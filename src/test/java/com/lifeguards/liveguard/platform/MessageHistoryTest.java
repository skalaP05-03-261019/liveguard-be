package com.lifeguards.liveguard.platform;

import static com.lifeguards.liveguard.support.LoginSupport.login;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.support.CaseFixtures;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MessageHistoryTest {

	private static final UUID CHANNEL_A = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID MODERATOR = UUID.fromString("10000000-0000-4000-8000-000000000002");
	private static final UUID VIEWER2 = UUID.fromString("10000000-0000-4000-8000-0000000000f3");
	private static final Instant T0 = Instant.parse("2026-10-20T00:00:00Z");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void addSecondViewer() {
		jdbc.update("INSERT INTO platform.users (id, username, password_hash, nickname, role) "
				+ "VALUES (?, 'history-viewer2', 'h', '시청자2', 'VIEWER')", VIEWER2);
		jdbc.update("INSERT INTO platform.channel_members (channel_id, user_id, member_role) VALUES (?, ?, 'VIEWER')",
				CHANNEL_A, VIEWER2);
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM platform.messages");
		CaseFixtures.deleteCases(jdbc);
		jdbc.update("DELETE FROM platform.channel_members WHERE user_id = ?", VIEWER2);
		jdbc.update("DELETE FROM platform.users WHERE id = ?", VIEWER2);
	}

	@Test
	void viewerGetsPublicAndOwnHeldHiddenButNoOtherUsersHeldOrHidden() throws Exception {
		insertVisibilityMix();

		String body = mockMvc.perform(history("?limit=100", login(mockMvc, "viewer")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertFalse(body.contains("MOD-HELD"), "타인 HELD 원문 0건");
		assertFalse(body.contains("V2-HIDDEN"), "타인 HIDDEN 원문 0건");
		List<String> texts = JsonPath.read(body, "$.data.items[*].text");
		assertEquals(List.of("MY-HIDDEN", "MY-HELD", "public 2", "public 1"), texts);
		List<Boolean> mine = JsonPath.read(body, "$.data.items[*].isMine");
		assertEquals(List.of(true, true, false, false), mine);
		List<String> aliases = JsonPath.read(body, "$.data.items[*].authorAlias");
		assertEquals(List.of("시청자", "시청자", "운영자", "운영자"), aliases);
	}

	@Test
	void otherViewerAlsoDoesNotSeeViewersHeldOrHidden() throws Exception {
		insertVisibilityMix();
		jdbc.update("UPDATE platform.users SET password_hash = (SELECT password_hash FROM platform.users "
				+ "WHERE username = 'viewer') WHERE id = ?", VIEWER2);

		String body = mockMvc.perform(history("?limit=100", login(mockMvc, "history-viewer2")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertFalse(body.contains("MY-HELD"));
		assertFalse(body.contains("MY-HIDDEN"));
		assertFalse(body.contains("MOD-HELD"));
		List<String> texts = JsonPath.read(body, "$.data.items[*].text");
		assertEquals(List.of("V2-HIDDEN", "public 2", "public 1"), texts);
	}

	@Test
	void moderatorGetsEverything() throws Exception {
		insertVisibilityMix();

		String body = mockMvc.perform(history("?limit=100", login(mockMvc, "moderator")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		List<String> visibilities = JsonPath.read(body, "$.data.items[*].visibility");
		assertEquals(6, visibilities.size());
		assertTrue(visibilities.containsAll(List.of("PUBLIC", "HELD", "HIDDEN")));
		assertTrue(body.contains("MOD-HELD") && body.contains("V2-HIDDEN") && body.contains("MY-HELD"));
	}

	@Test
	void pagesNewestFirstWithoutDuplicatesOrGapsEvenWithSameTimestamps() throws Exception {
		// 7건은 같은 시각, 5건은 서로 다른 시각
		for (int i = 0; i < 7; i++) {
			insert(MODERATOR, "same " + i, "PUBLIC", T0.plusSeconds(10));
		}
		for (int i = 0; i < 5; i++) {
			insert(VIEWER, "distinct " + i, "PUBLIC", T0.plusSeconds(i));
		}
		List<String> expected = jdbc.queryForList(
				"SELECT id::text FROM platform.messages ORDER BY sent_at DESC, id DESC", String.class);
		MockHttpSession session = login(mockMvc, "viewer");

		List<String> collected = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			String body = mockMvc.perform(history("?limit=3" + (cursor == null ? "" : "&cursor=" + cursor), session))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString();
			collected.addAll(JsonPath.read(body, "$.data.items[*].messageId"));
			cursor = JsonPath.read(body, "$.data.nextCursor");
			pages++;
		} while (cursor != null);

		assertEquals(expected, collected);
		assertEquals(4, pages);
	}

	@Test
	void lastPageHasNullNextCursorAndDefaultLimitIs50() throws Exception {
		for (int i = 0; i < 55; i++) {
			insert(MODERATOR, "m" + i, "PUBLIC", T0.plusSeconds(i));
		}
		MockHttpSession session = login(mockMvc, "viewer");

		String first = mockMvc.perform(history("", session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(50))
				.andExpect(jsonPath("$.data.items[0].text").value("m54"))
				.andReturn().getResponse().getContentAsString();
		String cursor = JsonPath.read(first, "$.data.nextCursor");

		String last = mockMvc.perform(history("?cursor=" + cursor, session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(5))
				.andExpect(jsonPath("$.data.nextCursor").value(nullValue()))
				.andReturn().getResponse().getContentAsString();
		assertTrue(last.contains("\"nextCursor\":null"), "nextCursor는 필드가 있고 null이다");
	}

	@Test
	void emptyChannelReturnsEmptyItems() throws Exception {
		mockMvc.perform(history("", login(mockMvc, "viewer")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(0))
				.andExpect(jsonPath("$.data.nextCursor").value(nullValue()))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
	}

	@Test
	void invalidLimitOrCursorIs400() throws Exception {
		MockHttpSession session = login(mockMvc, "viewer");
		for (String query : new String[] { "?limit=0", "?limit=101", "?limit=abc", "?cursor=!!!", "?cursor=YWJj" }) {
			mockMvc.perform(history(query, session))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}
	}

	@Test
	void nonMembersGet403AndAnonymousGets401() throws Exception {
		mockMvc.perform(history("", login(mockMvc, "admin")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		mockMvc.perform(get("/api/channels/" + UUID.randomUUID() + "/messages").session(login(mockMvc, "viewer")))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/channels/" + CHANNEL_A + "/messages"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
	}

	private void insertVisibilityMix() {
		insert(MODERATOR, "public 1", "PUBLIC", T0.plusSeconds(1));
		insert(MODERATOR, "public 2", "PUBLIC", T0.plusSeconds(2));
		insert(MODERATOR, "MOD-HELD", "HELD", T0.plusSeconds(3));
		insert(VIEWER2, "V2-HIDDEN", "HIDDEN", T0.plusSeconds(4));
		insert(VIEWER, "MY-HELD", "HELD", T0.plusSeconds(5));
		insert(VIEWER, "MY-HIDDEN", "HIDDEN", T0.plusSeconds(6));
	}

	private void insert(UUID author, String text, String visibility, Instant sentAt) {
		jdbc.update("INSERT INTO platform.messages (channel_id, author_id, client_message_id, text, normalized_text, "
				+ "filter_result, visibility, sent_at) VALUES (?, ?, ?, ?, ?, 'PASS', ?, ?)",
				CHANNEL_A, author, UUID.randomUUID(), text, text, visibility, Timestamp.from(sentAt));
	}

	private static MockHttpServletRequestBuilder history(String query, MockHttpSession session) {
		return get("/api/channels/" + CHANNEL_A + "/messages" + query).session(session);
	}

}
