-- Safe metadata only: never select payload, envelope, lease tokens or raw chat.
SELECT id, operation_id, task_id, case_id, status, attempt_count,
       next_attempt_at, delivery_uncertain, last_error_code, updated_at
FROM guard.agent_task_outbox
ORDER BY created_at DESC LIMIT 30;

SELECT c.id AS case_id, c.live_id, c.handling_route, s.confirmation_status,
       s.judgment IS NOT NULL AS has_ai_judgment, r.id AS review_id,
       r.kind AS review_kind, r.status AS review_status
FROM guard.cases c
JOIN guard.live_case_state s ON s.case_id=c.id
LEFT JOIN guard.reviews r ON r.case_id=c.id
ORDER BY c.created_at DESC LIMIT 30;
