-- 미해제 가림 원인 확인용(E-2, ERD 인덱스 표). 원인별 해제 뒤에 남은 원인이 있으면 PUBLIC으로 되돌리지 않는다.
CREATE INDEX message_hold_reasons_unreleased_idx
	ON platform.message_hold_reasons (message_id) WHERE released_at IS NULL;

-- 가림 원인은 메시지 없이는 의미가 없다. guard.candidates·case_messages의 messages FK와 같이 메시지를 지우면 함께 지운다.
ALTER TABLE platform.message_hold_reasons
	DROP CONSTRAINT message_hold_reasons_message_id_fkey,
	ADD CONSTRAINT message_hold_reasons_message_id_fkey
		FOREIGN KEY (message_id) REFERENCES platform.messages (id) ON DELETE CASCADE;
