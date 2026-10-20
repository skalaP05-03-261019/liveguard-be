package com.lifeguards.liveguard.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.response.KickEligibility;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KickEligibilityTest {
	private static final UUID ID = UUID.randomUUID();

	@Test
	void previousTwoAndCurrentCaseAreThreeDistinctCases() {
		List<UUID> cases = List.of(UUID.randomUUID(), UUID.randomUUID(), ID);
		KickEligibility result = eligibility(24, 3, cases);
		assertEquals(3, result.violationCount());
		assertEquals(cases, result.eligibleCaseIds());
		assertThrows(UnsupportedOperationException.class, () -> result.eligibleCaseIds().add(ID));
	}

	@Test
	void duplicateCasesCannotInflateCount() {
		assertThrows(InvalidContractException.class, () -> eligibility(24, 3, List.of(ID, ID, ID)));
	}

	@Test
	void windowAndCountMustMatchContract() {
		assertThrows(InvalidContractException.class, () -> eligibility(720, 0, List.of()));
		assertThrows(InvalidContractException.class, () -> eligibility(24, 3, List.of(ID)));
		assertEquals(0, eligibility(24, 0, List.of()).violationCount());
	}

	private KickEligibility eligibility(int hours, int count, List<UUID> cases) {
		return new KickEligibility(ID, ID, ID, Instant.now(), hours, count, cases);
	}
}
