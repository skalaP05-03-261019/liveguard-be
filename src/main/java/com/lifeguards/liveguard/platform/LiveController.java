package com.lifeguards.liveguard.platform;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lifeguards.liveguard.common.TraceIdFilter;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.response.ApiResponse;
import com.lifeguards.liveguard.contracts.dto.response.Live;

@RestController
class LiveController {

	private final LiveService liveService;

	LiveController(LiveService liveService) {
		this.liveService = liveService;
	}

	@PostMapping("/api/channels/{channelId}/lives")
	ResponseEntity<ApiResponse<Live>> start(@PathVariable UUID channelId,
			@AuthenticationPrincipal AuthenticatedUser user) {
		Live live = liveService.start(channelId, user);
		return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(live, TraceIdFilter.current()));
	}

	@GetMapping("/api/channels/{channelId}/lives/current")
	ApiResponse<Live> current(@PathVariable UUID channelId, @AuthenticationPrincipal AuthenticatedUser user) {
		return new ApiResponse<>(liveService.current(channelId, user), TraceIdFilter.current());
	}

	@PostMapping("/api/lives/{liveId}/end")
	ApiResponse<Live> end(@PathVariable UUID liveId, @AuthenticationPrincipal AuthenticatedUser user) {
		return new ApiResponse<>(liveService.end(liveId, user), TraceIdFilter.current());
	}

}
