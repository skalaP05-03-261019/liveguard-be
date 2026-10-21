package com.lifeguards.liveguard.platform;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lifeguards.liveguard.common.TraceIdFilter;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.request.SendMessageRequest;
import com.lifeguards.liveguard.contracts.dto.response.ApiResponse;
import com.lifeguards.liveguard.contracts.dto.response.Message;
import com.lifeguards.liveguard.contracts.dto.response.MessagePage;

@RestController
class MessageController {

	private final MessageService messageService;

	MessageController(MessageService messageService) {
		this.messageService = messageService;
	}

	@PostMapping("/api/lives/{liveId}/messages")
	ResponseEntity<ApiResponse<Message>> sendToLive(@PathVariable UUID liveId, @RequestBody SendMessageRequest request,
			@AuthenticationPrincipal AuthenticatedUser user) {
		return created(messageService.sendToLive(liveId, user, request));
	}

	/** 최신순. nextCursor로 더 과거 페이지를 가져온다 (마지막 페이지면 null). */
	@GetMapping("/api/lives/{liveId}/messages")
	ApiResponse<MessagePage> historyOfLive(@PathVariable UUID liveId, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "50") int limit, @AuthenticationPrincipal AuthenticatedUser user) {
		return new ApiResponse<>(messageService.historyOfLive(liveId, user, cursor, limit), TraceIdFilter.current());
	}

	/** 기존 FE 호환 경로(A-18). 채널의 진행 중 방송으로 귀속한다. */
	@Deprecated
	@PostMapping("/api/channels/{channelId}/messages")
	ResponseEntity<ApiResponse<Message>> sendToChannel(@PathVariable UUID channelId,
			@RequestBody SendMessageRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
		return created(messageService.sendToChannel(channelId, user, request));
	}

	/** 기존 FE 호환 경로(A-18). 채널 전체 이력. */
	@Deprecated
	@GetMapping("/api/channels/{channelId}/messages")
	ApiResponse<MessagePage> historyOfChannel(@PathVariable UUID channelId,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit,
			@AuthenticationPrincipal AuthenticatedUser user) {
		return new ApiResponse<>(messageService.historyOfChannel(channelId, user, cursor, limit),
				TraceIdFilter.current());
	}

	private static ResponseEntity<ApiResponse<Message>> created(Message message) {
		return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(message, TraceIdFilter.current()));
	}

}
