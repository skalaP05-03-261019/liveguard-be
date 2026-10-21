package com.lifeguards.liveguard.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.lifeguards.liveguard.contracts.dto.event.ReviewDecidedEvent;
import com.lifeguards.liveguard.contracts.dto.request.ContextDecisionRequest;
import com.lifeguards.liveguard.contracts.dto.response.ContextReview;
import com.lifeguards.liveguard.contracts.dto.response.Decision;
import com.lifeguards.liveguard.contracts.dto.response.KickRevalidation;
import com.lifeguards.liveguard.contracts.dto.response.KickReview;
import com.lifeguards.liveguard.contracts.dto.response.Live;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.ContextDecision;
import com.lifeguards.liveguard.contracts.enums.ReviewKind;
import com.lifeguards.liveguard.contracts.enums.ReviewStatus;
import com.lifeguards.liveguard.contracts.enums.Verdict;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

class ReviewContractsTest {

	private static final UUID ID = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");

	@Test
	void contextViolationDecisionRequiresCategoriesAndEvidenceOthersMustNotSendThem() {
		assertDoesNotThrow(() -> contextDecision(ContextDecision.CONFIRM_VIOLATION, List.of(Category.FLOOD), List.of(ID)));
		assertThrows(InvalidContractException.class,
				() -> contextDecision(ContextDecision.CONFIRM_VIOLATION, null, List.of(ID)));
		assertThrows(InvalidContractException.class,
				() -> contextDecision(ContextDecision.CONFIRM_VIOLATION, List.of(Category.FLOOD), List.of()));
		assertDoesNotThrow(() -> contextDecision(ContextDecision.DEFER, null, null));
		assertDoesNotThrow(() -> contextDecision(ContextDecision.CONFIRM_NORMAL, null, null));
		assertThrows(InvalidContractException.class,
				() -> contextDecision(ContextDecision.CONFIRM_NORMAL, List.of(Category.FLOOD), null));
		assertThrows(InvalidContractException.class,
				() -> contextDecision(ContextDecision.DEFER, null, List.of()));
	}

	@Test
	void decisionValueMustMatchItsReviewKind() {
		assertDoesNotThrow(() -> new Decision(ID, ID, ReviewKind.CONTEXT, "DEFER", ID, NOW));
		assertDoesNotThrow(() -> new Decision(ID, ID, ReviewKind.KICK, "APPROVE", ID, NOW));
		assertThrows(InvalidContractException.class, () -> new Decision(ID, ID, ReviewKind.KICK, "DEFER", ID, NOW));
		assertThrows(InvalidContractException.class, () -> new Decision(ID, ID, ReviewKind.CONTEXT, "APPROVE", ID, NOW));
		assertThrows(InvalidContractException.class, () -> new ReviewDecidedEvent(ID, ReviewKind.KICK, ID, "CONFIRM_NORMAL"));
	}

	@Test
	void reviewRecordsCarryTheirOwnKind() {
		assertEquals(ReviewKind.CONTEXT, new ContextReview(
				ID, ReviewKind.CONTEXT, ID, ID, Verdict.NO_VIOLATION, 1, ReviewStatus.PENDING, 1, NOW).kind());
		assertThrows(InvalidContractException.class, () -> new ContextReview(
				ID, ReviewKind.KICK, ID, ID, Verdict.NO_VIOLATION, 1, ReviewStatus.PENDING, 1, NOW));
		List<UUID> basis = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		assertDoesNotThrow(() -> new KickReview(
				ID, ReviewKind.KICK, ID, "U1", basis, ID, ReviewStatus.PENDING, "요약", null, 1, NOW));
		assertThrows(InvalidContractException.class, () -> new KickReview(
				ID, ReviewKind.KICK, ID, "U1", basis.subList(0, 2), ID, ReviewStatus.PENDING, "요약", null, 1, NOW));
	}

	@Test
	void revalidationIssuesValidationRefOnlyWhenValid() {
		assertDoesNotThrow(() -> new KickRevalidation(ID, ID, true, UUID.randomUUID(), List.of()));
		assertDoesNotThrow(() -> new KickRevalidation(ID, ID, false, null, List.of()));
		assertThrows(InvalidContractException.class, () -> new KickRevalidation(ID, ID, true, null, List.of()));
		assertThrows(InvalidContractException.class, () -> new KickRevalidation(ID, ID, false, ID, List.of()));
	}

	@Test
	void liveEndsAfterItStarts() {
		assertDoesNotThrow(() -> new Live(ID, ID, NOW, null));
		assertThrows(InvalidContractException.class, () -> new Live(ID, ID, NOW, NOW.minusSeconds(1)));
	}

	private static ContextDecisionRequest contextDecision(
			ContextDecision decision, List<Category> categories, List<UUID> evidenceMessageIds) {
		return new ContextDecisionRequest(ID, 1, decision, "근거", categories, evidenceMessageIds);
	}
}
