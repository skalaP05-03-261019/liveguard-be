-- Explicit local maintenance BEFORE V200; never run automatically at application startup.
-- Stop Backend and take a full pg_dump backup first. Text/visibility/version are untouched.
-- Old InMemoryCandidateSink IDs were not durable cases or confirmed findings.
\set ON_ERROR_STOP on
BEGIN;
LOCK TABLE platform.messages IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
    IF to_regclass('guard.cases') IS NOT NULL THEN
        RAISE EXCEPTION 'guard.cases already exists; this pre-V200 procedure does not apply';
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS guard.legacy_candidate_links (
    message_id uuid PRIMARY KEY,
    case_id uuid NOT NULL,
    archived_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source text NOT NULL DEFAULT 'PRE_V200_IN_MEMORY_CANDIDATE'
);

-- Do not overwrite a prior mapping if maintenance was interrupted and state changed.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM platform.messages m
        JOIN guard.legacy_candidate_links a ON a.message_id = m.id
        WHERE m.case_id IS NOT NULL AND m.case_id <> a.case_id) THEN
        RAISE EXCEPTION 'Legacy mapping conflict; inspect the backup before proceeding';
    END IF;
END $$;

INSERT INTO guard.legacy_candidate_links (message_id, case_id)
SELECT id, case_id FROM platform.messages WHERE case_id IS NOT NULL
ON CONFLICT (message_id) DO NOTHING;

UPDATE platform.messages m SET case_id = NULL
FROM guard.legacy_candidate_links a
WHERE m.id = a.message_id AND m.case_id = a.case_id;
COMMIT;

-- Archive is intentionally not migrated into cases/findings. No verdict is fabricated.
SELECT count(*) AS archived_legacy_message_links FROM guard.legacy_candidate_links;
