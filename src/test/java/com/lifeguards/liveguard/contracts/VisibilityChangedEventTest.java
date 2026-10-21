package com.lifeguards.liveguard.contracts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.lifeguards.liveguard.contracts.dto.event.VisibilityChangedEvent;
import com.lifeguards.liveguard.contracts.enums.Visibility;

import tools.jackson.databind.json.JsonMapper;

class VisibilityChangedEventTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void hideEventCarriesOnlyIdVisibilityAndVersion() {
		String json = mapper.writeValueAsString(
				new VisibilityChangedEvent(UUID.randomUUID(), Visibility.HIDDEN, 2, null, null, null));

		assertTrue(json.contains("\"messageId\"") && json.contains("\"visibility\":\"HIDDEN\"")
				&& json.contains("\"version\":2"));
		assertFalse(json.contains("text") || json.contains("authorAlias") || json.contains("sentAt"), json);
	}

	@Test
	void restoreEventIncludesTextAuthorAliasAndSentAt() {
		String json = mapper.writeValueAsString(new VisibilityChangedEvent(UUID.randomUUID(), Visibility.PUBLIC, 3,
				"hello", "시청자", Instant.parse("2026-10-20T00:00:00Z")));

		assertTrue(json.contains("\"text\":\"hello\""));
		assertTrue(json.contains("\"authorAlias\":\"시청자\""));
		assertTrue(json.contains("\"sentAt\":\"2026-10-20T00:00:00Z\""), json);
	}

}
