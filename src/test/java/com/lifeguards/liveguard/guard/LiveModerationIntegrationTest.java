package com.lifeguards.liveguard.guard;

import static org.junit.jupiter.api.Assertions.*;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.common.*;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.request.SendMessageRequest;
import com.lifeguards.liveguard.contracts.enums.*;
import com.lifeguards.liveguard.platform.LiveService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {"LIVEGUARD_POLICY_VERSION=v3.1", "LIVEGUARD_TBD_FIXTURE_ENABLED=true"})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class LiveModerationIntegrationTest {
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
  @Autowired LiveService lives;
  @Autowired LiveModerationService guard;
  @Autowired JdbcTemplate db;
  UUID channel, user, operator, other;
  AuthenticatedUser viewer, moderator, second;

  record User(UUID userId, String nickname, Role role) implements AuthenticatedUser {}

  @BeforeEach
  void setup() {
    channel = UUID.randomUUID();
    user = UUID.randomUUID();
    operator = UUID.randomUUID();
    other = UUID.randomUUID();
    db.update("INSERT INTO platform.channels(id,name) VALUES(?,'test')", channel);
    for (UUID id : List.of(user, operator, other)) {
      db.update(
          "INSERT INTO platform.users(id,username,password_hash,nickname,role)"
              + " VALUES(?,?,?,'test','VIEWER')",
          id,
          id.toString(),
          "unused");
      db.update(
          "INSERT INTO platform.channel_members(channel_id,user_id,member_role) VALUES(?,?,?)",
          channel,
          id,
          id.equals(operator) ? "MODERATOR" : "VIEWER");
    }
    viewer = new User(user, "viewer", Role.VIEWER);
    moderator = new User(operator, "moderator", Role.MODERATOR);
    second = new User(other, "other", Role.VIEWER);
  }

  UUID candidate(UUID live, String text) {
    var m = lives.send(live, viewer, new SendMessageRequest(UUID.randomUUID(), text));
    return db.queryForObject(
        "SELECT case_id FROM platform.messages WHERE id=?", UUID.class, m.get("messageId"));
  }

  UUID proposal(UUID c, String verdict) {
    var snap = guard.snapshot(c);
    Map<String, Object> input = new LinkedHashMap<>();
    for (String k :
        List.of(
            "caseId", "liveId", "snapshotId", "evidenceVersion", "policyVersion", "handlingRoute"))
      input.put(k, snap.get(k));
    String target = ((List<?>) snap.get("targetMessageIds")).getFirst().toString();
    input.put(
        "messageFindings",
        List.of(
            Map.of(
                "messageId",
                target,
                "verdict",
                verdict,
                "categories",
                verdict.equals("VIOLATION") ? List.of("HARASSMENT") : List.of(),
                "policyIds",
                List.of("CP-3.1"),
                "reason",
                "test evidence",
                "missingFacts",
                verdict.equals("NEEDS_REVIEW") ? List.of("unknown") : List.of())));
    var receipt = guard.judgment(c, input);
    assertEquals(receipt, guard.judgment(c, input));
    return receipt.get("reviewId") == null
        ? null
        : UUID.fromString(receipt.get("reviewId").toString());
  }

  Map<String, Object> decide(UUID r, String value, int version) {
    var card = guard.reviewCard(r);
    var request = new LinkedHashMap<String, Object>();
    request.put("clientRequestId", UUID.randomUUID().toString());
    request.put("expectedReviewVersion", version);
    request.put("decision", value);
    request.put("reason", "operator examined evidence");
    if (value.equals("CONFIRM_VIOLATION")) {
      request.put("categories", List.of("HARASSMENT"));
      request.put("evidenceMessageIds", card.get("targetMessageIds"));
    }
    var d = guard.decide(r, request, moderator, card.get("kind").toString());
    assertEquals(d, guard.decide(r, request, moderator, card.get("kind").toString()));
    return d;
  }

  Map<String, Object> apply(UUID c, UUID r, Map<String, Object> d) {
    var snap = guard.snapshot(c);
    var request = new LinkedHashMap<String, Object>();
    for (String k : List.of("caseId", "liveId", "snapshotId", "evidenceVersion", "policyVersion"))
      request.put(k, snap.get(k));
    request.put(
        "taskId",
        db.queryForObject(
                "SELECT task_id FROM guard.live_case_state WHERE case_id=?", UUID.class, c)
            .toString());
    request.put(
        "threadId",
        db.queryForObject(
            "SELECT thread_id FROM guard.live_case_state WHERE case_id=?", String.class, c));
    request.put("reviewId", r.toString());
    request.put("decisionId", d.get("decisionId").toString());
    var result = guard.apply(r, request);
    assertEquals(result, guard.apply(r, request));
    return result;
  }

  @Test
  void integrationModeCannotBypassLiveRestrictionsThroughDeprecatedChannelRoutes() throws Exception {
    db.update("UPDATE platform.users SET password_hash=? WHERE id=?", passwordEncoder.encode("liveguard-local"), user);
    var session = com.lifeguards.liveguard.support.LoginSupport.login(mvc, user.toString());
    var base = "/api/channels/" + channel;
    mvc.perform(com.lifeguards.liveguard.support.LoginSupport.withXsrf(mvc,
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base + "/messages").session(session))
        .contentType("application/json").content("{\"clientMessageId\":\""+UUID.randomUUID()+"\",\"text\":\"bypass\"}"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
    for (String route : List.of("/messages", "/events"))
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base+route).session(session))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
  }

  @Test
  void actualLiveScopeAndEndedBroadcastAndLegacyPreservation() {
    assertEquals(
        ErrorCode.FORBIDDEN,
        assertThrows(ApiException.class, () -> lives.start(channel, viewer)).code());
    var live = lives.start(channel, moderator);
    assertNotEquals(channel, live.liveId());
    assertEquals(
        ErrorCode.LIVE_ALREADY_ACTIVE,
        assertThrows(ApiException.class, () -> lives.start(channel, moderator)).code());
    lives.send(live.liveId(), viewer, new SendMessageRequest(UUID.randomUUID(), "hello"));
    lives.end(live.liveId(), moderator);
    assertEquals(
        ErrorCode.LIVE_ENDED,
        assertThrows(
                ApiException.class,
                () ->
                    lives.send(
                        live.liveId(), viewer, new SendMessageRequest(UUID.randomUUID(), "hello")))
            .code());
    var next = lives.start(channel, moderator);
    assertNotEquals(live.liveId(), next.liveId());
    assertEquals(0, ((List<?>) lives.history(next.liveId(), viewer).get("items")).size());
  }

  @Test
  void contextNormalWaitsForHumanDeferAndDuplicateApply() {
    UUID live = lives.start(channel, moderator).liveId();
    UUID c = candidate(live, "너 어디 꺼져");
    assertEquals("CONTEXT_REVIEW", guard.snapshot(c).get("handlingRoute"));
    UUID review = proposal(c, "NO_VIOLATION");
    assertEquals(
        "HELD",
        db.queryForObject(
            "SELECT visibility FROM platform.messages WHERE case_id=?", String.class, c));
    assertEquals(0, ((List<?>) lives.history(live, second).get("items")).size());
    var defer = decide(review, "DEFER", 1);
    assertEquals("PENDING", guard.reviewCard(review).get("status"));
    assertEquals(
        ErrorCode.STALE_VERSION,
        assertThrows(ApiException.class, () -> decide(review, "CONFIRM_NORMAL", 1)).code());
    var d = decide(review, "CONFIRM_NORMAL", 2);
    apply(c, review, d);
    assertEquals(
        "PUBLIC",
        db.queryForObject(
            "SELECT visibility FROM platform.messages WHERE case_id=?", String.class, c));
    assertEquals(1, ((List<?>) lives.history(live, second).get("items")).size());
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM guard.findings WHERE case_id=? AND source='BACKEND'",
            Integer.class,
            c));
  }

  @Test
  void piiUncertaintyUsesContextAndOtherHoldReasonPreventsRelease() {
    UUID live = lives.start(channel, moderator).liveId();
    UUID c = candidate(live, "연락 010-1234-5678");
    assertEquals("PII_INVESTIGATION", guard.snapshot(c).get("handlingRoute"));
    UUID r = proposal(c, "NEEDS_REVIEW");
    assertNotNull(r);
    assertFalse(guard.context(c).toString().contains("010-1234-5678"));
    UUID message =
        db.queryForObject("SELECT id FROM platform.messages WHERE case_id=?", UUID.class, c);
    db.update(
        "INSERT INTO platform.message_hold_reasons(message_id,case_id,reason,created_at)"
            + " VALUES(?,?,'OTHER',current_timestamp)",
        message,
        UUID.randomUUID());
    apply(c, r, decide(r, "CONFIRM_NORMAL", 1));
    assertEquals(
        "HELD",
        db.queryForObject(
            "SELECT visibility FROM platform.messages WHERE id=?", String.class, message));
  }

  @Test
  void approvedViolationCreatesTimeoutAndRejectsUnauthorisedDecision() {
    UUID live = lives.start(channel, moderator).liveId();
    UUID c = candidate(live, "너 꺼져");
    UUID r = proposal(c, "VIOLATION");
    var request =
        Map.<String, Object>of(
            "clientRequestId",
            UUID.randomUUID().toString(),
            "expectedReviewVersion",
            1,
            "decision",
            "CONFIRM_NORMAL",
            "reason",
            "x");
    assertEquals(
        ErrorCode.FORBIDDEN,
        assertThrows(ApiException.class, () -> guard.decide(r, request, viewer, "CONTEXT")).code());
    var result = apply(c, r, decide(r, "CONFIRM_VIOLATION", 1));
    assertEquals("APPLIED", result.get("status"));
    assertEquals(
        "HIDDEN",
        db.queryForObject(
            "SELECT visibility FROM platform.messages WHERE case_id=?", String.class, c));
    assertEquals(
        ErrorCode.TIMED_OUT,
        assertThrows(
                ApiException.class,
                () -> lives.send(live, viewer, new SendMessageRequest(UUID.randomUUID(), "hello")))
            .code());
  }

  @Test
  void endedLiveBlocksHumanApply() {
    UUID live = lives.start(channel, moderator).liveId();
    UUID c = candidate(live, "꺼져");
    UUID r = proposal(c, "NO_VIOLATION");
    var d = decide(r, "CONFIRM_NORMAL", 1);
    lives.end(live, moderator);
    assertEquals(
        ErrorCode.LIVE_ENDED, assertThrows(ApiException.class, () -> apply(c, r, d)).code());
    assertEquals(
        "HELD",
        db.queryForObject(
            "SELECT visibility FROM platform.messages WHERE case_id=?", String.class, c));
  }

  @Test
  void threeConfirmedCasesRequireSeparateKickApprovalAndIdempotentExecution() {
    UUID live = lives.start(channel, moderator).liveId();
    List<UUID> cases = new ArrayList<>();
    List<UUID> reviews = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      UUID c = candidate(live, "꺼져 사례 " + i);
      cases.add(c);
      reviews.add(proposal(c, "VIOLATION"));
    }
    Map<String, Object> applied = Map.of();
    for (int i = 0; i < 3; i++)
      applied = apply(cases.get(i), reviews.get(i), decide(reviews.get(i), "CONFIRM_VIOLATION", 1));
    var kick = (Map<String, Object>) applied.get("kickReview");
    assertEquals(true, kick.get("required"));
    UUID review = UUID.fromString(kick.get("reviewId").toString());
    assertEquals(0, db.queryForObject("SELECT count(*) FROM guard.live_kicks", Integer.class));
    var decision = decide(review, "APPROVE", 1);
    UUID d = UUID.fromString(decision.get("decisionId").toString());
    assertEquals(
        3,
        db.queryForObject(
            "SELECT count(*) FROM guard.live_reservations WHERE state='CONSUMED'", Integer.class));
    var validation = guard.revalidate(review, d);
    assertEquals("APPROVED", validation.get("status"));
    var command = (Map<String, Object>) validation.get("actionCommand");
    UUID a = UUID.fromString(command.get("actionId").toString());
    var result = guard.execute(a, command);
    assertEquals("SUCCEEDED", result.get("status"));
    assertEquals(result, guard.execute(a, command));
    assertEquals(
        ErrorCode.KICKED,
        assertThrows(ApiException.class, () -> lives.history(live, viewer)).code());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM guard.live_kicks", Integer.class));
    lives.end(live, moderator);
    UUID next = lives.start(channel, moderator).liveId();
    assertDoesNotThrow(
        () ->
            lives.send(next, viewer, new SendMessageRequest(UUID.randomUUID(), "new live hello")));
  }

  @Test
  void rejectedKickConsumesBasisWithoutRestoringHiddenMessages() {
    UUID live = lives.start(channel, moderator).liveId();
    List<UUID> cases = new ArrayList<>(), reviews = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      UUID c = candidate(live, "꺼져 거절 " + i);
      cases.add(c);
      reviews.add(proposal(c, "VIOLATION"));
    }
    Map<String, Object> result = Map.of();
    for (int i = 0; i < 3; i++)
      result = apply(cases.get(i), reviews.get(i), decide(reviews.get(i), "CONFIRM_VIOLATION", 1));
    UUID r = UUID.fromString(((Map<?, ?>) result.get("kickReview")).get("reviewId").toString());
    var d = decide(r, "REJECT", 1);
    assertEquals(
        "REJECTED",
        guard.revalidate(r, UUID.fromString(d.get("decisionId").toString())).get("status"));
    assertEquals(0, db.queryForObject("SELECT count(*) FROM guard.live_kicks", Integer.class));
    assertEquals(
        3,
        db.queryForObject(
            "SELECT count(*) FROM platform.messages WHERE live_id=? AND visibility='HIDDEN'",
            Integer.class,
            live));
  }
}
