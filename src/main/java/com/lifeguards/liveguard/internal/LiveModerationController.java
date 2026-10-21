package com.lifeguards.liveguard.internal;

import com.lifeguards.liveguard.common.*;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.response.ApiResponse;
import com.lifeguards.liveguard.contracts.dto.request.*;
import tools.jackson.databind.ObjectMapper;
import com.lifeguards.liveguard.guard.LiveModerationService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class LiveModerationController {
  private final LiveModerationService live;
  private final ObjectMapper mapper;

  LiveModerationController(LiveModerationService live, ObjectMapper mapper) {
    this.live = live;
    this.mapper = mapper;
  }

  @GetMapping("/api/reviews")
  ApiResponse<?> reviews(
      @RequestParam UUID liveId, @AuthenticationPrincipal AuthenticatedUser user) {
    return wrap(live.reviews(liveId, user));
  }

  @GetMapping("/api/cases")
  ApiResponse<?> cases(@RequestParam UUID liveId, @AuthenticationPrincipal AuthenticatedUser user) {
    return wrap(live.cases(liveId, user));
  }

  @GetMapping("/api/cases/{caseId}")
  ApiResponse<?> detail(
      @PathVariable UUID caseId, @AuthenticationPrincipal AuthenticatedUser user) {
    return wrap(live.detail(caseId, user));
  }

  @PostMapping("/api/context-reviews/{reviewId}/decision")
  @ResponseStatus(HttpStatus.ACCEPTED)
  ApiResponse<?> context(
      @PathVariable UUID reviewId,
      @RequestBody ContextDecisionRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return wrap(live.decide(reviewId, body(request), user, "CONTEXT"));
  }

  @PostMapping("/api/kick-reviews/{reviewId}/decision")
  @ResponseStatus(HttpStatus.ACCEPTED)
  ApiResponse<?> kick(
      @PathVariable UUID reviewId,
      @RequestBody KickDecisionRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return wrap(live.decide(reviewId, body(request), user, "KICK"));
  }

  @PostMapping("/internal/cases/{caseId}/judgment")
  @SecurityRequirement(name = "serviceToken")
  ApiResponse<?> judgment(@PathVariable UUID caseId, @RequestBody JudgmentRequest request) {
    return wrap(live.judgment(caseId, body(request)));
  }

  @GetMapping("/internal/reviews/{reviewId}/decisions/{decisionId}")
  @SecurityRequirement(name = "serviceToken")
  ApiResponse<?> decision(@PathVariable UUID reviewId, @PathVariable UUID decisionId) {
    return wrap(live.decision(reviewId, decisionId, true));
  }

  @PostMapping("/internal/reviews/{reviewId}/apply")
  @SecurityRequirement(name = "serviceToken")
  ApiResponse<?> apply(@PathVariable UUID reviewId, @RequestBody Map<String, Object> request) {
    return wrap(live.apply(reviewId, request));
  }

  @PostMapping("/internal/kick-reviews/{reviewId}/revalidate")
  @SecurityRequirement(name = "serviceToken")
  ApiResponse<?> revalidate(@PathVariable UUID reviewId, @RequestBody Map<String, Object> request) {
    return wrap(live.revalidate(reviewId, UUID.fromString(request.get("decisionId").toString())));
  }

  private Map<String, Object> body(Object request) {
    Map<String,Object> result = mapper.convertValue(request, Map.class);
    result.values().removeIf(Objects::isNull);
    return result;
  }

  private ApiResponse<?> wrap(Object data) {
    return new ApiResponse<>(data, TraceIdFilter.current());
  }
}
