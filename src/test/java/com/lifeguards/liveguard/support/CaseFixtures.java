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

	public static void deleteCases(JdbcTemplate jdbc) {
		jdbc.update("DELETE FROM guard.agent_task_outbox");
		jdbc.update("DELETE FROM guard.finding_events");
		jdbc.update("DELETE FROM guard.findings");
		jdbc.update("DELETE FROM guard.case_messages");
		jdbc.update("DELETE FROM guard.candidates");
		jdbc.update("UPDATE platform.messages SET case_id = NULL WHERE case_id IS NOT NULL");
		jdbc.update("DELETE FROM guard.cases");
	}
}
