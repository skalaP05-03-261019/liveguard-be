package com.lifeguards.liveguard.platform;

import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.common.TraceIdFilter;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.request.SendMessageRequest;
import com.lifeguards.liveguard.contracts.dto.response.ApiResponse;
import com.lifeguards.liveguard.contracts.dto.response.Message;
import com.lifeguards.liveguard.contracts.dto.response.MessagePage;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MessageController {

	@Value("${LIVEGUARD_TBD_FIXTURE_ENABLED:false}")
	private boolean liveIntegrationEnabled;

	private final MessageService messageService;

	MessageController(MessageService messageService) {
		this.messageService = messageService;
	}

	@PostMapping("/api/channels/{channelId}/messages")
	ResponseEntity<ApiResponse<Message>> sendToChannel(@PathVariable UUID channelId,
			@RequestBody SendMessageRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
		requireLegacyMode();
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(new ApiResponse<>(messageService.sendToChannel(channelId, user, request), TraceIdFilter.current()));
	}

	/** 최신순. nextCursor로 더 과거 페이지를 가져온다 (마지막 페이지면 null). */
	@GetMapping("/api/channels/{channelId}/messages")
	ApiResponse<MessagePage> historyOfChannel(@PathVariable UUID channelId,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit,
			@AuthenticationPrincipal AuthenticatedUser user) {
		requireLegacyMode();
		return new ApiResponse<>(messageService.historyOfChannel(channelId, user, cursor, limit),
				TraceIdFilter.current());
	}

	private void requireLegacyMode() {
		if (liveIntegrationEnabled) {
			throw new ApiException(ErrorCode.CASE_NOT_READY, "Use the liveId API in integration mode");
		}
	}
}
