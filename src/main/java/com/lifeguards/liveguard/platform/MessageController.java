package com.lifeguards.liveguard.platform;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lifeguards.liveguard.common.TraceIdFilter;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.request.SendMessageRequest;
import com.lifeguards.liveguard.contracts.dto.response.ApiResponse;
import com.lifeguards.liveguard.contracts.dto.response.Message;
import com.lifeguards.liveguard.contracts.dto.response.MessagePage;

@RestController
@RequestMapping("/api/channels/{channelId}/messages")
class MessageController {

	@org.springframework.beans.factory.annotation.Value("${LIVEGUARD_TBD_FIXTURE_ENABLED:false}")
	private boolean liveIntegrationEnabled;

	private void requireLegacyMode() {
		if (liveIntegrationEnabled) throw new com.lifeguards.liveguard.common.ApiException(
			com.lifeguards.liveguard.contracts.enums.ErrorCode.CASE_NOT_READY, "Use the liveId API in integration mode");
	}

	private final MessageService messageService;

	MessageController(MessageService messageService) {
		this.messageService = messageService;
	}

	@PostMapping
	ResponseEntity<ApiResponse<Message>> send(@PathVariable UUID channelId, @RequestBody SendMessageRequest request,
			@AuthenticationPrincipal AuthenticatedUser user) {
		requireLegacyMode();
		Message message = messageService.send(channelId, user, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(message, TraceIdFilter.current()));
	}

	/** 최신순. nextCursor로 더 과거 페이지를 가져온다 (마지막 페이지면 null). */
	@GetMapping
	ApiResponse<MessagePage> history(@PathVariable UUID channelId, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "50") int limit, @AuthenticationPrincipal AuthenticatedUser user) {
		requireLegacyMode();
		return new ApiResponse<>(messageService.history(channelId, user, cursor, limit), TraceIdFilter.current());
	}

}
