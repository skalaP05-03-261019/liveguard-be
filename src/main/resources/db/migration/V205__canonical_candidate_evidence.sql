-- Extend merged migrations; never infer readiness for pre-provenance candidates.
ALTER TABLE guard.candidates ADD COLUMN live_id uuid REFERENCES platform.lives(id);
ALTER TABLE guard.candidates ADD COLUMN handling_route text;
ALTER TABLE guard.candidates ADD COLUMN rule_id text;
ALTER TABLE guard.candidates ADD COLUMN policy_version text;
ALTER TABLE guard.candidates ADD COLUMN request jsonb;
ALTER TABLE guard.candidates ADD CONSTRAINT candidate_provenance CHECK (
 request IS NULL OR (live_id IS NOT NULL AND policy_version IS NOT NULL
 AND handling_route IN ('RULE_CONFIRMED','CONTEXT_REVIEW','PII_INVESTIGATION','FLOOD_REVIEW')
 AND ((handling_route = 'RULE_CONFIRMED') = (rule_id IS NOT NULL))));

CREATE TABLE guard.case_snapshots (
 snapshot_id uuid PRIMARY KEY,
 case_id uuid NOT NULL REFERENCES guard.cases(id),
 evidence_version integer NOT NULL CHECK(evidence_version > 0),
 snapshot jsonb NOT NULL,
 UNIQUE(case_id,evidence_version));
INSERT INTO guard.case_snapshots(snapshot_id,case_id,evidence_version,snapshot)
 SELECT c.snapshot_id,c.id,c.evidence_version,s.snapshot
 FROM guard.cases c JOIN guard.live_case_state s ON s.case_id=c.id
 WHERE (s.snapshot->>'snapshotId')::uuid=c.snapshot_id
 AND (s.snapshot->>'evidenceVersion')::integer=c.evidence_version;

-- Legacy finding writers recorded confirmation/revocation in the audit before these columns existed.
UPDATE guard.findings f SET confirmed_at=COALESCE(
 (SELECT min(e.created_at) FROM guard.finding_events e WHERE e.finding_id=f.id AND e.event_type='CONFIRMED'),
 f.created_at)
 WHERE f.source='BACKEND' AND f.confirmed_at IS NULL;
UPDATE guard.findings f SET revoked_at=COALESCE(
 (SELECT max(e.created_at) FROM guard.finding_events e WHERE e.finding_id=f.id AND e.event_type='REVOKED'),
 f.created_at)
 WHERE f.status='REVOKED' AND f.revoked_at IS NULL;
CREATE INDEX findings_confirmed_time_idx ON guard.findings(case_id,confirmed_at)
 WHERE source='BACKEND' AND status='CONFIRMED' AND verdict='VIOLATION' AND revoked_at IS NULL;
