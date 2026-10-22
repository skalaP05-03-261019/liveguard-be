-- 결정별 강퇴 실행 기록 조회(재검증, 넘겨받기 대상·BE 실행기 주기 조회, A-25)
CREATE INDEX live_actions_kick_decision_idx ON guard.live_actions(decision_id) WHERE kind = 'KICK';
