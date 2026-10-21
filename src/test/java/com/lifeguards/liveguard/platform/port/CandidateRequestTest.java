package com.lifeguards.liveguard.platform.port;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.HandlingRoute;
import com.lifeguards.liveguard.contracts.enums.MatchingTag;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

class CandidateRequestTest {

	private static CandidateRequest request(List<String> tags, String filterVersion) {
		return request(FilterResult.FLOOD, HandlingRoute.FLOOD_REVIEW, null, tags, filterVersion, "v3.1");
	}

	private static CandidateRequest request(FilterResult result, HandlingRoute route, String ruleId, List<String> tags,
			String filterVersion, String policyVersion) {
		return new CandidateRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				result, route, ruleId, tags, filterVersion, policyVersion, Instant.parse("2026-10-20T12:00:00Z"),
				List.of());
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
	void carriesRouteRuleIdAndPolicyVersionSeparatelyFromFilterVersion() {
		CandidateRequest rule = request(FilterResult.HOLD, HandlingRoute.RULE_CONFIRMED, "RULE_LINK_BLOCKED",
				List.of("LINK"), "norm-v1+dict-v2", "v3.1");

		assertEquals(HandlingRoute.RULE_CONFIRMED, rule.handlingRoute());
		assertEquals("RULE_LINK_BLOCKED", rule.ruleId());
		assertEquals("v3.1", rule.policyVersion());
		assertEquals("norm-v1+dict-v2", rule.filterVersion());
		assertNull(request(List.of("REPEATED_TEXT"), "norm-v1+dict-v2").ruleId());
	}

	@Test
	void ruleIdIsRequiredExactlyForRuleConfirmed() {
		assertThrows(InvalidContractException.class,
				() -> request(FilterResult.HOLD, HandlingRoute.RULE_CONFIRMED, null, List.of("LINK"), "v", "v3.1"));
		assertThrows(InvalidContractException.class,
				() -> request(FilterResult.HOLD, HandlingRoute.RULE_CONFIRMED, " ", List.of("LINK"), "v", "v3.1"));
		assertThrows(InvalidContractException.class, () -> request(FilterResult.HOLD, HandlingRoute.CONTEXT_REVIEW,
				"RULE_LINK_BLOCKED", List.of("ATTACK_TERM"), "v", "v3.1"));
	}

	@Test
	void filterResultMustMatchTheRoute() {
		assertDoesNotThrow(() -> request(FilterResult.HOLD, HandlingRoute.CONTEXT_REVIEW, null,
				List.of("ATTACK_TERM"), "v", "v3.1"));
		assertThrows(InvalidContractException.class, () -> request(FilterResult.REVIEW, HandlingRoute.CONTEXT_REVIEW,
				null, List.of("ATTACK_TERM"), "v", "v3.1"));
		assertThrows(InvalidContractException.class, () -> request(FilterResult.FLOOD, HandlingRoute.RULE_CONFIRMED,
				"RULE_LINK_BLOCKED", List.of("LINK"), "v", "v3.1"));
		assertThrows(InvalidContractException.class, () -> request(FilterResult.HOLD, HandlingRoute.FLOOD_REVIEW,
				null, List.of("REPEATED_TEXT"), "v", "v3.1"));
	}

	@Test
	void rejectsMissingRouteAndBlankPolicyVersion() {
		assertThrows(InvalidContractException.class,
				() -> request(FilterResult.FLOOD, null, null, List.of("REPEATED_TEXT"), "v", "v3.1"));
		assertThrows(InvalidContractException.class, () -> request(FilterResult.FLOOD, HandlingRoute.FLOOD_REVIEW,
				null, List.of("REPEATED_TEXT"), "v", null));
		assertThrows(InvalidContractException.class, () -> request(FilterResult.FLOOD, HandlingRoute.FLOOD_REVIEW,
				null, List.of("REPEATED_TEXT"), "v", " "));
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
