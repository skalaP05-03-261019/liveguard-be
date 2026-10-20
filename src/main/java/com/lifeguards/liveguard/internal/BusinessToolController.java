package com.lifeguards.liveguard.internal;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.request.ChatContextQuery;
import com.lifeguards.liveguard.contracts.dto.request.HistoryQuery;
import com.lifeguards.liveguard.contracts.dto.response.ActionResult;
import com.lifeguards.liveguard.contracts.dto.response.ApiResponse;
import com.lifeguards.liveguard.contracts.dto.response.ChatContextResponse;
import com.lifeguards.liveguard.contracts.dto.response.ModerationCaseResponse;
import com.lifeguards.liveguard.contracts.dto.response.OfficialLinksResponse;
import com.lifeguards.liveguard.contracts.dto.response.SecurityFactsResponse;
import com.lifeguards.liveguard.contracts.dto.response.StreamInfoResponse;
import com.lifeguards.liveguard.contracts.dto.response.UserHistoryResponse;
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
							ModerationCaseResponse.class, ChatContextResponse.class, StreamInfoResponse.class,
							OfficialLinksResponse.class, SecurityFactsResponse.class})),
					@SchemaProperty(name = "traceId", schema = @Schema(type = "string", format = "uuid"))
			}))
	@Operation(summary = "Get case context for a business tool",
			description = """
					view selects the data schema inside ApiResponse:
					moderationCase -> ModerationCaseResponse,
					chatContext -> ChatContextResponse,
					streamInfo -> StreamInfoResponse,
					officialLinks -> OfficialLinksResponse,
					securityFacts -> SecurityFactsResponse.
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
			case "chatContext" -> new ChatContextQuery(caseId, before, after);
			default -> throw new IllegalArgumentException("Unsupported context view: " + view);
		}
		throw new BusinessToolNotImplementedException(traceId);
	}

	@GetMapping("/cases/{caseId}/history")
	@Operation(summary = "get_user_history: get confirmed user history",
			description = "days defaults to 30 and must be 1..30 under Backend policy.")
	public ApiResponse<UserHistoryResponse> getUserHistory(
			@PathVariable UUID caseId,
			@RequestParam(defaultValue = "30") Integer days,
			@RequestHeader(value = "X-Trace-Id", required = false) UUID traceId) {
		new HistoryQuery(caseId, days);
		throw new BusinessToolNotImplementedException(traceId);
	}

	@GetMapping("/actions/{actionId}")
	@Operation(summary = "get_action_status: read an existing action without executing it")
	public ApiResponse<ActionResult> getActionStatus(
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
	public ApiResponse<ActionResult> executeAction(
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
	public ApiResponse<ActionResult> reconcileReview(
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
