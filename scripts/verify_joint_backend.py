"""BE assertions layered on the existing Agent #96 runner, not another Graph."""

import argparse
import asyncio
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import time
from uuid import UUID, uuid4

import httpx

from app.clients.business_mcp import AgentToolError, BusinessMcpReadClient, BusinessMcpSettings
from app.joint_test.run import JointEnvironment, JointError, poll, request


def require(condition, code):
    if not condition:
        raise JointError(code)


def query_json(environment, service, sql):
    return json.loads(environment.query(service, sql))


def business_counts(environment):
    return query_json(environment, "db",
        "SELECT json_build_object("
        "'cases',(SELECT count(*) FROM guard.cases),"
        "'reviews',(SELECT count(*) FROM guard.reviews),"
        "'decisions',(SELECT count(*) FROM guard.decisions),"
        "'findings',(SELECT count(*) FROM guard.findings),"
        "'actions',(SELECT count(*) FROM guard.live_actions),"
        "'kicks',(SELECT count(*) FROM guard.live_kicks),"
        "'outbox',(SELECT count(*) FROM guard.agent_task_outbox))")


def read_action(client, action_id):
    return asyncio.run(client.call("get_action_status", {
        "actionId": str(UUID(action_id)), "traceId": str(uuid4()),
    }))


