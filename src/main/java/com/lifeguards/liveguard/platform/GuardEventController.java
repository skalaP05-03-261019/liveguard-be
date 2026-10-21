package com.lifeguards.liveguard.platform;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.enums.AuthorRole;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;

@RestController
class GuardEventController {

	private final JdbcChannelMembershipQuery memberships;
	private final GuardEventHub hub;
	private final LiveService lives;

	GuardEventController(JdbcChannelMembershipQuery memberships, GuardEventHub hub, LiveService lives) {
		this.memberships = memberships;
		this.hub = hub;
		this.lives=lives;
	}

	/** 그 채널의 담당 운영자(member_role=MODERATOR)만 구독할 수 있다. */
	@GetMapping("/api/guard/events")
	ResponseEntity<SseEmitter> subscribe(@RequestParam(required=false) UUID channelId, @RequestParam(required=false) UUID liveId, @AuthenticationPrincipal AuthenticatedUser user,
			HttpServletRequest request) {
		if(liveId!=null)return ResponseEntity.ok().header("Cache-Control","no-cache").header("X-Accel-Buffering","no")
			.body(lives.subscribe(liveId,user,request.getSession().getId(),true));
		if(channelId==null)throw new ApiException(ErrorCode.VALIDATION_FAILED,"liveId or channelId required");
		boolean moderator = memberships.memberRole(channelId, user.userId())
				.filter(role -> role == AuthorRole.MODERATOR)
				.isPresent();
		if (!moderator) {
			throw new ApiException(ErrorCode.FORBIDDEN, "Only channel moderators can subscribe");
		}
		return ResponseEntity.ok()
				.header("Cache-Control", "no-cache")
				.header("X-Accel-Buffering", "no")
				.body(hub.subscribe(channelId, user.userId(), request.getSession().getId()));
	}

}
