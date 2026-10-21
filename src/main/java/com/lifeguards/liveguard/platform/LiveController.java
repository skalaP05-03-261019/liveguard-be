package com.lifeguards.liveguard.platform;

import com.lifeguards.liveguard.common.*;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.request.SendMessageRequest;
import com.lifeguards.liveguard.contracts.dto.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class LiveController {
  private final LiveService lives;

  LiveController(LiveService lives) {
    this.lives = lives;
  }

  @PostMapping("/api/channels/{channelId}/lives")
  @ResponseStatus(HttpStatus.CREATED)
  ApiResponse<?> start(
      @PathVariable UUID channelId, @AuthenticationPrincipal AuthenticatedUser user) {
    return new ApiResponse<>(lives.start(channelId, user), TraceIdFilter.current());
  }

  @GetMapping("/api/channels/{channelId}/lives/current")
  ApiResponse<?> current(
      @PathVariable UUID channelId, @AuthenticationPrincipal AuthenticatedUser user) {
    return new ApiResponse<>(lives.current(channelId, user), TraceIdFilter.current());
  }

  @PostMapping("/api/lives/{liveId}/end")
  ApiResponse<?> end(@PathVariable UUID liveId, @AuthenticationPrincipal AuthenticatedUser user) {
    return new ApiResponse<>(lives.end(liveId, user), TraceIdFilter.current());
  }

  @PostMapping("/api/lives/{liveId}/messages")
  @ResponseStatus(HttpStatus.CREATED)
  ApiResponse<?> send(
      @PathVariable UUID liveId,
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestBody SendMessageRequest request) {
    return new ApiResponse<>(lives.send(liveId, user, request), TraceIdFilter.current());
  }

  @GetMapping("/api/lives/{liveId}/messages")
  ApiResponse<?> history(
      @PathVariable UUID liveId, @AuthenticationPrincipal AuthenticatedUser user) {
    return new ApiResponse<>(lives.history(liveId, user), TraceIdFilter.current());
  }

  @GetMapping("/api/lives/{liveId}/events")
  Object events(
      @PathVariable UUID liveId,
      @AuthenticationPrincipal AuthenticatedUser user,
      HttpServletRequest req) {
    return lives.subscribe(liveId, user, req.getSession().getId(), false);
  }
}
