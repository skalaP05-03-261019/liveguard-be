-- Multiple independently approved kick reasons must survive revocation of one reason.
ALTER TABLE guard.live_kicks DROP CONSTRAINT live_kicks_pkey;
ALTER TABLE guard.live_kicks ADD PRIMARY KEY(review_id);
CREATE INDEX live_kicks_scope_idx ON guard.live_kicks(live_id,user_id);
