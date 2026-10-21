package com.lifeguards.liveguard.platform.port;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.MatchingTag;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

class CandidateRequestTest {

	private static CandidateRequest request(List<String> tags, String filterVersion) {
		return new CandidateRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				FilterResult.FLOOD, tags,
				filterVersion, Instant.parse("2026-10-20T12:00:00Z"), List.of());
	}

	@Test
	void carriesTheFilterVersion() {
		assertEquals("norm-v1+dict-v2", request(List.of("REPEATED_TEXT"), "norm-v1+dict-v2").filterVersion());
	}

	@Test
	void rejectsMissingOrBlankFilterVersion() {
		assertThrows(InvalidContractException.class, () -> request(List.of("REPEATED_TEXT"), null));
		assertThrows(InvalidContractException.class, () -> request(List.of("REPEATED_TEXT"), "  "));
	}

	@Test
	void acceptsEveryContractTagAndRejectsOthers() {
		List<String> all = Arrays.stream(MatchingTag.values()).map(Enum::name).toList();
		assertDoesNotThrow(() -> request(all, "norm-v1+dict-v2"));

		assertThrows(InvalidContractException.class, () -> request(List.of("ATTACK_TERM", "NOT_A_TAG"), "v"));
		assertThrows(InvalidContractException.class, () -> request(List.of("attack_term"), "v"));
	}

	@Test
	void tagsAndRelatedMessageIdsAreImmutableCopies() {
		List<String> tags = new ArrayList<>(List.of("REPEATED_TEXT"));
		CandidateRequest request = request(tags, "v");
		tags.add("ATTACK_TERM");

		assertEquals(List.of("REPEATED_TEXT"), request.matchingTags());
		assertThrows(UnsupportedOperationException.class, () -> request.matchingTags().add("ATTACK_TERM"));
	}

}
