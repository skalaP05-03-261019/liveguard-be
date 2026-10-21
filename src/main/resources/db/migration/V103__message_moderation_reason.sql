-- 작성자에게 보여 줄 처리 사유(A-3, A-22). 메시지를 저장하는 시점의 1차 필터 결과로 정한다.
-- 값 5종은 openapi ModerationReasonCode와 같다. PASS는 NULL이고, 방송 도입 전 메시지도 NULL이다.
ALTER TABLE platform.messages
	ADD COLUMN moderation_reason_code text
		CHECK (moderation_reason_code IN ('PROFANITY_INCLUDED', 'LINK_NOT_ALLOWED', 'FLOOD_REPEATED',
		                                  'CONTEXT_PENDING', 'PII_PENDING'));
