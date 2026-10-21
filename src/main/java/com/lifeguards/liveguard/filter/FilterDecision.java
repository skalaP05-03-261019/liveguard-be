package com.lifeguards.liveguard.filter;

import java.util.List;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.HandlingRoute;
import com.lifeguards.liveguard.contracts.enums.ModerationReasonCode;
import com.lifeguards.liveguard.contracts.enums.Visibility;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/**
 * 처리 경로 하나와 매칭된 태그 전부 (9번에서 InternalCase.matchingTags로 전달). {@link #result()}와
 * {@link #visibility()}는 경로에서 정해진다(TBD-02).
 *
 * @param ruleId 규칙으로 확정한 근거. RULE_CONFIRMED일 때만 있고 그 외에는 null이다
 */
public record FilterDecision(HandlingRoute handlingRoute, String ruleId, List<String> matchingTags) {

	public FilterDecision {
		DtoValidation.requireNonNull(handlingRoute, "handlingRoute");
		if ((handlingRoute == HandlingRoute.RULE_CONFIRMED) != (ruleId != null)) {
			throw new InvalidContractException("ruleId must be set exactly when handlingRoute is RULE_CONFIRMED");
		}
		matchingTags = List.copyOf(matchingTags);
	}

	/** v3.1은 REVIEW를 쓰지 않는다. 규칙·문맥·개인정보 경로는 HOLD, 도배는 FLOOD다. */
	public FilterResult result() {
		return switch (handlingRoute) {
			case PASS -> FilterResult.PASS;
			case RULE_CONFIRMED, CONTEXT_REVIEW, PII_INVESTIGATION -> FilterResult.HOLD;
			case FLOOD_REVIEW -> FilterResult.FLOOD;
		};
	}

	/** 규칙 확정은 HIDDEN, 조사·도배 후보는 HELD, 그 외는 PUBLIC. HELD·HIDDEN은 작성자·담당 운영자에게만 보인다. */
	public Visibility visibility() {
		return switch (handlingRoute) {
			case PASS -> Visibility.PUBLIC;
			case RULE_CONFIRMED -> Visibility.HIDDEN;
			case CONTEXT_REVIEW, PII_INVESTIGATION, FLOOD_REVIEW -> Visibility.HELD;
		};
	}

	/** 작성자에게 보여 줄 사유(A-3). PASS는 null이다. 규칙 확정은 어떤 규칙인지로 욕설과 링크를 가른다. */
	public ModerationReasonCode moderationReasonCode() {
		return switch (handlingRoute) {
			case PASS -> null;
			case RULE_CONFIRMED -> switch (ruleId) {
				case MessageFilter.RULE_CERTAIN_PROFANITY -> ModerationReasonCode.PROFANITY_INCLUDED;
				case MessageFilter.RULE_LINK_BLOCKED -> ModerationReasonCode.LINK_NOT_ALLOWED;
				default -> throw new IllegalStateException("Unknown ruleId: " + ruleId);
			};
			case FLOOD_REVIEW -> ModerationReasonCode.FLOOD_REPEATED;
			case CONTEXT_REVIEW -> ModerationReasonCode.CONTEXT_PENDING;
			case PII_INVESTIGATION -> ModerationReasonCode.PII_PENDING;
		};
	}

}
