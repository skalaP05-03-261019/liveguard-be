CREATE TABLE guard.live_case_state (
 case_id uuid PRIMARY KEY REFERENCES guard.cases(id), task_id uuid NOT NULL UNIQUE, thread_id text NOT NULL UNIQUE,
 snapshot jsonb NOT NULL, confirmation_status text NOT NULL DEFAULT 'PENDING',
 judgment jsonb, judgment_receipt jsonb, applied_result jsonb,
 CHECK (confirmation_status IN ('PENDING','CONFIRMED_NORMAL','CONFIRMED_VIOLATION','CANCELLED')));
CREATE TABLE guard.reviews (
 id uuid PRIMARY KEY, case_id uuid NOT NULL REFERENCES guard.cases(id), kind text NOT NULL CHECK(kind IN ('CONTEXT','KICK')),
 version integer NOT NULL DEFAULT 1, status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','DECIDED','INVALIDATED')),
 basis_case_ids uuid[] NOT NULL DEFAULT '{}', basis_snapshot_id uuid NOT NULL,
 created_at timestamptz NOT NULL, UNIQUE(case_id,kind));
CREATE TABLE guard.decisions (
 id uuid PRIMARY KEY, review_id uuid NOT NULL REFERENCES guard.reviews(id), client_request_id uuid NOT NULL,
 operator_id uuid NOT NULL, value text NOT NULL, reason text NOT NULL, categories text[] NOT NULL DEFAULT '{}',
 evidence_message_ids uuid[] NOT NULL DEFAULT '{}', decided_at timestamptz NOT NULL,
 request jsonb NOT NULL, UNIQUE(review_id,client_request_id));
CREATE UNIQUE INDEX final_decision_per_review ON guard.decisions(review_id) WHERE value <> 'DEFER';
CREATE TABLE guard.live_actions (
 id uuid PRIMARY KEY, case_id uuid NOT NULL REFERENCES guard.cases(id), decision_id uuid REFERENCES guard.decisions(id),
 kind text NOT NULL, status text NOT NULL, command jsonb, result jsonb, created_at timestamptz NOT NULL);
CREATE TABLE guard.live_timeouts (
 id uuid PRIMARY KEY, live_id uuid NOT NULL, user_id uuid NOT NULL, case_id uuid NOT NULL REFERENCES guard.cases(id),
 action_id uuid NOT NULL REFERENCES guard.live_actions(id), expires_at timestamptz NOT NULL,
 revoked_at timestamptz);
CREATE TABLE guard.live_kicks (
 live_id uuid NOT NULL, user_id uuid NOT NULL, review_id uuid NOT NULL UNIQUE REFERENCES guard.reviews(id),
 action_id uuid NOT NULL REFERENCES guard.live_actions(id), PRIMARY KEY(live_id,user_id));
CREATE TABLE guard.live_reservations (
 case_id uuid PRIMARY KEY REFERENCES guard.cases(id), review_id uuid NOT NULL REFERENCES guard.reviews(id),
 state text NOT NULL CHECK(state IN ('RESERVED','CONSUMED')));
ALTER TABLE guard.findings ADD COLUMN confirmed_at timestamptz;
ALTER TABLE guard.findings ADD COLUMN revoked_at timestamptz;
