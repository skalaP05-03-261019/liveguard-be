package com.lifeguards.liveguard.platform;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.enums.AuthorRole;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;

@RestController
class ChannelEventController {

	private final JdbcChannelMembershipQuery memberships;
	private final ChannelEventHub hub;

	ChannelEventController(JdbcChannelMembershipQuery memberships, ChannelEventHub hub) {
		this.memberships = memberships;
		this.hub = hub;
	}

	@GetMapping("/api/channels/{channelId}/events")
	ResponseEntity<SseEmitter> subscribe(@PathVariable UUID channelId, @AuthenticationPrincipal AuthenticatedUser user,
			HttpServletRequest request) {
		AuthorRole role = memberships.memberRole(channelId, user.userId())
				.orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "Not a member of this channel"));
		SseEmitter emitter = hub.subscribe(channelId, user.userId(), role == AuthorRole.MODERATOR,
				request.getSession().getId());
		return ResponseEntity.ok()
				.header("Cache-Control", "no-cache")
				.header("X-Accel-Buffering", "no")
				.body(emitter);
	}

}
