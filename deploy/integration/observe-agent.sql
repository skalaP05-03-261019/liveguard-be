SELECT operation_id, status, code, attempts, last_worker_id,
       lease_expires_at, created_at, updated_at
FROM agent_inbox.jobs
ORDER BY created_at DESC LIMIT 30;
