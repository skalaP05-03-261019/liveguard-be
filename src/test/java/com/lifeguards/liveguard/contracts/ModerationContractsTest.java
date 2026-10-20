package com.lifeguards.liveguard.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.lifeguards.liveguard.contracts.dto.request.ActionCommand;
import com.lifeguards.liveguard.contracts.dto.request.ChatContextQuery;
import com.lifeguards.liveguard.contracts.dto.request.HistoryQuery;
import com.lifeguards.liveguard.contracts.dto.request.MessageFinding;
import com.lifeguards.liveguard.contracts.enums.ActionType;
import com.lifeguards.liveguard.contracts.enums.Verdict;

class ModerationContractsTest {

	@Test
	void timeoutCommandRequiresPositiveServerCalculatedDuration() {
		assertThrows(IllegalArgumentException.class, () -> actionCommand(ActionType.TIMEOUT, null, null, null));
		assertThrows(IllegalArgumentException.class, () -> actionCommand(ActionType.TIMEOUT, 0, null, null));
		assertThrows(IllegalArgumentException.class,
				() -> actionCommand(ActionType.TIMEOUT, 604_801, null, null));

		ActionCommand command = actionCommand(ActionType.TIMEOUT, 600, null, null);

		assertEquals(600, command.durationSeconds());
	}

	@Test
	void reconcileCommandRequiresReviewDecisionAndOriginalAction() {
		assertThrows(NullPointerException.class,
				() -> actionCommand(ActionType.RECONCILE_REVIEW, null, null, null));

		ActionCommand command = actionCommand(
				ActionType.RECONCILE_REVIEW, null, UUID.randomUUID(), UUID.randomUUID());

		assertEquals(ActionType.RECONCILE_REVIEW, command.type());
	}

	@Test
	void contextQueryAppliesContractDefaultsAndMaximum() {
		ChatContextQuery defaults = new ChatContextQuery(UUID.randomUUID(), null, null);
		assertEquals(10, defaults.before());
		assertEquals(10, defaults.after());

		assertThrows(IllegalArgumentException.class,
				() -> new ChatContextQuery(UUID.randomUUID(), 11, 0));
	}

	@Test
	void historyQueryDefaultsToThirtyDaysAndRejectsLargerRange() {
		assertEquals(30, new HistoryQuery(UUID.randomUUID(), null).days());
		assertThrows(IllegalArgumentException.class,
				() -> new HistoryQuery(UUID.randomUUID(), 31));
	}

	@Test
	void normalFindingRequiresAnApplicableExceptionPolicy() {
		assertThrows(IllegalArgumentException.class, () -> new MessageFinding(
				UUID.randomUUID(),
				Verdict.NO_VIOLATION,
				java.util.List.of(),
				java.util.List.of(),
				java.util.List.of(),
				"정상적인 인용",
				java.util.List.of()));
	}

	private ActionCommand actionCommand(
			ActionType type,
			Integer durationSeconds,
			UUID decisionId,
			UUID originalActionId) {
		return new ActionCommand(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				type,
				UUID.randomUUID(),
				1,
				0,
				"0.5-draft",
				"action:" + UUID.randomUUID(),
				durationSeconds,
				decisionId,
				originalActionId);
	}
}
