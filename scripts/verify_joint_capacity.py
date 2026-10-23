"""Verify actual Agent capacity rejection and BE persisted Retry-After scheduling."""

import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import subprocess
import time
from uuid import UUID, uuid4

import httpx

from app.joint_test.run import JointEnvironment, JointError, poll, provenance, request, wait_job
from verify_joint_backend import require


def outbox(environment, task):
    return json.loads(environment.query("db",
        "SELECT json_build_object('status',status,'attempts',attempt_count,"
        "'uncertain',delivery_uncertain,'createdAt',created_at,'updatedAt',updated_at,'nextAttemptAt',next_attempt_at,"
        "'retryDelaySeconds',extract(epoch from next_attempt_at-updated_at),"
        "'untilDueSeconds',extract(epoch from next_attempt_at-clock_timestamp()),"
        "'payloadHash',md5(payload::text)) FROM guard.agent_task_outbox"
        f" WHERE operation_id='run:{UUID(task)}'"))


def verify(environment, report):
    report.update(mode="controlled-model-real-spring", realModelExecuted=False,
                  inboxCapacity=2, checks=[])
    overlay = environment.setup / "joint-capacity.yaml"
    environment.compose("-f", str(overlay), "config", "--quiet")
    environment.compose("-f", str(overlay), "up", "-d", "--wait", "--wait-timeout", "240", timeout=300)
    report["provenance"] = provenance(environment)
    environment.compose("stop", "worker-1", "worker-2", timeout=100)
    with httpx.Client(base_url=environment.url("backend", 8080), trust_env=False, timeout=15) as viewer:
        request(viewer, "GET", "/api/auth/me", expected=401)
        request(viewer, "POST", "/api/auth/login",
                body={"username": "viewer", "password": "liveguard-local"})
        live = str(UUID(request(viewer, "GET",
            "/api/channels/20000000-0000-4000-8000-000000000001/lives/current")["data"]["liveId"]))
        require(environment.query("db",
            f"SELECT count(*) FROM platform.messages WHERE live_id='{live}'::uuid") == "0",
            "UNUSED_LIVE_REQUIRED")
        cases = []
        for index in range(3):
            message = request(viewer, "POST", f"/api/lives/{live}/messages", expected=201,
                body={"clientMessageId": str(uuid4()),
                      "text": f"[JOINT96:NORMAL] 연락처 010-1234-5678 synthetic {uuid4().hex[:8]}"})["data"]
            message_id = str(UUID(message["messageId"]))
            raw = environment.query("db",
                "SELECT json_build_object('caseId',c.id,'taskId',s.task_id,'route',c.handling_route)"
                " FROM platform.messages m JOIN guard.cases c ON c.id=m.case_id"
                f" JOIN guard.live_case_state s ON s.case_id=c.id WHERE m.id='{message_id}'::uuid")
            case = json.loads(raw)
            require(case["route"] == "PII_INVESTIGATION", "PII_AUTOMATIC_NORMAL_PATH_REQUIRED")
            cases.append(case)
            # Make admission order deterministic, not dependent on DB poll ordering.
            expected = "ACCEPTED" if index < 2 else "RETRY"
            case["initialOutbox"] = poll(lambda: value if
                (value := outbox(environment, case["taskId"]))["status"] == expected else None)
        rejected = cases[-1]
        first = rejected["initialOutbox"]
        require(first["attempts"] == 1 and first["uncertain"] is False
                and datetime.fromisoformat(first["nextAttemptAt"]) >=
                datetime.fromisoformat(first["createdAt"]) + timedelta(seconds=10),
                "CAPACITY_NOT_A_CONFIRMED_DELAYED_REJECTION")
        require(environment.query("agent-db", "SELECT count(*) FROM agent_inbox.jobs") == "2",
                "CAPACITY_REJECTION_CREATED_A_JOB")
        # Check just before the stored deadline while workers remain stopped.
        time.sleep(max(0, first["untilDueSeconds"] - 1.5))
        before = outbox(environment, rejected["taskId"])
        require(before["untilDueSeconds"] > 0 and before["attempts"] == 1
                and before["status"] == "RETRY", "CAPACITY_RETRIED_BEFORE_PERSISTED_DEADLINE")
        environment.compose("start", "worker-1", "worker-2")
        for case in cases:
            case["execution"] = wait_job(environment, case["taskId"], "SUCCEEDED")
            case["finalOutbox"] = poll(lambda: value if
                (value := outbox(environment, case["taskId"]))["status"] == "ACCEPTED" else None)
            require(case["initialOutbox"]["payloadHash"] == case["finalOutbox"]["payloadHash"],
                    "RETRY_MUTATED_ENVELOPE")
        final = rejected["finalOutbox"]
        require(final["attempts"] >= 2 and final["uncertain"] is False
                and datetime.fromisoformat(final["updatedAt"]) >= datetime.fromisoformat(first["nextAttemptAt"]),
                "CAPACITY_RETRY_TIME_OR_IDENTITY_MISMATCH")
        require(environment.query("agent-db", "SELECT count(*) FROM agent_inbox.jobs") == "3",
                "CAPACITY_RECOVERY_DUPLICATED_JOB")
        report["checks"].append({"name": "actual-capacity-retry-after-and-drain", "status": "PASS",
                                 "input": 3, "cases": cases, "beforeDue": before})
    report["status"] = "PASS"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--be-repo", type=Path, required=True)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    args.action_loss = False
    report = {"status": "FAILED", "startedAt": datetime.now(timezone.utc).isoformat()}
    try:
        verify(JointEnvironment(args), report)
    except (JointError, httpx.HTTPError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        report["errorCode"] = str(error) if isinstance(error, JointError) else "BE78_CAPACITY_UNAVAILABLE"
        print("BE78_CAPACITY_FAILED: " + report["errorCode"])
        return 1
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n")
    print("BE78_CAPACITY_PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
