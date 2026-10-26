package com.lifeguards.liveguard.platform;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lifeguards.liveguard.common.TraceIdFilter;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.response.ApiResponse;

@RestController
class ChannelController {
	private final LiveService lives;

	ChannelController(LiveService lives) {
		this.lives = lives;
	}

	@GetMapping("/api/channels")
	ApiResponse<List<Map<String,Object>>> channels(@AuthenticationPrincipal AuthenticatedUser user) {
		return new ApiResponse<>(lives.channels(user), TraceIdFilter.current());
	}
}
