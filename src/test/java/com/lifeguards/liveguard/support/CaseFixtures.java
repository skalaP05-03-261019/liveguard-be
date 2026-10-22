package com.lifeguards.liveguard.support;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

public final class CaseFixtures {

	private CaseFixtures() {
	}

	public static void insertCase(JdbcTemplate jdbc, UUID caseId, UUID channelId, UUID userId) {
		if (caseId != null) {
			jdbc.update("""
					INSERT INTO guard.cases(id, channel_id, user_id, snapshot_id, occurred_at, created_at)
					VALUES (?, ?, ?, ?, now(), now()) ON CONFLICT (id) DO NOTHING
					""", caseId, channelId, userId, UUID.randomUUID());
		}
	}

	/** 그 방송에서 사용자를 강퇴한 상태(guard.live_kicks)를 만든다. 강퇴 행이 가리키는 사건·검토·조치도 함께 넣는다. */
	public static void insertLiveBan(JdbcTemplate jdbc, UUID liveId, UUID channelId, UUID userId) {
		UUID caseId = UUID.randomUUID();
		UUID review = UUID.randomUUID();
		UUID action = UUID.randomUUID();
		insertCase(jdbc, caseId, channelId, userId);
		jdbc.update("INSERT INTO guard.reviews(id, case_id, kind, status, basis_snapshot_id, created_at) "
				+ "VALUES (?, ?, 'KICK', 'DECIDED', ?, now())", review, caseId, UUID.randomUUID());
		jdbc.update("INSERT INTO guard.live_actions(id, case_id, kind, status, created_at) "
				+ "VALUES (?, ?, 'KICK', 'SUCCEEDED', now())", action, caseId);
		jdbc.update("INSERT INTO guard.live_kicks(live_id, user_id, review_id, action_id) VALUES (?, ?, ?, ?)",
				liveId, userId, review, action);
	}

	public static void deleteCases(JdbcTemplate jdbc) {
		jdbc.update("DELETE FROM guard.agent_task_outbox");
		jdbc.update("DELETE FROM guard.live_kicks");
		jdbc.update("DELETE FROM guard.live_timeouts");
		jdbc.update("DELETE FROM guard.live_actions");
		jdbc.update("DELETE FROM guard.live_reservations");
		jdbc.update("DELETE FROM guard.finding_revocations");
		jdbc.update("DELETE FROM guard.decisions");
		jdbc.update("DELETE FROM guard.reviews");
		jdbc.update("DELETE FROM guard.flood_marks");
		jdbc.update("DELETE FROM guard.case_snapshots");
		jdbc.update("DELETE FROM guard.live_case_state");
		jdbc.update("DELETE FROM guard.live_user_state");
		jdbc.update("DELETE FROM platform.message_hold_reasons");
		jdbc.update("DELETE FROM guard.finding_events");
		jdbc.update("DELETE FROM guard.findings");
		jdbc.update("DELETE FROM guard.case_messages");
		jdbc.update("DELETE FROM guard.candidates");
		jdbc.update("UPDATE platform.messages SET case_id = NULL WHERE case_id IS NOT NULL");
		jdbc.update("DELETE FROM guard.cases");
	}
}
