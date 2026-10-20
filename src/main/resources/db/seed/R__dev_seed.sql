-- 로컬·테스트 프로파일 전용 시드 (spring.flyway.locations에 db/seed를 추가한 경우에만 적용된다).
-- 모든 계정의 비밀번호는 README의 "로컬 시드 계정" 표를 참고한다. 운영 배포에는 이 위치를 포함하지 않는다.
-- 반복 마이그레이션(R__)이라 내용이 바뀌면 다시 실행되며, upsert라 여러 번 실행해도 결과가 같다.

INSERT INTO platform.users (id, username, password_hash, nickname, role) VALUES
	('10000000-0000-4000-8000-000000000001', 'viewer',    '$2y$10$g8v1SJHc.kIgevXTb7pYkOCaASfB0VTAsnOea/ll6tMIDiQ7R7iza', '시청자', 'VIEWER'),
	('10000000-0000-4000-8000-000000000002', 'moderator', '$2y$10$g8v1SJHc.kIgevXTb7pYkOCaASfB0VTAsnOea/ll6tMIDiQ7R7iza', '운영자', 'MODERATOR'),
	('10000000-0000-4000-8000-000000000003', 'admin',     '$2y$10$g8v1SJHc.kIgevXTb7pYkOCaASfB0VTAsnOea/ll6tMIDiQ7R7iza', '관리자', 'PLATFORM_ADMIN')
ON CONFLICT (id) DO UPDATE SET
	username      = EXCLUDED.username,
	password_hash = EXCLUDED.password_hash,
	nickname      = EXCLUDED.nickname,
	role          = EXCLUDED.role;

INSERT INTO platform.channels (id, name, official_hosts, official_events) VALUES
	('20000000-0000-4000-8000-000000000001', '라이브 채널 A',
		'["live-a.example.com"]'::jsonb,
		'[{"title": "신규 가입 이벤트", "summary": "공식 이벤트 페이지에서만 참여할 수 있다"}]'::jsonb),
	('20000000-0000-4000-8000-000000000002', '라이브 채널 B',
		'["live-b.example.com", "shop.live-b.example.com"]'::jsonb,
		'[]'::jsonb)
ON CONFLICT (id) DO UPDATE SET
	name            = EXCLUDED.name,
	official_hosts  = EXCLUDED.official_hosts,
	official_events = EXCLUDED.official_events;

INSERT INTO platform.channel_members (channel_id, user_id, member_role) VALUES
	('20000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'VIEWER'),
	('20000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000002', 'MODERATOR'),
	('20000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', 'VIEWER'),
	('20000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000002', 'MODERATOR')
ON CONFLICT (channel_id, user_id) DO UPDATE SET
	member_role = EXCLUDED.member_role;
