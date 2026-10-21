-- Existing V102/V202 from merged #36 are immutable. Extend rather than rewrite them.
ALTER TABLE guard.live_reservations DROP CONSTRAINT live_reservations_state_check;
ALTER TABLE guard.live_reservations ADD CONSTRAINT live_reservations_state_check CHECK(state IN ('RESERVED','CONSUMED','RELEASED'));

CREATE TABLE guard.live_user_state (
 live_id uuid NOT NULL, user_id uuid NOT NULL, version integer NOT NULL DEFAULT 1 CHECK(version>0),
 PRIMARY KEY(live_id,user_id));

CREATE TABLE guard.finding_revocations (
 client_request_id uuid PRIMARY KEY,finding_id uuid NOT NULL REFERENCES guard.findings(id),operator_id uuid NOT NULL,
 request jsonb NOT NULL,result jsonb NOT NULL,created_at timestamptz NOT NULL);

CREATE TABLE guard.flood_marks (
 live_id uuid NOT NULL,user_id uuid NOT NULL,text_hash text NOT NULL,case_id uuid PRIMARY KEY REFERENCES guard.cases(id),
 confirmed_at timestamptz NOT NULL,last_sent_at timestamptz NOT NULL);
CREATE INDEX flood_marks_scope_idx ON guard.flood_marks(live_id,user_id,text_hash,confirmed_at);
