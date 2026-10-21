package com.lifeguards.liveguard.internal;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.common.TraceIdFilter;
import com.lifeguards.liveguard.contracts.dto.event.TaskFailedEvent;
import com.lifeguards.liveguard.contracts.dto.event.ToolEvent;
import com.lifeguards.liveguard.contracts.dto.request.AgentEventRequest;
import com.lifeguards.liveguard.contracts.dto.response.ApiResponse;
import com.lifeguards.liveguard.contracts.dto.response.ChannelInfo;
import com.lifeguards.liveguard.contracts.dto.response.ContextData;
import com.lifeguards.liveguard.contracts.dto.response.ChannelUserRole;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import com.lifeguards.liveguard.platform.CaseContextQuery;
import com.lifeguards.liveguard.platform.port.GuardEventPublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;

/** B1 담당 내부 API (Agent·business-mcp 호출). B2 담당 경로(사건 조회, 사용자 이력, 판정, 강퇴 실행)는 B2가 만든다. */
@RestController
@RequestMapping("/internal/cases/{caseId}")
@SecurityRequirement(name = "serviceToken")
@SecurityScheme(name = "serviceToken", type = SecuritySchemeType.APIKEY,
		in = SecuritySchemeIn.HEADER, paramName = "X-Service-Token")
class InternalCaseController {

	private final CaseContextQuery cases;
	private final GuardEventPublisher guardEvents;
	private final com.lifeguards.liveguard.guard.LiveModerationService live;

	InternalCaseController(CaseContextQuery cases, GuardEventPublisher guardEvents, com.lifeguards.liveguard.guard.LiveModerationService live) {
		this.cases = cases;
		this.guardEvents = guardEvents;
		this.live=live;
	}

	@GetMapping("/context")
	@Operation(summary = "get_chat_context: get masked case messages and surrounding context",
			description = "Real BE1 query, at most 20 messages; before/after each 0..10. Legacy view=chatContext is accepted.")
	ApiResponse<ContextData> context(@PathVariable UUID caseId,
			@RequestParam(defaultValue = "10") int before, @RequestParam(defaultValue = "10") int after,
			@RequestParam(required = false) String view) {
		if (view != null && !view.equals("chatContext")) {
			throw new InvalidContractException("Use the dedicated case or channel-info endpoint instead of view");
		}
		if(before<0||before>10||after<0||after>10) throw new InvalidContractException("Invalid context range");
		return new ApiResponse<>(live.internalContext(caseId,before,after), TraceIdFilter.current());
	}

	@GetMapping("/channel-info")
	@Operation(summary = "Get current channel role, official hosts and registered events",
			description = "Shared source for get_stream_info/get_official_links; not live lifecycle or verified PII facts. Official links are not exempt from link prohibition.")
	ApiResponse<ChannelInfo> channelInfo(@PathVariable UUID caseId) {
		return new ApiResponse<>(cases.channelInfo(caseId), TraceIdFilter.current());
	}

	@GetMapping("/role")
	@Operation(summary = "get_channel_user_role: get the current case author's channel role",
			description = "Reuses BE1 channel info. A former member is returned as VIEWER by the current BE1 query; this is not a historical or verified identity claim.")
	ApiResponse<ChannelUserRole> role(@PathVariable UUID caseId) {
		ChannelInfo info = cases.channelInfo(caseId);
		return new ApiResponse<>(new ChannelUserRole(info.authorRole(), info.isOfficialOperator()),
				TraceIdFilter.current());
	}

	/** Agent 진행 이벤트를 관제 SSE로 넘긴다. 계약 필드만 옮기고 나머지 payload(도구 인자 등)는 버린다. */
	@PostMapping("/events")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void event(@PathVariable UUID caseId, @RequestBody AgentEventRequest request) {
		Object data = switch (request.event()) {
			case TOOL_START, TOOL_END -> new ToolEvent(caseId, requiredText(request.payload(), "tool"),
					requiredText(request.payload(), "callId"), optionalText(request.payload(), "status"),
					optionalInteger(request.payload(), "elapsedMs"));
			case TASK_FAILED -> new TaskFailedEvent(caseId, requiredText(request.payload(), "stage"),
					requiredText(request.payload(), "errorCode"), optionalText(request.payload(), "traceUrl"));
		};
		if(live.owns(caseId)){live.progress(caseId,request.event().name(),data);return;}
		UUID channelId = cases.channelOf(caseId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Case has no messages"));
		guardEvents.publish(channelId, request.event().name(), data);
	}

	private static String requiredText(Map<String, Object> payload, String name) {
		String value = optionalText(payload, name);
		if (value == null || value.isBlank()) {
			throw new InvalidContractException("payload." + name + " is required");
		}
		return value;
	}

	private static String optionalText(Map<String, Object> payload, String name) {
		Object value = payload.get(name);
		if (value == null) {
			return null;
		}
		if (!(value instanceof String text)) {
			throw new InvalidContractException("payload." + name + " must be a string");
		}
		return text;
	}

	private static Integer optionalInteger(Map<String, Object> payload, String name) {
		Object value = payload.get(name);
		if (value == null) {
			return null;
		}
		if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0
				|| ((Number) value).longValue() > Integer.MAX_VALUE) {
			throw new InvalidContractException("payload." + name + " must be a non-negative integer");
		}
		return ((Number) value).intValue();
	}

}
