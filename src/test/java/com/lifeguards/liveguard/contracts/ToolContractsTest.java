package com.lifeguards.liveguard.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.response.ActionExecutionResult;
import com.lifeguards.liveguard.contracts.dto.response.ActionExecutionResult.Observation;
import com.lifeguards.liveguard.contracts.dto.response.ActionExecutionResult.Status;
import com.lifeguards.liveguard.contracts.dto.response.KickReviewBasis;
import com.lifeguards.liveguard.contracts.dto.response.InternalCase;
import com.lifeguards.liveguard.contracts.dto.response.MaskedMessage;
import com.lifeguards.liveguard.contracts.dto.response.SecurityFacts;
import com.lifeguards.liveguard.contracts.enums.ActionType;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.HandlingRoute;
import com.lifeguards.liveguard.contracts.enums.Visibility;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import com.lifeguards.liveguard.internal.BusinessActionCommand;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolContractsTest {
	private static final UUID ID = UUID.randomUUID();
	private static final List<UUID> CASES = List.of(ID, UUID.randomUUID(), UUID.randomUUID());

	@Test
	void snapshotTargetsMustBeDistinctEvidenceMessagesWithVersions() {
		var target = new InternalCase.Target(ID, Visibility.HELD, 1);
		assertDoesNotThrow(() -> snapshot(List.of(target)));
		assertThrows(InvalidContractException.class, () -> snapshot(List.of(target, target)));
		assertThrows(InvalidContractException.class,
				() -> snapshot(List.of(new InternalCase.Target(UUID.randomUUID(), Visibility.HELD, 1))));
		assertThrows(InvalidContractException.class, () -> new InternalCase.Target(ID, Visibility.HELD, 0));
	}

	@Test
	void kickRequiresOriginalReviewSnapshotAndRevalidationReference() {
		assertDoesNotThrow(() -> kick(ID, CASES));
		assertThrows(InvalidContractException.class, () -> kick(null, CASES));
		assertThrows(InvalidContractException.class, () -> kick(ID, List.of(ID, ID, ID)));
		assertThrows(InvalidContractException.class, () -> kick(ID, List.of(ID)));
	}

	@Test
	void fixedBasisPreservesConsumptionOwnershipWithoutSubstitutingNewCases() {
		assertDoesNotThrow(() -> new KickReviewBasis(ID, ID, ID, ID, ID, CASES,
				List.of(new KickReviewBasis.Consumption(ID, ID, ID))));
		assertThrows(InvalidContractException.class, () -> new KickReviewBasis(ID, ID, ID, ID, ID, CASES,
				List.of(new KickReviewBasis.Consumption(UUID.randomUUID(), ID, ID))));
		assertThrows(InvalidContractException.class, () -> new KickReviewBasis(ID, ID, ID, ID, ID,
				List.of(ID, ID, ID), List.of()));
	}

	@Test
	void unknownObservationDoesNotClaimSuccessFailureOrApplication() {
		assertDoesNotThrow(() -> result(Status.RUNNING, Observation.UNKNOWN, null, 1, 0, null));
		assertThrows(InvalidContractException.class,
				() -> result(Status.FAILED, Observation.UNKNOWN, null, 1, 0, null));
		assertThrows(InvalidContractException.class,
				() -> result(Status.RUNNING, Observation.UNKNOWN, false, 1, 0, null));
		assertThrows(InvalidContractException.class,
				() -> result(Status.SUCCEEDED, Observation.CONFIRMED, false, 1, 0, null));
	}

	@Test
	void unknownApplicationSerializesAsExplicitNullRatherThanFalse() {
		var json = new JsonMapper().valueToTree(result(Status.RUNNING, Observation.UNKNOWN, null, 1, 0, null));
		assertTrue(json.has("applied"));
		assertTrue(json.get("applied").isNull());
	}

	@Test
	void kickRetryBudgetAndLifetimeAreNotResetOrFixedToTwentyFourHours() {
		assertDoesNotThrow(() -> result(Status.FAILED, Observation.CONFIRMED, false, 3, 2, null));
		assertThrows(InvalidContractException.class,
				() -> result(Status.FAILED, Observation.CONFIRMED, false, 4, 3, null));
		assertThrows(InvalidContractException.class,
				() -> result(Status.FAILED, Observation.CONFIRMED, false, 3, 0, null));
		assertThrows(InvalidContractException.class,
				() -> result(Status.SUCCEEDED, Observation.CONFIRMED, true, 1, 0, Instant.now()));
	}

	@Test
	void verifiedFactsRequireEvidenceRatherThanChannelMetadataFallback() {
		assertDoesNotThrow(() -> new SecurityFacts.Fact("PII", SecurityFacts.Value.UNKNOWN, List.of()));
		assertDoesNotThrow(() -> new SecurityFacts.Fact("PII", SecurityFacts.Value.TRUE, List.of("verified-source")));
		assertThrows(InvalidContractException.class,
				() -> new SecurityFacts.Fact("PII", SecurityFacts.Value.TRUE, List.of()));
		assertThrows(InvalidContractException.class,
				() -> new SecurityFacts.Fact("PII", SecurityFacts.Value.FALSE, List.of(" ")));
	}

	private BusinessActionCommand kick(UUID validationRef, List<UUID> cases) {
		return new BusinessActionCommand(ID, ID, ID, ID, ActionType.KICK, ID, 1, 1,
				"test-policy-only", "test-action", null, ID, ID, ID, validationRef, cases);
	}

	private InternalCase snapshot(List<InternalCase.Target> targets) {
		return new InternalCase(ID, ID, ID, ID, 1, "test-policy-only", HandlingRoute.CONTEXT_REVIEW,
				FilterResult.HOLD, List.of("CONTEXT_CANDIDATE"), 1,
				List.of(new MaskedMessage(ID, "U1", "masked evidence", Instant.now())), targets);
	}

	private ActionExecutionResult result(Status status, Observation observation, Boolean applied,
			int attempts, int retries, Instant expiresAt) {
		return new ActionExecutionResult(ID, ID, ID, ActionType.KICK, status, observation, applied,
				attempts, retries, null, expiresAt, null);
	}
}
