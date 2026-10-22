package com.lifeguards.liveguard.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.support.CaseFixtures;

/** 사건 증거 snapshot(LivePlatformPort.snapshot)은 증거 앞뒤 10건만 담는다. 증거 사이가 길어도 커지지 않는다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class LiveSnapshotTest {

	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID MODERATOR = UUID.fromString("10000000-0000-4000-8000-000000000002");
	private static final Instant T0 = Instant.parse("2026-10-20T00:00:00Z");

	@Autowired
	private LiveService lives;
	@Autowired
	private JdbcTemplate jdbc;

	private UUID channel;
	private UUID live;
	private final List<UUID> messages = new ArrayList<>();

	@BeforeEach
	void setUp() {
		channel = UUID.randomUUID();
		live = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.channels (id, name) VALUES (?, 'snapshot-test')", channel);
		jdbc.update("INSERT INTO platform.lives (id, channel_id, started_at) VALUES (?, ?, ?)", live, channel,
				Timestamp.from(T0));
		messages.clear();
		for (int i = 0; i < 61; i++) {
			UUID id = UUID.randomUUID();
			jdbc.update("INSERT INTO platform.messages (id, channel_id, live_id, author_id, client_message_id, text, "
					+ "normalized_text, filter_result, visibility, sent_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'PASS', 'PUBLIC', ?)",
					id, channel, live, i % 2 == 0 ? VIEWER : MODERATOR, UUID.randomUUID(), "m" + i, "m" + i,
					Timestamp.from(T0.plusSeconds(i)));
			messages.add(id);
		}
	}

	@Test
	void keepsTenMessagesAroundEachEvidenceAndDropsTheLongGapBetween() {
		UUID caseId = UUID.randomUUID();
		CaseFixtures.insertCase(jdbc, caseId, channel, VIEWER);
		jdbc.update("UPDATE platform.messages SET case_id = ? WHERE id = ?", caseId, messages.get(4));

		// 증거: 사건이 소유한 4번과 관련 메시지 50번. 이전에는 4~50 사이 전체(0~60)를 담았다
		List<UUID> ids = ids(lives.snapshot(live, messages.get(4), VIEWER, List.of(messages.get(50))));

		List<UUID> expected = new ArrayList<>();
		IntStream.rangeClosed(0, 14).forEach(i -> expected.add(messages.get(i)));
		IntStream.rangeClosed(40, 60).forEach(i -> expected.add(messages.get(i)));
		assertEquals(expected, ids);
	}

	@Test
	void evidenceCloseTogetherKeepsTheWholeRangeAsBefore() {
		UUID caseId = UUID.randomUUID();
		CaseFixtures.insertCase(jdbc, caseId, channel, VIEWER);
		jdbc.update("UPDATE platform.messages SET case_id = ? WHERE id = ?", caseId, messages.get(30));

		List<UUID> ids = ids(lives.snapshot(live, messages.get(30), VIEWER, List.of(messages.get(45))));

		assertEquals(messages.subList(20, 56), ids);
	}

	private static List<UUID> ids(List<Map<String, Object>> snapshot) {
		return snapshot.stream().map(m -> (UUID) m.get("messageId")).toList();
	}

}
