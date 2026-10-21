CREATE TABLE platform.message_hold_reasons (
 message_id uuid NOT NULL REFERENCES platform.messages(id), case_id uuid NOT NULL,
 reason text NOT NULL, created_at timestamptz NOT NULL, released_at timestamptz,
 PRIMARY KEY(message_id,case_id));
