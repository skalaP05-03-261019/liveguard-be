"""Actual BE-produced live scopes and Worker lease recovery using Agent #96 tools."""

import argparse
from contextlib import ExitStack
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
from uuid import UUID, uuid4

import httpx

from app.joint_test.run import (
    JointEnvironment, JointError, decide, job, poll, request, select_review, wait_job,
)
from verify_joint_backend import require


def produce(environment, viewer, live, marker):
    message = request(viewer, "POST", f"/api/lives/{live}/messages", expected=201,
        body={"clientMessageId": str(uuid4()),
              "text": f"[JOINT96:NORMAL] 공격 표현 '꺼져'를 분석하는 합성 인용문 {marker} {uuid4().hex[:8]}"})["data"]
    message_id = str(UUID(message["messageId"]))
    raw = environment.query("db",
        "SELECT json_build_object('taskId',s.task_id,'caseId',s.case_id,'threadId',s.thread_id)"
        " FROM guard.live_case_state s JOIN platform.messages m ON m.case_id=s.case_id"
        f" WHERE m.id='{message_id}'::uuid")
    require(bool(raw), "REAL_CASE_NOT_CREATED")
    return {**json.loads(raw), "liveId": str(UUID(live)), "messageId": message_id}


def verify(environment, report):
    report.update(mode="controlled-model-real-spring", realModelExecuted=False, checks=[])
    checks = report["checks"]
    with ExitStack() as stack:
        backend_url = environment.url("backend", 8080)
        viewer = stack.enter_context(httpx.Client(base_url=backend_url, trust_env=False, timeout=15))
        operator = stack.enter_context(httpx.Client(base_url=backend_url, trust_env=False, timeout=15))
        fixture = stack.enter_context(httpx.Client(
            base_url=environment.url("controlled-fixture", 8090), trust_env=False, timeout=15,
            headers={"Authorization": "Bearer " + environment.values["MODEL_API_KEY"]}))
        for client, username in ((viewer, "viewer"), (operator, "moderator")):
            request(client, "GET", "/api/auth/me", expected=401)
            request(client, "POST", "/api/auth/login",
                    body={"username": username, "password": "liveguard-local"})
        # The BE postconditions suite ended A; start a genuine new live via the browser API.
        channel_a = "20000000-0000-4000-8000-000000000001"
        channel_b = "20000000-0000-4000-8000-000000000002"
        live_a = request(operator, "POST", f"/api/channels/{channel_a}/lives", expected=201)["data"]["liveId"]
        live_b = request(viewer, "GET", f"/api/channels/{channel_b}/lives/current")["data"]["liveId"]
        live_a, live_b = str(UUID(live_a)), str(UUID(live_b))
        try:
            environment.compose("stop", "worker-1", "worker-2", timeout=100)
            cases = [produce(environment, viewer, live, "fairness")
                     for live in (live_a, live_a, live_a, live_a, live_b, live_b)]
            environment.compose("start", "worker-1", "worker-2")
            for worker in ("worker-1", "worker-2"):
                poll(lambda: environment.worker_ready(worker), timeout=40)
            for case in cases:
                case["analysis"] = wait_job(environment, case["taskId"], "WAITING_REVIEW")
            task_ids = ",".join(str(UUID(case["taskId"])) for case in cases)
            admissions = json.loads(environment.query("agent-db",
                "SELECT json_agg(row_to_json(s)) FROM (SELECT j.live_id,"
                "t.task_id,j.admitted_at,j.last_worker_id FROM agent_inbox.jobs j"
                " JOIN agent_inbox.threads t ON t.thread_id=j.thread_id"
                f" WHERE t.task_id=ANY(string_to_array('{task_ids}',','))"
                " ORDER BY j.admitted_at) s"))
            require(len(admissions) == 6, "EXPECTED_SIX_REAL_LIVE_ADMISSIONS")
            require({row["live_id"] for row in admissions[:4]} == {live_a, live_b},
                    "BROADCAST_STARVED_IN_FIRST_FOUR_ADMISSIONS")
            for case in cases:
                envelope_live = environment.query("db",
                    "SELECT payload->>'liveId' FROM guard.agent_task_outbox"
                    f" WHERE operation_id='run:{UUID(case['taskId'])}'")
                require(envelope_live == case["liveId"], "OUTBOX_LIVE_SCOPE_MISMATCH")
                review = select_review(operator, case["liveId"], case["caseId"], "CONTEXT")
                if case is cases[0]:
                    stale = request(operator, "POST",
                        f"/api/context-reviews/{review['reviewId']}/decision", expected=409,
                        body={"clientRequestId": str(uuid4()), "expectedReviewVersion": review["version"] + 1,
                              "decision": "CONFIRM_NORMAL", "reason": "Synthetic stale version rejection."})
                    require(stale["error"]["code"] == "STALE_VERSION", "STALE_REVIEW_NOT_REJECTED")
                decision = decide(operator, review, "CONFIRM_NORMAL", case["messageId"])
                case["resume"] = wait_job(environment, case["taskId"], "SUCCEEDED", decision_id=decision)
            checks.append({"name": "two-real-lives-fair-admission", "status": "PASS",
                           "inputPerLive": [4, 2], "firstFourCoverBothLives": True,
                           "admissions": admissions, "cases": cases})
            checks.append({"name": "stale-review-version-rejected", "status": "PASS",
                           "errorCode": "STALE_VERSION"})

            before = request(fixture, "GET", "/control")["timeouts"]
            request(fixture, "POST", "/control", body={"fault": "model-timeout"})
            case = produce(environment, viewer, live_a, "worker-kill")
            poll(lambda: request(fixture, "GET", "/control")["timeouts"] > before)
            running = job(environment, case["taskId"])
            require(running["status"] == "RUNNING", "MODEL_FAULT_DID_NOT_HOLD_RUNNING_JOB")
            owner = {"integration-worker-1": "worker-1",
                     "integration-worker-2": "worker-2"}.get(running["workerId"])
            require(owner is not None, "WORKER_OWNER_REQUIRED")
            environment.compose("kill", "-s", "SIGKILL", owner)
            recovered = wait_job(environment, case["taskId"], {"WAITING_REVIEW", "UNKNOWN", "NEEDS_REVIEW"})
            if recovered["status"] == "WAITING_REVIEW":
                require(recovered["workerId"] != running["workerId"] and recovered["attempts"] >= 2,
                        "LEASE_RECOVERY_NOT_PROVEN")
                review = select_review(operator, live_a, case["caseId"], "CONTEXT")
                decision = decide(operator, review, "CONFIRM_NORMAL", case["messageId"])
                case["resume"] = wait_job(environment, case["taskId"], "SUCCEEDED", decision_id=decision)
            else:
                require(bool(recovered["code"]), "UNCERTAIN_RECOVERY_WITHOUT_EXPLICIT_CODE")
            case["recovery"] = recovered
            require(environment.query("agent-db",
                f"SELECT count(*) FROM agent_inbox.jobs WHERE operation_id='run:{UUID(case['taskId'])}'") == "1",
                "WORKER_RECOVERY_CREATED_SECOND_OPERATION")
            require(environment.query("db",
                f"SELECT count(*) FROM guard.reviews WHERE case_id='{UUID(case['caseId'])}' AND kind='CONTEXT'") == "1",
                "WORKER_RECOVERY_CREATED_DUPLICATE_REVIEW")
            checks.append({"name": "running-worker-kill-and-expired-lease", "status": "PASS",
                           "previousWorkerId": running["workerId"], "case": case,
                           "agentOutcome": recovered["status"]})
        finally:
            environment.compose("start", "worker-1", "worker-2")
    report["status"] = "PASS"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--be-repo", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--action-loss", action="store_true")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    report = {"status": "FAILED", "startedAt": datetime.now(timezone.utc).isoformat()}
    try:
        verify(JointEnvironment(args), report)
    except (JointError, httpx.HTTPError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        report["errorCode"] = str(error) if isinstance(error, JointError) else "BE78_RECOVERY_UNAVAILABLE"
        print("BE78_RECOVERY_FAILED: " + report["errorCode"])
        return 1
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n")
    print("BE78_RECOVERY_PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
