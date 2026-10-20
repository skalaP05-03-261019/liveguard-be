package com.lifeguards.liveguard.platform;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lifeguards.liveguard.common.TraceIdFilter;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.request.SendMessageRequest;
import com.lifeguards.liveguard.contracts.dto.response.ApiResponse;
import com.lifeguards.liveguard.contracts.dto.response.Message;

@RestController
@RequestMapping("/api/channels/{channelId}/messages")
class MessageController {

	private final MessageService messageService;

	MessageController(MessageService messageService) {
		this.messageService = messageService;
	}

	@PostMapping
	ResponseEntity<ApiResponse<Message>> send(@PathVariable UUID channelId, @RequestBody SendMessageRequest request,
			@AuthenticationPrincipal AuthenticatedUser user) {
		Message message = messageService.send(channelId, user, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(message, TraceIdFilter.current()));
	}

}
