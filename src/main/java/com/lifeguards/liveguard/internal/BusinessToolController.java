package com.lifeguards.liveguard.internal;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.response.ApiResponse;
import com.lifeguards.liveguard.contracts.dto.response.ChannelInfo;
import com.lifeguards.liveguard.contracts.dto.response.ContextData;
import com.lifeguards.liveguard.contracts.dto.response.InternalCase;
import com.lifeguards.liveguard.contracts.dto.response.KickEligibility;
import com.lifeguards.liveguard.contracts.dto.response.UserHistory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
@Tag(name = "Business MCP Backend", description = "Agent HTTP adapter contracts; business operations are not implemented.")
public class BusinessToolController {

	@GetMapping("/cases/{caseId}/context")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
			description = "Future implementation: data depends on view",
			content = @Content(schemaProperties = {
					@SchemaProperty(name = "data", schema = @Schema(oneOf = {
							InternalCase.class, ContextData.class, ChannelInfo.class})),
					@SchemaProperty(name = "traceId", schema = @Schema(type = "string", format = "uuid"))
			}))
	@Operation(summary = "Get case context for a business tool",
			description = """
					view selects the data schema inside ApiResponse:
					moderationCase -> InternalCase,
					chatContext -> ContextData,
					streamInfo, officialLinks, securityFacts -> ChannelInfo.
					Only chatContext uses before/after (default 5 each).
					Backend policy limits each to 0..10, 20 total, even though Agent accepts up to 50.
					""")
	public ApiResponse<?> getCaseContext(
			@PathVariable UUID caseId,
			@Parameter(schema = @Schema(allowableValues = {
					"moderationCase", "chatContext", "streamInfo", "officialLinks", "securityFacts"}))
			@RequestParam String view,
			@RequestParam(defaultValue = "5") Integer before,
			@RequestParam(defaultValue = "5") Integer after,
			@RequestHeader(value = "X-Trace-Id", required = false) UUID traceId) {
		switch (view) {
			case "moderationCase", "streamInfo", "officialLinks", "securityFacts" -> { }
			case "chatContext" -> {
				if (before == null || before < 0 || before > 10
						|| after == null || after < 0 || after > 10 || before + after > 20) {
					throw new IllegalArgumentException("before and after must each be 0..10 and total no more than 20");
				}
			}
			default -> throw new IllegalArgumentException("Unsupported context view: " + view);
		}
		throw new BusinessToolNotImplementedException(traceId);
	}

	@GetMapping("/cases/{caseId}/history")
	@Operation(summary = "get_user_history: get confirmed user history",
			description = """
					TIMEOUT history by category, excluding the current case.
					Consumed kick-review cases remain in this history. This is not kick eligibility.
					days is an optional positive requested range, not an approved policy window.
					The default TIMEOUT lookback remains undecided; no 30-day default is applied.
					windowHours describes the actual resolved query range.
					""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
			description = "Future implementation: confirmed user history",
			content = @Content(schemaProperties = {
					@SchemaProperty(name = "data", schema = @Schema(implementation = UserHistory.class)),
					@SchemaProperty(name = "traceId", schema = @Schema(type = "string", format = "uuid"))
			}))
	public ApiResponse<UserHistory> getUserHistory(
			@PathVariable UUID caseId,
			@RequestParam(required = false) Integer days,
			@RequestHeader(value = "X-Trace-Id", required = false) UUID traceId) {
		if (days != null && days < 1) {
			throw new IllegalArgumentException("days must be positive when provided");
		}
		throw new BusinessToolNotImplementedException(traceId);
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
			@RequestParam(required = false) Integer days,
			@RequestHeader(value = "X-Trace-Id", required = false) UUID traceId) {
		if (days != null) {
			throw new IllegalArgumentException("Kick eligibility has a fixed 24-hour window; days is not supported");
		}
		throw new BusinessToolNotImplementedException(traceId);
	}

	@GetMapping("/actions/{actionId}")
	@Operation(summary = "get_action_status: read an existing action without executing it")
	public ApiResponse<?> getActionStatus(
			@PathVariable UUID actionId,
			@RequestHeader(value = "X-Trace-Id", required = false) UUID traceId) {
		throw new BusinessToolNotImplementedException(traceId);
	}

	@PostMapping("/actions/{actionId}/execute")
	@Operation(summary = "Execute a Backend-issued action command",
			description = """
					Used by hide_message (HIDE), timeout_user (TIMEOUT),
					release_message (RELEASE_HELD), and kick_user (KICK).
					The path actionId must match the command. idempotencyKey is in the JSON body.
					Future implementation must revalidate versions, policy, permissions and idempotency.
					""")
	public ApiResponse<?> executeAction(
			@PathVariable UUID actionId,
			@RequestBody BusinessActionCommand command,
			@RequestHeader(value = "X-Trace-Id", required = false) UUID traceId) {
		if (!actionId.equals(command.actionId())) {
			throw new IllegalArgumentException("Path actionId must match command actionId");
		}
		throw new BusinessToolNotImplementedException(traceId);
	}

	@PostMapping("/cases/{caseId}/reviews/{decisionId}/reconcile")
	@Operation(summary = "reconcile_review: apply a persisted review decision",
			description = "Body identifiers must match the path. No decision or action is created by this stub.")
	public ApiResponse<?> reconcileReview(
			@PathVariable UUID caseId,
			@PathVariable UUID decisionId,
			@RequestBody ReconcileReviewRequest request,
			@RequestHeader(value = "X-Trace-Id", required = false) UUID traceId) {
		if (!caseId.equals(request.caseId()) || !decisionId.equals(request.decisionId())) {
			throw new IllegalArgumentException("Body caseId and decisionId must match the path");
		}
		throw new BusinessToolNotImplementedException(traceId);
	}
}
