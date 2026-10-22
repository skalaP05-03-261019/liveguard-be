package com.lifeguards.liveguard.internal;

import java.util.UUID;

import com.lifeguards.liveguard.common.TraceIdFilter;
import com.lifeguards.liveguard.guard.CaseQueryService;
import com.lifeguards.liveguard.contracts.dto.response.ApiResponse;
import com.lifeguards.liveguard.contracts.dto.response.InternalCase;
import com.lifeguards.liveguard.contracts.dto.response.KickEligibility;
import com.lifeguards.liveguard.contracts.dto.response.KickReviewBasis;
import com.lifeguards.liveguard.contracts.dto.response.ActionExecutionResult;
import com.lifeguards.liveguard.contracts.dto.response.UserHistory;
import com.lifeguards.liveguard.contracts.dto.response.SecurityFacts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
@Tag(name = "Business MCP Backend", description = "Agent read APIs and pending business action contracts.")
@SecurityRequirement(name = "serviceToken")
public class BusinessToolController {

	private final CaseQueryService queries;
	private final com.lifeguards.liveguard.guard.LiveModerationService live;
	private final tools.jackson.databind.json.JsonMapper mapper;

	public BusinessToolController(CaseQueryService queries, com.lifeguards.liveguard.guard.LiveModerationService live, tools.jackson.databind.json.JsonMapper mapper) {
		this.queries = queries;
		this.live = live; this.mapper=mapper;
	}

	@GetMapping("/cases/{caseId}")
	@Operation(summary = "get_moderation_case: get the Backend case snapshot",
			description = "Persistence-backed current evidence and target versions. Missing case is 404; incomplete metadata/evidence is 409 CASE_NOT_READY.")
	public ApiResponse<InternalCase> getModerationCase(
			@PathVariable UUID caseId) {
		return new ApiResponse<>(queries.moderationCase(caseId), TraceIdFilter.current());
	}

	@GetMapping("/cases/{caseId}/security-facts")
	@Operation(summary = "get_security_facts: get verified security facts with source references",
			description = "Contract only. TRUE/FALSE require verified sources; unresolved facts are UNKNOWN. Channel metadata is not proof of PII.")
	public ApiResponse<SecurityFacts> getSecurityFacts(
			@PathVariable UUID caseId) {
		if(live.owns(caseId))return new ApiResponse<>(new SecurityFacts(java.util.List.of(
			new SecurityFacts.Fact("isPersonalData",SecurityFacts.Value.UNKNOWN,java.util.List.of()),
			new SecurityFacts.Fact("isOfficialContact",SecurityFacts.Value.UNKNOWN,java.util.List.of()))),TraceIdFilter.current());
		throw new BusinessToolNotImplementedException();
	}

	@GetMapping("/cases/{caseId}/user-history")
	@Operation(summary = "get_user_history: get confirmed user history",
			description = """
					TIMEOUT history by category, excluding the current case.
					Consumed kick-review cases remain in this history. This is not kick eligibility.
					History covers the whole broadcast, based on confirmedAt, not message occurrence.
					days is unsupported and is explicitly rejected.
					violationCount counts distinct cases; recentCases contains case/category rows.
					""")
	public ApiResponse<UserHistory> getUserHistory(
			@PathVariable UUID caseId,
			@RequestParam(required = false) Integer days) {
		return new ApiResponse<>(queries.userHistory(caseId, days), TraceIdFilter.current());
	}

	@GetMapping("/cases/{caseId}/kick-eligibility")
	@Operation(summary = "get_kick_eligibility: get distinct confirmed cases for kick review",
			description = """
					Fixed 24-hour window, including the current confirmed case.
					Resolve user/channel/live from the case on the Backend; combine all five categories
					and count each caseId once. Exclude revoked and already consumed cases, not cases
					whose TIMEOUT has merely expired. Three or more cases qualify for an operator review,
					not automatic execution. Reserve basis cases while pending; record consumption on
					approval or rejection. Rejection preserves existing hiding. Only approval permits
					KICK after revalidation, blocking re-entry/chat until the current live ends.
					""")
	public ApiResponse<KickEligibility> getKickEligibility(
			@PathVariable UUID caseId,
			@RequestParam(required = false) Integer days) {
		if (days != null) {
			throw new IllegalArgumentException("Kick eligibility has a fixed 24-hour window; days is not supported");
		}
		return new ApiResponse<>(queries.kickEligibility(caseId),TraceIdFilter.current());
	}

	@GetMapping("/kick-reviews/{reviewId}/basis")
	@Operation(summary = "get_kick_review_basis: read the original approved review basis",
			description = """
					Read the fixed decided basis and consumption ownership, not a new eligibility count.
					Own review/decision consumption is allowed during revalidation; another review's is not.
					This read does not approve a review or issue a validationRef.
					""")
	public ApiResponse<KickReviewBasis> getKickReviewBasis(
			@PathVariable UUID reviewId) {
		return new ApiResponse<>(live.kickReviewBasis(reviewId),TraceIdFilter.current());
	}

	@GetMapping("/actions/{actionId}")
	@Operation(summary = "get_action_status: read an existing action without executing it")
	public ApiResponse<ActionExecutionResult> getActionStatus(
			@PathVariable UUID actionId) {
		if(live.ownsAction(actionId)) return new ApiResponse<>(mapper.convertValue(live.actionStatus(actionId),ActionExecutionResult.class),TraceIdFilter.current());
		throw new BusinessToolNotImplementedException();
	}

	@PostMapping("/actions/{actionId}/execute")
	@Operation(summary = "Execute a Backend-issued action command",
			description = """
					Used by hide_message (HIDE), timeout_user (TIMEOUT),
					release_message (RELEASE_HELD), and kick_user (KICK).
					The path actionId must match the command. idempotencyKey is in the JSON body.
					Future implementation must revalidate versions, policy, permissions and idempotency.
					""")
	public ApiResponse<ActionExecutionResult> executeAction(
			@PathVariable UUID actionId,
			@RequestBody BusinessActionCommand command) {
		if (!actionId.equals(command.actionId())) {
			throw new IllegalArgumentException("Path actionId must match command actionId");
		}
		if(live.ownsAction(actionId)) {
			java.util.Map<String,Object> payload=mapper.convertValue(command,java.util.Map.class);
			payload.values().removeIf(java.util.Objects::isNull);
			return new ApiResponse<>(mapper.convertValue(live.execute(actionId,payload),ActionExecutionResult.class),TraceIdFilter.current());
		}
		throw new BusinessToolNotImplementedException();
	}

	@PostMapping("/cases/{caseId}/reviews/{decisionId}/reconcile")
	@Operation(summary = "Legacy reconcile_review contract", deprecated = true,
			description = """
					Contract only; not the CONTEXT/KICK decision or resume API.
					Does not approve KICK, unconsume cases, or automatically undo HIDE/TIMEOUT.
					The stored review decision must be checked before any future reconciliation.
					""")
	public ApiResponse<ActionExecutionResult> reconcileReview(
			@PathVariable UUID caseId,
			@PathVariable UUID decisionId,
			@RequestBody ReconcileReviewRequest request) {
		if (!caseId.equals(request.caseId()) || !decisionId.equals(request.decisionId())) {
			throw new IllegalArgumentException("Body caseId and decisionId must match the path");
		}
		throw new BusinessToolNotImplementedException();
	}
}
