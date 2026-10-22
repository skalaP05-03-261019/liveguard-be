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
  private final com.lifeguards.liveguard.guard.LiveFindingRecovery recovery;

  LiveModerationController(LiveModerationService live, ObjectMapper mapper, com.lifeguards.liveguard.guard.LiveFindingRecovery recovery) {
    this.live = live;
    this.mapper = mapper;
    this.recovery=recovery;
  }

  @GetMapping("/api/reviews")
  ApiResponse<?> reviews(
      @RequestParam UUID liveId, @RequestParam com.lifeguards.liveguard.contracts.enums.ReviewKind kind, @RequestParam(required=false) com.lifeguards.liveguard.contracts.enums.ReviewStatus status, @AuthenticationPrincipal AuthenticatedUser user) {
    return wrap(live.reviews(liveId, user).stream().filter(r->r.get("kind").equals(kind.name())).filter(r->status==null||r.get("status").equals(status.name())).toList());
  }

  @GetMapping("/api/cases")
  ApiResponse<?> cases(@RequestParam UUID liveId,
      @RequestParam(required=false) com.lifeguards.liveguard.contracts.enums.ConfirmationStatus confirmationStatus,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return wrap(live.cases(liveId, confirmationStatus, user));
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

  @PostMapping("/api/findings/{findingId}/revoke")
  ApiResponse<?> revoke(@PathVariable UUID findingId,@RequestBody RevokeFindingRequest request,@AuthenticationPrincipal AuthenticatedUser user){
    return wrap(recovery.revoke(findingId,request,user));
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