def verify(environment, source, report):
    require(source["status"] == "PASS" and len(source["results"]) == 5, "FULL_JOINT_PASS_REQUIRED")
    require(source["provenance"]["agentRevision"] ==
            environment.environment["AGENT_REVISION"], "PINNED_AGENT_REVISION_REQUIRED")
    report.update(mode="controlled-model-real-spring", realModelExecuted=False,
                  provenance=source["provenance"], checks=[], sourceReportStatus=source["status"])
    checks = report["checks"]
    kick = next(result for result in source["results"] if result["scenario"].endswith("-kick"))
    action_id = str(UUID(kick["kickAction"]["actionId"]))
    before = business_counts(environment)
    jobs_before = environment.query("agent-db", "SELECT count(*) FROM agent_inbox.jobs")
    # Keep immutable envelopes in memory only. Reports never contain their contents.
    envelopes = query_json(environment, "db",
        "SELECT json_agg(payload ORDER BY created_at) FROM guard.agent_task_outbox")
    require(len(envelopes) == 12, "EXPECTED_FIVE_RUN_SEVEN_RESUME_OPERATIONS")
    base_url = environment.url("agent-api", 8001)

    def duplicate(envelope):
        suffix = "resume" if envelope["kind"] == "RESUME_REVIEW" else "run"
        task_id = str(UUID(envelope["taskId"]))
        with httpx.Client(base_url=base_url, trust_env=False, timeout=15,
                          headers={"Authorization": "Bearer " + environment.values["AGENT_SERVICE_TOKEN"]}) as api:
            receipt = request(api, "POST", f"/agent/tasks/{task_id}/{suffix}", body=envelope)
        operation = ("resume:" + str(UUID(envelope["decisionId"])) if suffix == "resume"
                     else "run:" + task_id)
        require(receipt["duplicate"] is True and receipt["operationId"] == operation,
                "DUPLICATE_RECEIPT_MISMATCH")
        return operation

    with ThreadPoolExecutor(max_workers=4) as executor:
        receipts = list(executor.map(duplicate, envelopes + envelopes))
    require(environment.query("agent-db", "SELECT count(*) FROM agent_inbox.jobs") == jobs_before,
            "DUPLICATE_CREATED_AGENT_JOB")
    require(business_counts(environment) == before, "DUPLICATE_CHANGED_BUSINESS_COUNTS")
    checks.append({"name": "concurrent-duplicate-run-resume", "status": "PASS",
                   "requests": len(receipts), "operations": len(set(receipts)),
                   "businessCountsBefore": before, "businessCountsAfter": business_counts(environment)})

    bindings = query_json(environment, "db",
        "SELECT json_agg(json_build_object('taskId',o.task_id,'operationId',o.operation_id,"
        "'caseId',c.id,'liveId',c.live_id,'snapshotId',c.snapshot_id,"
        "'evidenceVersion',c.evidence_version,'policyVersion',c.policy_version,"
        "'reviewId',o.payload->>'reviewId','decisionId',o.payload->>'decisionId',"
        "'scopeMatches',o.payload->>'liveId'=c.live_id::text"
        " AND o.payload->>'snapshotId'=c.snapshot_id::text"
        " AND (o.payload->>'expectedEvidenceVersion')::int=c.evidence_version"
        " AND s.snapshot=cs.snapshot))"
        " FROM guard.agent_task_outbox o JOIN guard.cases c ON c.id=o.case_id"
        " JOIN guard.live_case_state s ON s.case_id=c.id"
        " JOIN guard.case_snapshots cs ON cs.snapshot_id=c.snapshot_id")
    require(len(bindings) == 12 and all(row["scopeMatches"] for row in bindings),
            "IMMUTABLE_EVIDENCE_BINDING_MISMATCH")
    report["bindings"] = bindings
    checks.append({"name": "immutable-snapshot-live-evidence-bindings", "status": "PASS",
                   "operations": len(bindings)})

    backend_url = environment.url("backend", 8080)
    with ExitStack() as stack:
        internal = stack.enter_context(httpx.Client(base_url=backend_url, trust_env=False, timeout=15,
            headers={"X-Service-Token": environment.values["INTERNAL_SERVICE_TOKEN"]}))
        operator = stack.enter_context(httpx.Client(base_url=backend_url, trust_env=False, timeout=15))
        request(operator, "GET", "/api/auth/me", expected=401)
        request(operator, "POST", "/api/auth/login",
                body={"username": "moderator", "password": "liveguard-local"})
        decision_id = str(UUID(kick["kickResume"]["operationId"].split(":", 1)[1]))
        review_id = environment.query("db",
            f"SELECT review_id FROM guard.decisions WHERE id='{decision_id}'::uuid")
        review_id = str(UUID(review_id))
        validated = request(internal, "POST", f"/internal/kick-reviews/{review_id}/revalidate",
                            body={"decisionId": decision_id})["data"]
        command = validated["actionCommand"]
        require(command["actionId"] == action_id, "REVALIDATION_CREATED_NEW_ACTION")
        first = request(internal, "POST", f"/internal/actions/{action_id}/execute", body=command)["data"]
        second = request(internal, "POST", f"/internal/actions/{action_id}/execute", body=command)["data"]
        require(first == second and first["status"] == "SUCCEEDED", "EXECUTE_REPLAY_RESULT_CHANGED")
        require(business_counts(environment) == before, "EXECUTE_REPLAY_DUPLICATED_EFFECT")
        checks.append({"name": "actual-kick-command-revalidation-execution-replay", "status": "PASS",
                       "reviewId": review_id, "decisionId": decision_id, "actionId": action_id})

        client = BusinessMcpReadClient(BusinessMcpSettings(
            environment.url("agent-api", 8002) + "/mcp",
            environment.values["BUSINESS_MCP_AGENT_TOKEN"]))

        def confirmed_read():
            try:
                result = read_action(client, action_id)
            except AgentToolError as error:
                if error.retryable:
                    return None
                raise
            require(result["data"]["status"] == "SUCCEEDED", "ACTION_STATUS_NOT_SUCCEEDED")
            return result

        try:
            environment.compose("stop", "mcp-1", timeout=40)
            poll(confirmed_read, timeout=20)
            checks.append({"name": "one-mcp-down-real-action-read", "status": "PASS",
                           "survivor": "mcp-2", "actionId": action_id})
            environment.compose("stop", "mcp-2", timeout=40)
            # Both real servers are stopped; allow the existing 2x1s health checks to converge.
            time.sleep(3)
            try:
                read_action(client, action_id)
            except AgentToolError as error:
                require(error.retryable, "TOTAL_MCP_OUTAGE_NOT_RETRYABLE")
                checks.append({"name": "total-mcp-outage-explicit-error", "status": "PASS",
                               "errorCode": error.code})
            else:
                raise JointError("TOTAL_MCP_OUTAGE_RETURNED_SUCCESS")
        finally:
            environment.compose("up", "-d", "--no-deps", "--wait", "mcp-1", "mcp-2", timeout=90)
        poll(confirmed_read, timeout=20)
        require(business_counts(environment) == before, "MCP_OUTAGE_CHANGED_BUSINESS_COUNTS")
        checks.append({"name": "mcp-recovery-existing-action-read", "status": "PASS",
                       "actionId": action_id})

        case_id = str(UUID(source["results"][2]["caseId"]))
        finding = environment.query("db",
            f"SELECT id FROM guard.findings WHERE case_id='{case_id}'::uuid"
            " AND source='BACKEND' AND status='CONFIRMED' LIMIT 1")
        finding = str(UUID(finding))
        detail = request(operator, "GET", f"/api/cases/{case_id}")["data"]
        revoke = {"clientRequestId": str(uuid4()), "expectedCaseVersion": detail["version"],
                  "reason": "Synthetic BE78 replay and recovery verification."}
        revoked = request(operator, "POST", f"/api/findings/{finding}/revoke", body=revoke)["data"]
        require(request(operator, "POST", f"/api/findings/{finding}/revoke", body=revoke)["data"] == revoked,
                "FINDING_REVOCATION_REPLAY_CHANGED")
        require(environment.query("db", "SELECT count(*) FROM guard.live_kicks") == "0",
                "REVOKED_BASIS_LEFT_LIVE_KICK")
        require(request(internal, "GET", f"/internal/actions/{action_id}")["data"] == first,
                "REVOCATION_REWROTE_ACTION_HISTORY")
        require(request(internal, "POST", f"/internal/actions/{action_id}/execute", body=command)["data"] == first,
                "REVOCATION_REEXECUTED_OLD_ACTION")
        require(environment.query("db", "SELECT count(*) FROM guard.live_kicks") == "0",
                "OLD_ACTION_RECREATED_REVOKED_KICK")
        checks.append({"name": "finding-revocation-and-old-result-replay", "status": "PASS",
                       "findingId": finding, "caseId": case_id, "actionId": action_id})
        live_id = str(UUID(kick["liveId"]))
        request(operator, "POST", f"/api/lives/{live_id}/end")
        require(request(internal, "GET", f"/internal/actions/{action_id}")["data"] == first,
                "LIVE_END_REWROTE_ACTION_HISTORY")
        require(request(internal, "POST", f"/internal/actions/{action_id}/execute", body=command)["data"] == first,
                "LIVE_END_REEXECUTED_OLD_ACTION")
        require(environment.query("db", "SELECT count(*) FROM guard.live_kicks") == "0",
                "ENDED_LIVE_RECREATED_KICK")
        checks.append({"name": "ended-live-retains-result-without-reexecution", "status": "PASS",
                       "liveId": live_id, "actionId": action_id})
    report["finalBusinessCounts"] = business_counts(environment)
    report["status"] = "PASS"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--be-repo", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--action-loss", action="store_true")
    parser.add_argument("--source-report", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    report = {"status": "FAILED", "startedAt": datetime.now(timezone.utc).isoformat()}
    try:
        environment = JointEnvironment(args)
        verify(environment, json.loads(args.source_report.read_text()), report)
    except (JointError, httpx.HTTPError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        report["errorCode"] = str(error) if isinstance(error, JointError) else "BE78_VERIFICATION_UNAVAILABLE"
        print("BE78_FAILED: " + report["errorCode"])
        return 1
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n")
    print("BE78_PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
