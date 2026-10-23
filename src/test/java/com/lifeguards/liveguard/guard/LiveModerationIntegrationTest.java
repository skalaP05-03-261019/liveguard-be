package com.lifeguards.liveguard.guard;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    properties = {"LIVEGUARD_TBD_FIXTURE_ENABLED=true"})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class LiveModerationIntegrationTest {
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
  @Autowired LiveService lives;
  @Autowired LiveModerationService guard;
  @Autowired LiveFindingRecovery recovery;
  @Autowired CaseQueryService queries;
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
    return decide(r, value, version, List.of("HARASSMENT"));
  }

  Map<String, Object> decide(UUID r, String value, int version, List<String> categories) {
    var card = guard.reviewCard(r);
    var request = new LinkedHashMap<String, Object>();
    request.put("clientRequestId", UUID.randomUUID().toString());
    request.put("expectedReviewVersion", version);
    request.put("decision", value);
    // 운영자 화면에는 사유 입력란이 없다(A-26)
    if (value.equals("CONFIRM_VIOLATION")) {
      request.put("categories", categories);
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
  void publicReleaseKeepsRuleHoldButRevocationReleasesOnlyItsOwnReason() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID context=candidate(live,"너 꺼져 별도 문맥");
    UUID rule=candidate(live,"씨발");
    UUID message=db.queryForObject("SELECT id FROM platform.messages WHERE case_id=?",UUID.class,rule);
    int version=db.queryForObject("SELECT version FROM platform.messages WHERE id=?",Integer.class,message);
    lives.visibility(rule,"PUBLIC");
    assertEquals("HIDDEN",db.queryForObject("SELECT visibility FROM platform.messages WHERE id=?",String.class,message));
    assertEquals(version,db.queryForObject("SELECT version FROM platform.messages WHERE id=?",Integer.class,message));
    assertNull(db.queryForObject("SELECT released_at FROM platform.message_hold_reasons WHERE message_id=? AND case_id=?",java.sql.Timestamp.class,message,rule));
    db.update("INSERT INTO platform.message_hold_reasons(message_id,case_id,reason,created_at) VALUES(?,?,'CONTEXT_REVIEW',current_timestamp)",message,context);
    recovery.revoke(confirmedFinding(rule),revokeRequest(rule),moderator);
    assertEquals("HIDDEN",db.queryForObject("SELECT visibility FROM platform.messages WHERE id=?",String.class,message));
    assertNull(db.queryForObject("SELECT released_at FROM platform.message_hold_reasons WHERE message_id=? AND case_id=?",java.sql.Timestamp.class,message,context));
    lives.visibility(context,"PUBLIC");
    assertEquals("PUBLIC",db.queryForObject("SELECT visibility FROM platform.messages WHERE id=?",String.class,message));
    assertEquals(version+1,db.queryForObject("SELECT version FROM platform.messages WHERE id=?",Integer.class,message));
  }

  /** 사건 snapshot 그대로의 판정 요청. policyVersion·policyIds만 바꿔 버전 검사를 본다. */
  Map<String, Object> harassmentJudgment(UUID c, String policyVersion, List<String> policyIds) {
    var snap = guard.snapshot(c);
    Map<String, Object> input = new LinkedHashMap<>();
    for (String k : List.of("caseId", "liveId", "snapshotId", "evidenceVersion", "handlingRoute"))
      input.put(k, snap.get(k));
    input.put("policyVersion", policyVersion);
    input.put("messageFindings", List.of(Map.of(
        "messageId", ((List<?>) snap.get("targetMessageIds")).getFirst().toString(),
        "verdict", "VIOLATION", "categories", List.of("HARASSMENT"), "policyIds", policyIds,
        "reason", "test evidence", "missingFacts", List.of())));
    return input;
  }

  @Test
  void v32CaseAcceptsGroupHarassmentClauseOnlyUnderItsOwnVersion() {
    UUID live = lives.start(channel, moderator).liveId();
    UUID c = candidate(live, "너 꺼져");
    assertEquals("v3.2", guard.snapshot(c).get("policyVersion"));
    // 사건 버전과 다른 버전으로 판정하면 오래된 요청이다
    assertEquals(ErrorCode.STALE_VERSION, assertThrows(ApiException.class,
        () -> guard.judgment(c, harassmentJudgment(c, "v3.1", List.of("CP-3.2")))).code());
    // 정책에 없는 조항은 받지 않는다
    assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ApiException.class,
        () -> guard.judgment(c, harassmentJudgment(c, "v3.2", List.of("CP-3.4")))).code());
    assertNotNull(guard.judgment(c, harassmentJudgment(c, "v3.2", List.of("CP-3.3", "CP-2.2"))).get("reviewId"));
  }

  @Test
  void caseOpenedBeforeV32CutoverIsJudgedUnderV31() {
    UUID live = lives.start(channel, moderator).liveId();
    UUID c = candidate(live, "너 꺼져");
    // 전환 전에 v3.1로 기록된 사건을 흉내 낸다(사건·snapshot의 버전을 함께 바꿔 불변 증거 검사를 통과시킨다)
    db.update("UPDATE guard.cases SET policy_version='v3.1' WHERE id=?", c);
    db.update("UPDATE guard.live_case_state SET snapshot=jsonb_set(snapshot,'{policyVersion}','\"v3.1\"') WHERE case_id=?", c);
    db.update("UPDATE guard.case_snapshots SET snapshot=jsonb_set(snapshot,'{policyVersion}','\"v3.1\"') WHERE case_id=?", c);
    assertEquals("v3.1", guard.snapshot(c).get("policyVersion"));
    assertEquals(ErrorCode.STALE_VERSION, assertThrows(ApiException.class,
        () -> guard.judgment(c, harassmentJudgment(c, "v3.2", List.of("CP-3.2")))).code());
    // v3.1 사건은 v3.2에서 생긴 CP-3.3을 인용할 수 없다
    assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ApiException.class,
        () -> guard.judgment(c, harassmentJudgment(c, "v3.1", List.of("CP-3.3")))).code());
    assertNotNull(guard.judgment(c, harassmentJudgment(c, "v3.1", List.of("CP-3.2"))).get("reviewId"));
  }

  /** 개인정보 경로의 확정 위반 판정. 형태와 노출 문맥만 근거다(실재·유효성은 확인하지 않음). */
  Map<String, Object> piiViolation(UUID c) {
    var input = harassmentJudgment(c, guard.snapshot(c).get("policyVersion").toString(), List.of("SP-3.1"));
    var finding = new LinkedHashMap<>((Map<String, Object>) ((List<?>) input.get("messageFindings")).getFirst());
    finding.put("categories", List.of("PII_EXPOSURE"));
    input.put("messageFindings", List.of(finding));
    return input;
  }

  @Test
  void v32PiiViolationIsConfirmedFromFormAndContextWithoutVerifiedFacts() {
    UUID live = lives.start(channel, moderator).liveId();
    // 합성 입력이다. 실제 개인의 유효한 연락처인지는 확인하지 않는다
    UUID first = candidate(live, "제 번호 010-0000-1111 로 연락주세요");
    assertEquals("PII_INVESTIGATION", guard.snapshot(first).get("handlingRoute"));
    var receipt = guard.judgment(first, piiViolation(first));
    assertEquals("CONFIRMED_VIOLATION", receipt.get("confirmationStatus"));
    assertNull(receipt.get("reviewId"));
    assertEquals("HIDDEN", visibilityOf(first));
    // 첫 개인정보 위반은 HIDE만, 두 번째는 TIMEOUT 600초(제재표 그대로)
    assertEquals(List.of(), timeoutSeconds(first));
    assertEquals(receipt, guard.judgment(first, piiViolation(first)));
    UUID second = candidate(live, "메일은 someone@example.com 입니다");
    guard.judgment(second, piiViolation(second));
    assertEquals(List.of(600L), timeoutSeconds(second));
  }

  @Test
  void v31PiiViolationStillNeedsVerifiedFacts() {
    UUID live = lives.start(channel, moderator).liveId();
    UUID c = candidate(live, "제 번호 010-0000-2222 로 연락주세요");
    db.update("UPDATE guard.cases SET policy_version='v3.1' WHERE id=?", c);
    db.update("UPDATE guard.live_case_state SET snapshot=jsonb_set(snapshot,'{policyVersion}','\"v3.1\"') WHERE case_id=?", c);
    db.update("UPDATE guard.case_snapshots SET snapshot=jsonb_set(snapshot,'{policyVersion}','\"v3.1\"') WHERE case_id=?", c);
    assertEquals(ErrorCode.REVIEW_NOT_APPROVED,
        assertThrows(ApiException.class, () -> guard.judgment(c, piiViolation(c))).code());
    assertEquals("HELD", visibilityOf(c));
    // AI 판정 없는 검토가 열린 뒤 늦게 온 확정 판정은 버전과 관계없이 409다(A-25)
    assertTrue(guard.openReviewWithoutJudgment(c, java.time.Duration.ZERO));
    assertEquals(ErrorCode.STALE_VERSION,
        assertThrows(ApiException.class, () -> guard.judgment(c, piiViolation(c))).code());
  }

  @Test
  void piiRouteConfirmsOnlyPiiExposureWithoutAnOperator() {
    UUID live = lives.start(channel, moderator).liveId();
    UUID c = candidate(live, "꺼져 연락 010-0000-3333");
    assertEquals("PII_INVESTIGATION", guard.snapshot(c).get("handlingRoute"));
    // 개인정보 경로의 확정은 운영자를 거치지 않으므로 괴롭힘 등 다른 유형은 받지 않는다(운영자 결정이 필요하면 NEEDS_REVIEW)
    assertEquals(ErrorCode.REVIEW_NOT_APPROVED, assertThrows(ApiException.class,
        () -> guard.judgment(c, harassmentJudgment(c, "v3.2", List.of("CP-3.2")))).code());
    var both = piiViolation(c);
    var finding = new LinkedHashMap<>((Map<String, Object>) ((List<?>) both.get("messageFindings")).getFirst());
    finding.put("categories", List.of("PII_EXPOSURE", "HARASSMENT"));
    both.put("messageFindings", List.of(finding));
    assertEquals(ErrorCode.REVIEW_NOT_APPROVED,
        assertThrows(ApiException.class, () -> guard.judgment(c, both)).code());
    assertEquals("HELD", visibilityOf(c));
    assertEquals(0, db.queryForObject(
        "SELECT count(*) FROM guard.findings WHERE case_id=? AND status='CONFIRMED'", Integer.class, c));
  }

  @Test
  void floodRepeatJoiningPreCutoverCaseKeepsThatCaseVersion() {
    UUID live = lives.start(channel, moderator).liveId();
    for (int i = 0; i < 3; i++) lives.send(live, viewer, new SendMessageRequest(UUID.randomUUID(), "같은 응원"));
    UUID c = db.queryForObject("SELECT id FROM guard.cases WHERE live_id=?", UUID.class, live);
    db.update("UPDATE guard.cases SET policy_version='v3.1' WHERE id=?", c);
    db.update("UPDATE guard.live_case_state SET snapshot=jsonb_set(snapshot,'{policyVersion}','\"v3.1\"') WHERE case_id=?", c);
    db.update("UPDATE guard.case_snapshots SET snapshot=jsonb_set(snapshot,'{policyVersion}','\"v3.1\"') WHERE case_id=?", c);
    // 전환 뒤(활성 v3.2) 같은 문장이 10초 안에 반복되면 전환 전 사건에 합류한다. 사건은 v3.1로 계속 읽힌다
    lives.send(live, viewer, new SendMessageRequest(UUID.randomUUID(), "같은 응원"));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM guard.cases WHERE live_id=?", Integer.class, live));
    var snap = guard.snapshot(c);
    assertEquals("v3.1", snap.get("policyVersion"));
    assertEquals(2, ((Number) snap.get("evidenceVersion")).intValue());
  }

  @Test
  void judgmentWithNullPolicyIdIsRejectedAsInvalid() {
    UUID live = lives.start(channel, moderator).liveId();
    UUID c = candidate(live, "너 꺼져");
    var ids = new ArrayList<String>();
    ids.add("CP-3.2");
    ids.add(null);
    assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ApiException.class,
        () -> guard.judgment(c, harassmentJudgment(c, "v3.2", ids))).code());
  }

  @Test
  void decisionReasonIsOptionalButMustNotBeBlankWhenSent() throws Exception {
    UUID live = lives.start(channel, moderator).liveId();
    UUID c = candidate(live, "너 꺼져");
    UUID r = proposal(c, "VIOLATION");
    db.update("UPDATE platform.users SET password_hash=? WHERE id=?", passwordEncoder.encode("liveguard-local"), operator);
    var session = com.lifeguards.liveguard.support.LoginSupport.login(mvc, operator.toString());
    String path = "/api/context-reviews/" + r + "/decision";
    java.util.function.Function<String, org.springframework.test.web.servlet.ResultActions> post = body -> {
      try {
        return mvc.perform(com.lifeguards.liveguard.support.LoginSupport.withXsrf(mvc,
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).session(session))
            .contentType("application/json").content(body));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    };
    // 보내면 공백 아닌 1~500자
    post.apply("{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"expectedReviewVersion\":1,\"decision\":\"DEFER\",\"reason\":\"  \"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    // 운영자 화면(A-26)은 사유를 보내지 않는다
    post.apply("{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"expectedReviewVersion\":1,\"decision\":\"DEFER\"}")
        .andExpect(status().isAccepted());
    // 이전 FE는 위반 확정에 사유를 함께 보낸다
    UUID target = db.queryForObject("SELECT id FROM platform.messages WHERE case_id=?", UUID.class, c);
    post.apply("{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"expectedReviewVersion\":2,\"decision\":\"CONFIRM_VIOLATION\","
            + "\"reason\":\"스트리머 인신공격\",\"categories\":[\"HARASSMENT\"],\"evidenceMessageIds\":[\"" + target + "\"]}")
        .andExpect(status().isAccepted());
    assertEquals(
        List.of("DEFER:", "CONFIRM_VIOLATION:스트리머 인신공격"),
        db.queryForList(
            "SELECT value||':'||reason FROM guard.decisions WHERE review_id=? ORDER BY decided_at,value DESC", String.class, r));
  }

  @Test
  void eligibilityReadsActualReservationsAndOriginalConsumptionWithoutReplacingBasis() {
    UUID live=lives.start(channel,moderator).liveId();
    List<UUID> cs=new ArrayList<>(),rs=new ArrayList<>();
    for(int i=0;i<4;i++){UUID c=candidate(live,"너 꺼져 읽기 전용 "+i);cs.add(c);rs.add(proposal(c,"VIOLATION"));}
    apply(cs.get(0),rs.get(0),decide(rs.get(0),"CONFIRM_VIOLATION",1));
    apply(cs.get(1),rs.get(1),decide(rs.get(1),"CONFIRM_VIOLATION",1));
    assertEquals(2,queries.kickEligibility(cs.get(2)).count());
    apply(cs.get(2),rs.get(2),decide(rs.get(2),"CONFIRM_VIOLATION",1));
    UUID kick=db.queryForObject("SELECT id FROM guard.reviews WHERE case_id=? AND kind='KICK'",UUID.class,cs.get(2));
    var fixed=(List<?>)guard.reviewCard(kick).get("basisCaseIds");
    assertEquals(0,queries.kickEligibility(cs.get(2)).count());
    assertEquals(ErrorCode.CASE_NOT_READY,assertThrows(ApiException.class,()->guard.kickReviewBasis(kick)).code());
    var decision=decide(kick,"APPROVE",1);
    apply(cs.get(3),rs.get(3),decide(rs.get(3),"CONFIRM_VIOLATION",1));
    assertEquals(List.of(cs.get(3)),queries.kickEligibility(cs.get(3)).eligibleCaseIds());
    var basis=guard.kickReviewBasis(kick);
    assertEquals(fixed,basis.basisCaseIds());
    assertEquals(3,basis.consumptions().size());
    assertTrue(basis.consumptions().stream().allMatch(c->c.reviewId().equals(kick)&&c.decisionId().toString().equals(decision.get("decisionId").toString())));
    assertEquals(3,queries.userHistory(cs.get(3),null).violationCount());
    assertEquals(3,db.queryForObject("SELECT count(*) FROM guard.live_reservations WHERE state='CONSUMED'",Integer.class));
  }

  @Test
  @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  void committedLiveEndInvalidatesOnlyKickWorkAndPreservesHistoryAndDeliveryOutcomes() {
    try {
      UUID live=lives.start(channel,moderator).liveId();
      List<UUID> cs=new ArrayList<>(),rs=new ArrayList<>();
      for(int i=0;i<7;i++){UUID c=candidate(live,"너 꺼져 방송 종료 "+i);cs.add(c);rs.add(proposal(c,"VIOLATION"));}
      for(int i=0;i<6;i++)apply(cs.get(i),rs.get(i),decide(rs.get(i),"CONFIRM_VIOLATION",1));
      var kicks=db.queryForList("SELECT id FROM guard.reviews WHERE kind='KICK' ORDER BY created_at,id",UUID.class);
      UUID approved=kicks.getFirst();
      var decision=decide(approved,"APPROVE",1);
      var validated=guard.revalidate(approved,UUID.fromString(decision.get("decisionId").toString()));
      var command=(Map<String,Object>)validated.get("actionCommand");
      UUID action=UUID.fromString(command.get("actionId").toString());
      db.update("UPDATE guard.agent_task_outbox SET status='ACCEPTED' WHERE case_id=? AND payload->>'kind'='ANALYZE'",cs.getFirst());
      db.update("UPDATE guard.agent_task_outbox SET status='RETRY',delivery_uncertain=true WHERE payload->>'reviewId'=?",approved.toString());
      // 아직 Agent에 닿지 않은 분석 작업(1)과 전달이 불확실한 분석 작업(2)
      db.update("UPDATE guard.agent_task_outbox SET status='RETRY',last_error_code='AGENT_UNREACHABLE' WHERE case_id=? AND payload->>'kind'='ANALYZE'",cs.get(1));
      db.update("UPDATE guard.agent_task_outbox SET status='RETRY',delivery_uncertain=true WHERE case_id=? AND payload->>'kind'='ANALYZE'",cs.get(2));
      long findings=db.queryForObject("SELECT count(*) FROM guard.findings",Long.class);
      var ended=lives.end(live,moderator);
      assertEquals(2,db.queryForObject("SELECT count(*) FROM guard.reviews WHERE kind='KICK' AND status='INVALIDATED'",Integer.class));
      assertEquals(3,db.queryForObject("SELECT count(*) FROM guard.live_reservations WHERE state='CONSUMED'",Integer.class));
      assertEquals(3,db.queryForObject("SELECT count(*) FROM guard.live_reservations WHERE state='RELEASED'",Integer.class));
      assertEquals("PENDING",guard.reviewCard(rs.get(6)).get("status"));
      assertEquals("ACCEPTED",db.queryForObject("SELECT status FROM guard.agent_task_outbox WHERE case_id=? AND payload->>'kind'='ANALYZE'",String.class,cs.getFirst()));
      assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM guard.agent_task_outbox WHERE payload->>'reviewId'=?",String.class,approved.toString()));
      // 끝난 방송에는 판정을 낼 수 없으므로 닿지 않은 분석 작업은 멈추고, Agent가 받았을 수 있는 작업은 그대로 둔다
      assertEquals("FAILED:LIVE_ENDED",db.queryForObject("SELECT status||':'||last_error_code FROM guard.agent_task_outbox WHERE case_id=? AND payload->>'kind'='ANALYZE'",String.class,cs.get(1)));
      assertEquals("RETRY",db.queryForObject("SELECT status FROM guard.agent_task_outbox WHERE case_id=? AND payload->>'kind'='ANALYZE'",String.class,cs.get(2)));
      assertEquals(findings,db.queryForObject("SELECT count(*) FROM guard.findings",Long.class));
      assertEquals(0,queries.kickEligibility(cs.getFirst()).count());
      assertEquals("LIVE_ENDED",guard.actionStatus(action).get("errorCode"));
      assertEquals(false,guard.execute(action,command).get("applied"));
      assertEquals(ErrorCode.LIVE_ENDED,assertThrows(ApiException.class,()->guard.revalidate(approved,UUID.fromString(decision.get("decisionId").toString()))).code());
      int version=db.queryForObject("SELECT version FROM guard.reviews WHERE id=?",Integer.class,approved);
      guard.liveEnded(new com.lifeguards.liveguard.platform.port.LiveEndedEvent(live,channel,ended.endedAt()));
      assertEquals(version,db.queryForObject("SELECT version FROM guard.reviews WHERE id=?",Integer.class,approved));
      assertEquals(6,queries.userHistory(cs.get(6),null).violationCount());
    } finally {
      deleteCommitted();
    }
  }

  /** 트랜잭션 없이 돌린 시험이 커밋한 행을 지운다. */
  void deleteCommitted() {
    com.lifeguards.liveguard.support.CaseFixtures.deleteCases(db);
    db.update("DELETE FROM platform.messages WHERE channel_id=?",channel);
    db.update("DELETE FROM platform.lives WHERE channel_id=?",channel);
    db.update("DELETE FROM platform.channel_members WHERE channel_id=?",channel);
    db.update("DELETE FROM platform.channels WHERE id=?",channel);
    for(UUID id:List.of(user,operator,other))db.update("DELETE FROM platform.users WHERE id=?",id);
  }

  @Test
  void legacyChannelSendUsesSameLiveAndSanctions() throws Exception {
    var live=lives.start(channel,moderator);
    db.update("UPDATE platform.users SET password_hash=? WHERE id=?",passwordEncoder.encode("liveguard-local"),user);
    var session=com.lifeguards.liveguard.support.LoginSupport.login(mvc,user.toString());
    String path="/api/channels/"+channel+"/messages";
    mvc.perform(com.lifeguards.liveguard.support.LoginSupport.withXsrf(mvc,
      org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).session(session))
      .contentType("application/json").content("{\"clientMessageId\":\""+UUID.randomUUID()+"\",\"text\":\"hello\"}"))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.liveId").value(live.liveId().toString()));
    UUID c=candidate(live.liveId(),"너 꺼져");UUID r=proposal(c,"VIOLATION");apply(c,r,decide(r,"CONFIRM_VIOLATION",1));
    mvc.perform(com.lifeguards.liveguard.support.LoginSupport.withXsrf(mvc,
      org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).session(session))
      .contentType("application/json").content("{\"clientMessageId\":\""+UUID.randomUUID()+"\",\"text\":\"bypass\"}"))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("TIMED_OUT"));
  }

  UUID confirmedFinding(UUID c) {
    return db.queryForObject("SELECT id FROM guard.findings WHERE case_id=? AND source='BACKEND' AND status='CONFIRMED' ORDER BY created_at LIMIT 1",UUID.class,c);
  }
  com.lifeguards.liveguard.contracts.dto.request.RevokeFindingRequest revokeRequest(UUID c) {
    return new com.lifeguards.liveguard.contracts.dto.request.RevokeFindingRequest(UUID.randomUUID(),db.queryForObject("SELECT version FROM guard.cases WHERE id=?",Integer.class,c),"운영자가 문맥을 다시 확인하여 오판을 취소합니다.");
  }
  @Test void cancellationRestoresOnlyOwnHoldAndRecomputesLaterTimeoutHistory() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID first=candidate(live,"너 꺼져 첫번째"),secondCase=candidate(live,"너 꺼져 두번째");
    UUID r1=proposal(first,"VIOLATION"),r2=proposal(secondCase,"VIOLATION");
    apply(first,r1,decide(r1,"CONFIRM_VIOLATION",1));apply(secondCase,r2,decide(r2,"CONFIRM_VIOLATION",1));
    UUID f1=confirmedFinding(first);var request=revokeRequest(first);
    assertEquals(ErrorCode.FORBIDDEN,assertThrows(ApiException.class,()->recovery.revoke(f1,request,viewer)).code());
    var result=recovery.revoke(f1,request,moderator);assertEquals(result,recovery.revoke(f1,request,moderator));
    assertEquals("CANCELLED",result.get("confirmationStatus"));
    assertEquals("PUBLIC",db.queryForObject("SELECT visibility FROM platform.messages WHERE case_id=?",String.class,first));
    assertEquals(List.of(600L),timeoutSeconds(secondCase));
    UUID message=db.queryForObject("SELECT id FROM platform.messages WHERE case_id=?",UUID.class,secondCase);
    db.update("INSERT INTO platform.message_hold_reasons(message_id,case_id,reason,created_at) VALUES(?,?,'OTHER',current_timestamp)",message,UUID.randomUUID());
    recovery.revoke(confirmedFinding(secondCase),revokeRequest(secondCase),moderator);
    assertEquals("HIDDEN",db.queryForObject("SELECT visibility FROM platform.messages WHERE id=?",String.class,message));
    assertDoesNotThrow(()->lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),"restriction restored")));
  }
  @Test void floodBurstCountsOneCaseAndRecurrenceIsLiveScoped() {
    UUID live=lives.start(channel,moderator).liveId();
    for(int i=0;i<4;i++)lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),"같은 응원"));
    assertEquals(1,db.queryForObject("SELECT count(*) FROM guard.cases WHERE live_id=?",Integer.class,live));
    assertEquals(2,db.queryForObject("SELECT count(*) FROM platform.messages WHERE live_id=? AND visibility='PUBLIC'",Integer.class,live));
    assertEquals(2,db.queryForObject("SELECT count(*) FROM platform.messages WHERE live_id=? AND visibility='HIDDEN'",Integer.class,live));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.live_timeouts WHERE live_id=?",Integer.class,live));
    db.update("UPDATE guard.flood_marks SET last_sent_at=current_timestamp-interval '11 seconds' WHERE live_id=?",live);
    db.update("UPDATE platform.messages SET sent_at=current_timestamp-interval '11 seconds' WHERE live_id=?",live);
    var recurring=lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),"같은 응원"));
    assertEquals("HIDDEN",recurring.get("visibility"));
    assertEquals(2,db.queryForObject("SELECT count(*) FROM guard.cases WHERE live_id=?",Integer.class,live));
    assertEquals(ErrorCode.TIMED_OUT,assertThrows(ApiException.class,()->lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),"new"))).code());
    lives.end(live,moderator);UUID next=lives.start(channel,moderator).liveId();
    assertEquals("PUBLIC",lives.send(next,viewer,new SendMessageRequest(UUID.randomUUID(),"같은 응원")).get("visibility"));
  }
  /**
   * 증거가 멀리 떨어진 FLOOD 사건: snapshot은 증거 ±10만 담지만 Agent 문맥은 전체를 담았을 때와 같다.
   * 메시지 순서 r1, r2, 다른 사용자 o0~o24, m(세 번째 같은 문장). 증거는 r1·r2·m.
   */
  @Test void farApartEvidenceKeepsTheSameAgentContextWithABoundedSnapshot() {
    UUID live=lives.start(channel,moderator).liveId();
    List<UUID> order=new ArrayList<>();
    for(int i=0;i<2;i++)order.add(id(lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),"같은 문장")).get("messageId")));
    for(int i=0;i<25;i++)order.add(id(lives.send(live,second,new SendMessageRequest(UUID.randomUUID(),"응원 "+i)).get("messageId")));
    order.add(id(lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),"같은 문장")).get("messageId")));
    UUID c=db.queryForObject("SELECT case_id FROM platform.messages WHERE id=?",UUID.class,order.getLast());

    // 증거(0, 1, 27번)에서 10건 넘게 떨어진 12~16번만 snapshot에서 빠진다
    List<UUID> kept=new ArrayList<>(order);kept.removeAll(order.subList(12,17));
    assertEquals(kept,guard.internalCase(c).messages().stream().map(m->m.messageId()).toList());
    // 문맥은 전체 28건 기준과 같다: 증거 3건 + 증거에 가까운 17건(2~10번, 19~26번), truncated
    List<UUID> expected=new ArrayList<>(order.subList(0,11));expected.addAll(order.subList(19,28));
    var context=guard.internalContext(c,10,10);
    assertEquals(expected,context.messages().stream().map(m->m.messageId()).toList());
    assertTrue(context.truncated());
  }

  static UUID id(Object value) { return UUID.fromString(value.toString()); }

  /** 욕설 확정 2건 뒤 같은 문장 3회로 도배 사건(세 번째 확정)을 만들어 강퇴 분석이 큐에 들어간 상태로 둔다. */
  UUID floodCaseAtKickThreshold(UUID live) {
    for(String text:List.of("씨발","씨발 진짜")) {
      lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),text));
      db.update("DELETE FROM guard.live_timeouts WHERE live_id=?",live);
    }
    UUID last=null;
    for(int i=0;i<3;i++)last=id(lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),"같은 문장")).get("messageId"));
    return db.queryForObject("SELECT case_id FROM platform.messages WHERE id=?",UUID.class,last);
  }

  UUID taskOf(UUID c) { return db.queryForObject("SELECT task_id FROM guard.live_case_state WHERE case_id=?",UUID.class,c); }

  @Test void floodEvidenceAppendReanalyzesThePendingKickAnalysisWithANewTask() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID flood=floodCaseAtKickThreshold(live);
    UUID before=taskOf(flood);
    assertEquals("PENDING",db.queryForObject("SELECT status FROM guard.agent_task_outbox WHERE task_id=?",String.class,before));

    lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),"같은 문장"));

    // 결정 전 강퇴 분석은 새 작업·새 snapshot으로 다시 넣고, 보내지 않은 이전 작업은 멈춘다
    UUID after=taskOf(flood);
    assertNotEquals(before,after);
    assertEquals("case:"+flood+":analysis:2",db.queryForObject("SELECT thread_id FROM guard.live_case_state WHERE case_id=?",String.class,flood));
    assertEquals(List.of("FAILED","SUPERSEDED"),db.queryForObject("SELECT ARRAY[status,last_error_code] FROM guard.agent_task_outbox WHERE task_id=?",
      (rs,n)->List.of((Object[])rs.getArray(1).getArray()),before));
    var payload=db.queryForMap("SELECT payload->>'kind' AS kind, payload->>'snapshotId' AS snapshot, payload->>'expectedEvidenceVersion' AS evidence FROM guard.agent_task_outbox WHERE task_id=?",after);
    assertEquals("REANALYZE",payload.get("kind"));
    assertEquals(db.queryForObject("SELECT snapshot_id::text FROM guard.cases WHERE id=?",String.class,flood),payload.get("snapshot"));
    assertEquals("2",payload.get("evidence"));
    assertEnvelopeLiveScope(flood, live, "ANALYZE", "REANALYZE");
  }

  /**
   * 이전 분석이 이미 Agent에 전달된(ACCEPTED, 결정 대기일 수 있음) 뒤 증거가 추가되면 그 작업은 그대로 두고 새 작업으로
   * 다시 분석한다. 이후 결정의 재개 작업은 새 task·thread와 현재 snapshot으로 나가 Agent의 thread 바인딩과 맞는다.
   */
  @Test void deliveredAnalysisIsReanalyzedAndTheLaterResumeTargetsTheNewThread() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID flood=floodCaseAtKickThreshold(live);
    UUID before=taskOf(flood);
    db.update("UPDATE guard.agent_task_outbox SET status='ACCEPTED' WHERE task_id=?",before);

    lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),"같은 문장"));

    UUID after=taskOf(flood);
    assertNotEquals(before,after);
    assertEquals("ACCEPTED",db.queryForObject("SELECT status FROM guard.agent_task_outbox WHERE task_id=?",String.class,before));
    UUID review=db.queryForObject("SELECT id FROM guard.reviews WHERE case_id=? AND kind='KICK'",UUID.class,flood);
    decide(review,"APPROVE",1);
    var resume=db.queryForMap("SELECT task_id, payload->>'threadId' AS thread, payload->>'snapshotId' AS snapshot"
      +" FROM guard.agent_task_outbox WHERE case_id=? AND payload->>'kind'='RESUME_REVIEW'",flood);
    assertEquals(after,resume.get("task_id"));
    assertEquals("case:"+flood+":analysis:2",resume.get("thread"));
    assertEquals(db.queryForObject("SELECT snapshot_id::text FROM guard.cases WHERE id=?",String.class,flood),resume.get("snapshot"));
    assertEnvelopeLiveScope(flood, live, "ANALYZE", "REANALYZE", "RESUME_REVIEW");
  }

  /** 전달이 불확실했던 이전 작업은 Agent가 받았을 수 있으므로 FAILED가 아니라 UNKNOWN으로 멈춘다. */
  @Test void uncertainPreviousDeliveryIsMarkedUnknownWhenSuperseded() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID flood=floodCaseAtKickThreshold(live);
    UUID before=taskOf(flood);
    db.update("UPDATE guard.agent_task_outbox SET status='RETRY',delivery_uncertain=true WHERE task_id=?",before);

    lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),"같은 문장"));

    assertEquals(List.of("UNKNOWN","SUPERSEDED"),db.queryForObject("SELECT ARRAY[status,last_error_code] FROM guard.agent_task_outbox WHERE task_id=?",
      (rs,n)->List.of((Object[])rs.getArray(1).getArray()),before));
  }

  @Test void decidedKickReviewIsNotReanalyzedWhenFloodEvidenceIsAdded() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID flood=floodCaseAtKickThreshold(live);
    UUID before=taskOf(flood);
    UUID review=db.queryForObject("SELECT id FROM guard.reviews WHERE case_id=? AND kind='KICK'",UUID.class,flood);
    decide(review,"REJECT",1);

    lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),"같은 문장"));

    // 결정된 강퇴 판단의 근거(사건 3건)는 바뀌지 않으므로 다시 분석하지 않는다
    assertEquals(before,taskOf(flood));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.agent_task_outbox WHERE case_id=? AND payload->>'kind'='REANALYZE'",Integer.class,flood));
  }

  @Test void independentKickReasonsSurviveCancellationOfAnotherReview() {
    UUID live=lives.start(channel,moderator).liveId();List<UUID> cs=new ArrayList<>(),rs=new ArrayList<>();
    for(int i=0;i<6;i++){UUID c=candidate(live,"너 꺼져 별도사건 "+i);cs.add(c);rs.add(proposal(c,"VIOLATION"));}
    List<UUID> kicks=new ArrayList<>();
    for(int i=0;i<6;i++){var result=apply(cs.get(i),rs.get(i),decide(rs.get(i),"CONFIRM_VIOLATION",1));var k=(Map<String,Object>)result.get("kickReview");if(Boolean.TRUE.equals(k.get("required")))kicks.add(UUID.fromString(k.get("reviewId").toString()));}
    assertEquals(2,kicks.size());
    for(UUID review:kicks){var d=decide(review,"APPROVE",1);var command=(Map<String,Object>)guard.revalidate(review,UUID.fromString(d.get("decisionId").toString())).get("actionCommand");guard.execute(UUID.fromString(command.get("actionId").toString()),command);}
    assertEquals(2,db.queryForObject("SELECT count(*) FROM guard.live_kicks WHERE live_id=?",Integer.class,live));
    recovery.revoke(confirmedFinding(cs.getFirst()),revokeRequest(cs.getFirst()),moderator);
    assertEquals(1,db.queryForObject("SELECT count(*) FROM guard.live_kicks WHERE live_id=?",Integer.class,live));
    assertEquals(ErrorCode.KICKED,assertThrows(ApiException.class,()->lives.history(live,viewer)).code());
  }
  @Test void releasedPendingReservationsCanBeSafelyReservedByAnotherReview() {
    UUID live=lives.start(channel,moderator).liveId();List<UUID> cs=new ArrayList<>(),rs=new ArrayList<>();
    for(int i=0;i<4;i++){UUID c=candidate(live,"너 꺼져 예약 "+i);cs.add(c);rs.add(proposal(c,"VIOLATION"));}
    for(int i=0;i<3;i++)apply(cs.get(i),rs.get(i),decide(rs.get(i),"CONFIRM_VIOLATION",1));
    recovery.revoke(confirmedFinding(cs.getFirst()),revokeRequest(cs.getFirst()),moderator);
    var result=apply(cs.get(3),rs.get(3),decide(rs.get(3),"CONFIRM_VIOLATION",1));
    assertEquals(true,((Map<String,Object>)result.get("kickReview")).get("required"));
    assertEquals(3,db.queryForObject("SELECT count(*) FROM guard.live_reservations WHERE state='RESERVED'",Integer.class));
  }

  @Test void streamerLifecycleDoesNotGrantPrivateModerationAccessAndHistoryPaginates() {
    UUID live=lives.start(channel,moderator).liveId();
    db.update("UPDATE platform.channel_members SET member_role='STREAMER' WHERE channel_id=? AND user_id=?",channel,other);
    UUID c=candidate(live,"너 꺼져");
    assertEquals(ErrorCode.FORBIDDEN,assertThrows(ApiException.class,()->guard.reviews(live,second)).code());
    assertEquals(0,((List<?>)lives.history(live,second).get("items")).size());
    for(int i=0;i<12;i++)lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),"인사 "+i));
    var first=lives.history(live,second,null,5);
    assertEquals(5,((List<?>)first.get("items")).size());assertNotNull(first.get("nextCursor"));
    var next=lives.history(live,second,first.get("nextCursor").toString(),5);
    assertEquals(5,((List<?>)next.get("items")).size());
    assertDoesNotThrow(()->lives.end(live,second));
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
  void differentBroadcastsProduceDistinctSchedulingScopes() {
    UUID first = lives.start(channel, moderator).liveId();
    UUID firstCase = candidate(first, "너 어디 꺼져");
    lives.end(first, moderator);
    UUID next = lives.start(channel, moderator).liveId();
    UUID nextCase = candidate(next, "너 어디 꺼져");
    assertEnvelopeLiveScope(firstCase, first, "ANALYZE");
    assertEnvelopeLiveScope(nextCase, next, "ANALYZE");
    assertNotEquals(first, next);
  }

  void assertEnvelopeLiveScope(UUID caseId, UUID live, String... kinds) {
    assertEquals(Set.of(kinds), new HashSet<>(db.queryForList(
        "SELECT DISTINCT payload->>'kind' FROM guard.agent_task_outbox WHERE case_id=?", String.class, caseId)));
    assertEquals(0, db.queryForObject(
        "SELECT count(*) FROM guard.agent_task_outbox WHERE case_id=? AND (payload->>'liveId') IS DISTINCT FROM ?",
        Integer.class, caseId, live.toString()));
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
    assertEnvelopeLiveScope(c, live, "ANALYZE", "RESUME_REVIEW");
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
  void maskedContextUncertaintyAndOtherHoldReasonPreventsRelease() {
    UUID live = lives.start(channel, moderator).liveId();
    UUID c = candidate(live, "꺼져 연락 010-1234-5678");
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
  void issuedKickCommandRejectsRevokedOperatorAuthority() {
    UUID live=lives.start(channel,moderator).liveId();List<UUID> cs=new ArrayList<>(),rs=new ArrayList<>();
    for(int i=0;i<3;i++){UUID c=candidate(live,"너 꺼져 권한 확인 "+i);cs.add(c);rs.add(proposal(c,"VIOLATION"));}
    Map<String,Object> result=Map.of();for(int i=0;i<3;i++)result=apply(cs.get(i),rs.get(i),decide(rs.get(i),"CONFIRM_VIOLATION",1));
    UUID r=UUID.fromString(((Map<?,?>)result.get("kickReview")).get("reviewId").toString());
    UUID d=UUID.fromString(decide(r,"APPROVE",1).get("decisionId").toString());
    var command=(Map<String,Object>)guard.revalidate(r,d).get("actionCommand");
    db.update("UPDATE platform.channel_members SET member_role='VIEWER' WHERE channel_id=? AND user_id=?",channel,operator);
    assertEquals(ErrorCode.FORBIDDEN,assertThrows(ApiException.class,()->guard.execute(UUID.fromString(command.get("actionId").toString()),command)).code());
    assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.live_kicks WHERE live_id=?",Integer.class,live));
  }

  @Test
  void legacyReplaySurvivesEndAndHistoryKeepsPriorBroadcast() {
    UUID live=lives.start(channel,moderator).liveId();UUID request=UUID.randomUUID();
    var first=lives.sendToChannel(channel,viewer,new SendMessageRequest(request,"legacy replay"));
    lives.end(live,moderator);
    assertEquals(first,lives.sendToChannel(channel,viewer,new SendMessageRequest(request,"legacy replay")));
    assertEquals(ErrorCode.LIVE_ENDED,assertThrows(ApiException.class,()->lives.sendToChannel(channel,viewer,new SendMessageRequest(UUID.randomUUID(),"fresh"))).code());
  }

  @Test
  void replayIsNotBlockedByTheTimeoutItsOwnMessageStarted() {
    UUID live=lives.start(channel,moderator).liveId();UUID request=UUID.randomUUID();
    var first=lives.send(live,viewer,new SendMessageRequest(request,"여기 https://evil.test 접속"));
    // 첫 링크 위반에 PHISHING 타임아웃이 걸린다. 응답을 잃고 같은 요청을 다시 보내면 저장된 메시지를 돌려준다(A-18)
    assertEquals("HIDDEN",first.get("visibility"));
    assertEquals(first.get("messageId"),lives.send(live,viewer,new SendMessageRequest(request,"여기 https://evil.test 접속")).get("messageId"));
    assertEquals(first.get("messageId"),lives.sendToChannel(channel,viewer,new SendMessageRequest(request,"여기 https://evil.test 접속")).get("messageId"));
    assertEquals(ErrorCode.TIMED_OUT,assertThrows(ApiException.class,()->lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),"new"))).code());
  }

  org.springframework.test.web.servlet.ResultActions listCases(org.springframework.mock.web.MockHttpSession session, UUID live, String query) throws Exception {
    return mvc.perform(get("/api/cases?liveId="+live+query).session(session));
  }

  @Test void caseListIsTheSameAsEachCaseDetail() {
    UUID live=lives.start(channel,moderator).liveId();
    candidate(live,"너 꺼져 판정 대기");
    UUID judged=candidate(live,"너 꺼져 판정 받음");proposal(judged,"NEEDS_REVIEW");
    // 확정 위반 3건(강퇴 검토 대기 포함), 오판 취소, 다른 시청자의 사건을 섞는다
    for(int i=0;i<3;i++){UUID c=candidate(live,"너 꺼져 누적 "+i);UUID r=proposal(c,"VIOLATION");apply(c,r,decide(r,"CONFIRM_VIOLATION",1));
      db.update("UPDATE guard.live_timeouts SET revoked_at=current_timestamp WHERE live_id=?",live);}
    UUID revoked=candidate(live,"씨발 취소");
    recovery.revoke(confirmedFinding(revoked),revokeRequest(revoked),moderator);
    db.update("UPDATE guard.live_timeouts SET revoked_at=current_timestamp WHERE live_id=?",live);
    lives.send(live,second,new SendMessageRequest(UUID.randomUUID(),"너 꺼져 다른 시청자"));
    // 같은 문장 세 번째에 도배 사건(관련 메시지 2건 포함)
    for(int i=0;i<3;i++)lives.send(live,second,new SendMessageRequest(UUID.randomUUID(),"같은 말 반복"));
    var listed=guard.cases(live,null,moderator);
    assertEquals(8,listed.size());
    assertTrue(listed.stream().anyMatch(c->Boolean.TRUE.equals(((Map<?,?>)c.get("kickReview")).get("required"))));
    assertTrue(listed.stream().anyMatch(c->c.get("handlingRoute").equals("FLOOD_REVIEW")));
    // 목록은 사건마다 detail()을 부르지 않고 모아 읽지만 결과는 같아야 한다
    for(var item:listed)
      assertEquals(guard.detail(UUID.fromString(item.get("caseId").toString()),moderator),item);
  }

  @Test
  void caseListFiltersByConfirmationStatus() throws Exception {
    UUID live=lives.start(channel,moderator).liveId();
    UUID pending=candidate(live,"너 꺼져 확인 대기");
    UUID violation=candidate(live,"씨발 확정");
    UUID normal=candidate(live,"너 꺼져 정상 확정");
    UUID r=proposal(normal,"NO_VIOLATION");apply(normal,r,decide(r,"CONFIRM_NORMAL",1));
    UUID cancelled=candidate(live,"씨발 취소될 확정");
    recovery.revoke(confirmedFinding(cancelled),revokeRequest(cancelled),moderator);
    db.update("UPDATE platform.users SET password_hash=? WHERE id=?",passwordEncoder.encode("liveguard-local"),operator);
    var session=com.lifeguards.liveguard.support.LoginSupport.login(mvc,operator.toString());

    // 목록 항목은 사건 상세(CaseDetail)다. 운영 화면이 쓰는 대상 메시지·AI 제안·확정 위반·누적 수가 있다
    var all=listCases(session,live,"").andExpect(jsonPath("$.data.length()").value(4));
    // 항목마다(4개 모두) 필수 필드가 있다
    for(String field:List.of("targetMessageIds","findings","confirmedFindings","violationCount","kickReview"))
      all.andExpect(jsonPath("$.data[*]."+field,org.hamcrest.Matchers.hasSize(4)));
    UUID pendingMessage=db.queryForObject("SELECT id FROM platform.messages WHERE case_id=?",UUID.class,pending);
    all.andExpect(jsonPath("$.data[?(@.caseId=='"+pending+"')].targetMessageIds[0]").value(pendingMessage.toString()))
      .andExpect(jsonPath("$.data[?(@.caseId=='"+pending+"')].findings[*]",org.hamcrest.Matchers.empty()))
      .andExpect(jsonPath("$.data[?(@.caseId=='"+normal+"')].findings[0].verdict").value("NO_VIOLATION"))
      .andExpect(jsonPath("$.data[?(@.caseId=='"+pending+"')].kickReview.required").value(false));
    for(var expected:List.of(List.of("PENDING",pending),List.of("CONFIRMED_VIOLATION",violation),
        List.of("CONFIRMED_NORMAL",normal),List.of("CANCELLED",cancelled)))
      listCases(session,live,"&confirmationStatus="+expected.get(0))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].caseId").value(expected.get(1).toString()))
        .andExpect(jsonPath("$.data[0].confirmationStatus").value(expected.get(0)));
    // enum에 없는 값은 400 VALIDATION_FAILED
    listCases(session,live,"&confirmationStatus=WHATEVER")
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
  }

  @Test
  void kickedViewerCannotReadOrSubscribeThroughLegacyChannelPaths() throws Exception {
    UUID live=lives.start(channel,moderator).liveId();
    kick(live);
    db.update("UPDATE platform.users SET password_hash=? WHERE id=?",passwordEncoder.encode("liveguard-local"),user);
    var session=com.lifeguards.liveguard.support.LoginSupport.login(mvc,user.toString());
    // POLICY §10: 방송 종료까지 재입장과 채팅 금지는 기존 채널 경로에도 같다. 방송 종료까지라 until은 없다
    for(String path:List.of("/api/channels/"+channel+"/messages","/api/channels/"+channel+"/events"))
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).session(session)
          .accept("application/json","text/event-stream"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("KICKED"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.until").doesNotExist());
    lives.end(live,moderator);
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/channels/"+channel+"/messages").session(session))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  }

  /** 확정 사건 3건 → 강퇴 승인 → 재검증 → 실행으로 viewer를 이 방송에서 강퇴한다. */
  void kick(UUID live) {
    List<UUID> cases=new ArrayList<>(),reviews=new ArrayList<>();
    for(int i=0;i<3;i++){UUID c=candidate(live,"꺼져 강퇴 "+i);cases.add(c);reviews.add(proposal(c,"VIOLATION"));}
    Map<String,Object> applied=Map.of();
    for(int i=0;i<3;i++)applied=apply(cases.get(i),reviews.get(i),decide(reviews.get(i),"CONFIRM_VIOLATION",1));
    UUID review=UUID.fromString(((Map<String,Object>)applied.get("kickReview")).get("reviewId").toString());
    UUID d=UUID.fromString(decide(review,"APPROVE",1).get("decisionId").toString());
    var command=(Map<String,Object>)guard.revalidate(review,d).get("actionCommand");
    assertEquals("SUCCEEDED",guard.execute(UUID.fromString(command.get("actionId").toString()),command).get("status"));
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
    recovery.revoke(confirmedFinding(cases.getFirst()),revokeRequest(cases.getFirst()),moderator);
    assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.live_kicks",Integer.class));
    assertEquals(3,db.queryForObject("SELECT count(*) FROM guard.live_reservations WHERE state='CONSUMED'",Integer.class));
    assertDoesNotThrow(()->lives.history(live,viewer));
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

  // ---- AI 판정 없는 문맥 검토(A-25) ----

  UUID contextReview(UUID c) {
    return db.queryForObject("SELECT id FROM guard.reviews WHERE case_id=? AND kind='CONTEXT'", UUID.class, c);
  }

  String visibilityOf(UUID c) {
    return db.queryForObject("SELECT visibility FROM platform.messages WHERE case_id=?", String.class, c);
  }

  String analysisOutbox(UUID c) {
    return db.queryForObject("SELECT status||':'||coalesce(last_error_code,'-') FROM guard.agent_task_outbox"
        + " WHERE case_id=? AND payload->>'kind'='ANALYZE'", String.class, c);
  }

  int resumeTasks(UUID c) {
    return db.queryForObject("SELECT count(*) FROM guard.agent_task_outbox WHERE case_id=? AND payload->>'kind'='RESUME_REVIEW'",
        Integer.class, c);
  }

  @Test void judgmentDeadlineOpensReviewWithoutAiAndOperatorDecisionAppliesDirectly() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID c=candidate(live,"너 꺼져 판정 없음");
    // 마감 전에는 열지 않는다
    assertFalse(guard.casesAwaitingJudgment(java.time.Duration.ofHours(1),50).contains(c));
    assertFalse(guard.openReviewWithoutJudgment(c,java.time.Duration.ofHours(1)));
    assertTrue(guard.casesAwaitingJudgment(java.time.Duration.ZERO,50).contains(c));
    assertTrue(guard.openReviewWithoutJudgment(c,java.time.Duration.ZERO));
    assertFalse(guard.openReviewWithoutJudgment(c,java.time.Duration.ZERO));
    assertFalse(guard.casesAwaitingJudgment(java.time.Duration.ZERO,50).contains(c));

    UUID r=contextReview(c);
    var card=guard.reviewCard(r);
    // 계약 스키마는 그대로다: aiVerdict는 NEEDS_REVIEW, findings는 비어 있고 안내 문구로 알린다
    assertEquals("NEEDS_REVIEW",card.get("aiVerdict"));
    assertEquals(List.of(),card.get("findings"));
    assertTrue(card.get("summary").toString().startsWith("AI 분석 결과를 받지 못했습니다"));
    assertEquals("PENDING",card.get("status"));
    assertEquals("HELD",visibilityOf(c));

    decide(r,"CONFIRM_NORMAL",1);
    // Agent 재개 없이 BE가 바로 적용한다. 아직 보내지 않은 분석은 멈춘다
    assertEquals("PUBLIC",visibilityOf(c));
    assertEquals("CONFIRMED_NORMAL",db.queryForObject("SELECT confirmation_status FROM guard.live_case_state WHERE case_id=?",String.class,c));
    assertEquals(0,resumeTasks(c));
    assertEquals("FAILED:DECIDED_WITHOUT_JUDGMENT",analysisOutbox(c));
    // 결정 뒤에 온 판정은 받지 않는다
    assertEquals(ErrorCode.STALE_VERSION,assertThrows(ApiException.class,()->proposal(c,"VIOLATION")).code());
    assertNull(db.queryForObject("SELECT judgment::text FROM guard.live_case_state WHERE case_id=?",String.class,c));
  }

  @Test void violationDecisionWithoutAiConfirmsDirectly() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID c=candidate(live,"너 꺼져 판정 없는 위반");
    assertTrue(guard.openReviewWithoutJudgment(c,java.time.Duration.ZERO));
    decide(contextReview(c),"CONFIRM_VIOLATION",1);
    assertEquals("HIDDEN",visibilityOf(c));
    assertEquals("CONFIRMED_VIOLATION",db.queryForObject("SELECT confirmation_status FROM guard.live_case_state WHERE case_id=?",String.class,c));
    assertEquals(1,db.queryForObject("SELECT count(*) FROM guard.findings WHERE case_id=? AND status='CONFIRMED' AND category='HARASSMENT'",Integer.class,c));
    assertEquals(0,resumeTasks(c));
  }

  @Test void deferWithoutAiKeepsReviewPendingAndAppliesNothing() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID c=candidate(live,"너 꺼져 보류");
    assertTrue(guard.openReviewWithoutJudgment(c,java.time.Duration.ZERO));
    decide(contextReview(c),"DEFER",1);
    assertEquals("PENDING",guard.reviewCard(contextReview(c)).get("status"));
    assertEquals("HELD",visibilityOf(c));
    assertEquals(0,resumeTasks(c));
    assertEquals("PENDING:-",analysisOutbox(c));
  }

  @Test void lateJudgmentAttachesToOpenReviewAndDecisionGoesThroughAgent() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID c=candidate(live,"너 꺼져 늦은 판정");
    assertTrue(guard.openReviewWithoutJudgment(c,java.time.Duration.ZERO));
    UUID r=contextReview(c);
    // 결정 전에 온 판정은 같은 검토에 AI 제안으로 붙는다
    assertEquals(r,proposal(c,"NO_VIOLATION"));
    var card=guard.reviewCard(r);
    assertEquals("NO_VIOLATION",card.get("aiVerdict"));
    assertEquals(1,((List<?>)card.get("findings")).size());
    assertEquals("AI 제안을 확인하고 최종 결정해 주세요",card.get("summary"));
    // AI 제안이 생겼으므로 검토 버전이 오른다. 제안을 보기 전 버전으로 낸 결정은 받지 않는다
    assertEquals(2,card.get("version"));
    assertEquals(ErrorCode.STALE_VERSION,assertThrows(ApiException.class,()->decide(r,"CONFIRM_NORMAL",1)).code());
    // 판정이 붙었으므로(Agent가 결정을 기다림) 결정은 기존대로 Agent가 재개해 적용한다
    var d=decide(r,"CONFIRM_NORMAL",2);
    assertEquals(1,resumeTasks(c));
    assertEquals("HELD",visibilityOf(c));
    apply(c,r,d);
    assertEquals("PUBLIC",visibilityOf(c));
  }

  @Test void lateDefiniteJudgmentOnPiiCaseIsRejectedWhileReviewIsOpen() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID c=candidate(live,"연락 010-2222-3333 판정 없음");
    assertEquals("PII_INVESTIGATION",guard.snapshot(c).get("handlingRoute"));
    assertTrue(guard.openReviewWithoutJudgment(c,java.time.Duration.ZERO));
    // 평소에는 개인정보 경로의 NO_VIOLATION이 바로 공개하지만, 사람 검토가 열려 있으면 받지 않는다(Agent는 이 판정에
    // 문맥 검토 응답을 받지 않아 결정을 기다리지 않으므로 붙여도 재개할 수 없다). 운영자 결정은 BE가 바로 적용한다
    assertEquals(ErrorCode.STALE_VERSION,assertThrows(ApiException.class,()->proposal(c,"NO_VIOLATION")).code());
    // 확정 위반 판정도 같다(v3.2는 검증 출처 없이도 받지만 열린 사람 검토를 덮지 않는다)
    assertEquals(ErrorCode.STALE_VERSION,assertThrows(ApiException.class,()->guard.judgment(c,piiViolation(c))).code());
    assertEquals("HELD",visibilityOf(c));
    assertNull(db.queryForObject("SELECT judgment::text FROM guard.live_case_state WHERE case_id=?",String.class,c));
    // NEEDS_REVIEW는 Agent가 결정을 기다리므로 붙는다
    assertEquals(contextReview(c),proposal(c,"NEEDS_REVIEW"));
    assertEquals("PENDING",db.queryForObject("SELECT confirmation_status FROM guard.live_case_state WHERE case_id=?",String.class,c));
  }

  @Test void ruleConfirmedKickReviewCardStillShowsViolation() {
    UUID live=lives.start(channel,moderator).liveId();
    for(String text:List.of("씨발 하나","씨발 둘","씨발 셋")) {
      candidate(live,text);
      // 두 번째부터 채팅 제한이 걸리므로 다음 전송을 위해 해제한다(강퇴 집계는 확정 사건 수로 한다)
      db.update("UPDATE guard.live_timeouts SET revoked_at=current_timestamp WHERE live_id=?",live);
    }
    UUID kick=db.queryForObject("SELECT r.id FROM guard.reviews r JOIN guard.cases c ON c.id=r.case_id WHERE c.live_id=? AND r.kind='KICK'",UUID.class,live);
    // 규칙 확정 사건은 판정이 없지만 강퇴 카드는 확정 위반을 근거로 한다
    assertEquals("VIOLATION",guard.reviewCard(kick).get("aiVerdict"));
  }

  @Test void kickReviewCardShowsViolationEvenWhenOperatorOverrodeAiNormalProposal() {
    UUID live=lives.start(channel,moderator).liveId();
    for(int i=0;i<2;i++) {
      UUID c=candidate(live,"너 꺼져 뒤집기 전 "+i);UUID r=proposal(c,"VIOLATION");apply(c,r,decide(r,"CONFIRM_VIOLATION",1));
      db.update("UPDATE guard.live_timeouts SET revoked_at=current_timestamp WHERE live_id=?",live);
    }
    // 세 번째 사건은 AI가 정상으로 제안했지만 운영자가 위반으로 확정한다
    UUID c=candidate(live,"너 꺼져 뒤집은 세 번째");UUID r=proposal(c,"NO_VIOLATION");
    var applied=apply(c,r,decide(r,"CONFIRM_VIOLATION",1));
    UUID kick=UUID.fromString(((Map<?,?>)applied.get("kickReview")).get("reviewId").toString());
    var card=guard.reviewCard(kick);
    assertEquals(c,card.get("caseId"));
    assertTrue(((List<?>)card.get("basisCaseIds")).stream().map(Object::toString).toList().contains(c.toString()));
    assertEquals("VIOLATION",card.get("aiVerdict"));
    // findings는 그 사건의 AI 제안 그대로다(계약 KickReviewCard.findings)
    assertEquals(List.of("NO_VIOLATION"),((List<Map<String,Object>>)card.get("findings")).stream().map(f->f.get("verdict")).toList());
    // 문맥 검토 카드는 그대로 AI 제안을 보여 준다
    assertEquals("NO_VIOLATION",guard.reviewCard(r).get("aiVerdict"));
  }

  @Test void kickReviewCreatedOnANormalDecisionShowsTheLatestBasisCase() {
    UUID live=lives.start(channel,moderator).liveId();
    List<UUID> cs=new ArrayList<>(),rs=new ArrayList<>();
    for(int i=0;i<5;i++){UUID c=candidate(live,"너 꺼져 카드 근거 "+i);cs.add(c);rs.add(proposal(c,i<4?"VIOLATION":"NO_VIOLATION"));}
    for(int i=0;i<4;i++)apply(cs.get(i),rs.get(i),decide(rs.get(i),"CONFIRM_VIOLATION",1));
    // A·B·C로 강퇴 검토가 C에 생기고 D는 예약되지 않는다. A를 오판 취소하면 검토가 무효가 되고 B·C 예약이 풀린다
    recovery.revoke(confirmedFinding(cs.get(0)),revokeRequest(cs.get(0)),moderator);
    // 다음 결정(E를 정상 확정)에서 B·C·D로 강퇴 검토가 E에 생긴다
    var applied=apply(cs.get(4),rs.get(4),decide(rs.get(4),"CONFIRM_NORMAL",1));
    UUID kick=UUID.fromString(((Map<?,?>)applied.get("kickReview")).get("reviewId").toString());
    var card=guard.reviewCard(kick);
    assertEquals(cs.get(4),card.get("caseId"));
    var basis=((List<?>)card.get("basisCaseIds")).stream().map(Object::toString).toList();
    assertEquals(java.util.Set.of(cs.get(1).toString(),cs.get(2).toString(),cs.get(3).toString()),java.util.Set.copyOf(basis));
    // 증거는 정상 사건 E가 아니라 가장 늦게 확정된 기준 사건(보통 D)의 것이다
    UUID shown=UUID.fromString(basis.getLast());
    UUID shownMessage=db.queryForObject("SELECT id FROM platform.messages WHERE case_id=?",UUID.class,shown);
    assertEquals(List.of(shownMessage.toString()),((List<?>)card.get("targetMessageIds")).stream().map(Object::toString).toList());
    assertNotEquals(db.queryForObject("SELECT id FROM platform.messages WHERE case_id=?",UUID.class,cs.get(4)),shownMessage);
    assertEquals(List.of("VIOLATION"),((List<Map<String,Object>>)card.get("findings")).stream().map(f->f.get("verdict")).toList());
    assertEquals(db.queryForObject("SELECT evidence_version FROM guard.cases WHERE id=?",Integer.class,shown),card.get("evidenceVersion"));
  }

  @Test void contextReviewVerdictAggregatesAllTargetFindings() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID c=candidate(live,"너 꺼져 여러 대상");
    UUID r=proposal(c,"NO_VIOLATION");
    // 대상이 여럿인 판정: 첫 판정만 보지 않고 모은다(NEEDS_REVIEW > VIOLATION > NO_VIOLATION)
    for(var verdicts:List.of(List.of("NO_VIOLATION","VIOLATION"),List.of("NO_VIOLATION","NEEDS_REVIEW"),List.of("VIOLATION","NEEDS_REVIEW"))) {
      String findings=verdicts.stream().map(v->"{\"messageId\":\""+UUID.randomUUID()+"\",\"verdict\":\""+v+"\"}")
          .collect(java.util.stream.Collectors.joining(",","[","]"));
      db.update("UPDATE guard.live_case_state SET judgment=jsonb_build_object('messageFindings',?::jsonb) WHERE case_id=?",findings,c);
      assertEquals(verdicts.contains("NEEDS_REVIEW")?"NEEDS_REVIEW":"VIOLATION",guard.reviewCard(r).get("aiVerdict"));
    }
  }

  @Test void rejectedAnalysisOpensReviewBeforeTheDeadlineButLiveEndAndSupersedeDoNot() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID rejected=candidate(live,"너 꺼져 거절된 분석"),ended=candidate(live,"너 꺼져 종료로 멈춤");
    db.update("UPDATE guard.agent_task_outbox SET status='FAILED',last_error_code='AGENT_HTTP_400' WHERE case_id=?",rejected);
    db.update("UPDATE guard.agent_task_outbox SET status='FAILED',last_error_code='LIVE_ENDED' WHERE case_id=?",ended);
    var due=guard.casesAwaitingJudgment(java.time.Duration.ofHours(1),50);
    assertTrue(due.contains(rejected));
    assertFalse(due.contains(ended));
  }

  @Test void acceptedOrUncertainDeliveryDoesNotOpenFallbackBeforeTheJudgmentDeadline() {
    UUID live=lives.start(channel,moderator).liveId();
    for(String status:List.of("ACCEPTED","UNKNOWN")) {
      UUID c=candidate(live,"너 꺼져 접수와 실행 구분 "+status);
      db.update("UPDATE guard.agent_task_outbox SET status=?,delivery_uncertain=? WHERE case_id=?",
          status,status.equals("UNKNOWN"),c);
      assertFalse(guard.casesAwaitingJudgment(java.time.Duration.ofHours(1),50).contains(c));
      assertFalse(guard.openReviewWithoutJudgment(c,java.time.Duration.ofHours(1)));
      assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.reviews WHERE case_id=?",Integer.class,c));
      assertTrue(guard.openReviewWithoutJudgment(c,java.time.Duration.ZERO));
      assertEquals("NEEDS_REVIEW",guard.reviewCard(contextReview(c)).get("aiVerdict"));
      assertEquals("HELD",visibilityOf(c));
    }
  }

  /** 확정 위반 2건 뒤 AI 판정 없이 세 번째 위반을 확정한다. 세 번째 사건에 강퇴 검토가 생긴다. */
  UUID kickAfterViolationWithoutJudgment(UUID live) {
    for(int i=0;i<2;i++) {
      UUID c=candidate(live,"너 꺼져 강퇴 기준 "+i);UUID r=proposal(c,"VIOLATION");apply(c,r,decide(r,"CONFIRM_VIOLATION",1));
      db.update("UPDATE guard.live_timeouts SET revoked_at=current_timestamp WHERE live_id=?",live);
    }
    UUID c=candidate(live,"너 꺼져 판정 없이 세 번째");
    assertTrue(guard.openReviewWithoutJudgment(c,java.time.Duration.ZERO));
    decide(contextReview(c),"CONFIRM_VIOLATION",1);
    return db.queryForObject("SELECT id FROM guard.reviews WHERE case_id=? AND kind='KICK'",UUID.class,c);
  }

  static final java.time.Duration HANDOFF=java.time.Duration.ofMinutes(3);

  /** 시험에서는 주기 작업 대신 실행기를 직접 돌린다. 넘겨받기 마감은 기본값(3분)이다. */
  void runPendingKicks() {
    new KickWithoutAgentExecutor(guard,HANDOFF).executePending();
  }

  String kickAction(UUID kick) {
    return db.queryForObject("SELECT status||':'||coalesce(result->>'errorCode','-')||':'||coalesce(result->>'attemptCount','-')"
        + " FROM guard.live_actions WHERE kind='KICK' AND decision_id=(SELECT id FROM guard.decisions WHERE review_id=?)",String.class,kick);
  }

  @Test void approvedKickWithoutJudgmentIsStoredWithTheDecisionAndExecutedByTheBackend() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID kick=kickAfterViolationWithoutJudgment(live);
    decide(kick,"APPROVE",1);
    // 기다리는 Agent thread가 없으므로 재개 작업을 넣지 않는다. 실행 명령은 결정과 함께 남는다(재시작에도 남음)
    assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.agent_task_outbox WHERE payload->>'reviewId'=?",Integer.class,kick.toString()));
    assertEquals("PENDING:-:-",kickAction(kick));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.live_kicks WHERE live_id=?",Integer.class,live));
    runPendingKicks();
    assertEquals("SUCCEEDED:-:1",kickAction(kick));
    // ActionExecutionResult 필수 필드(강퇴는 expiresAt·errorCode가 null)
    assertEquals(true,db.queryForObject("SELECT result ?& array['expiresAt','errorCode'] FROM guard.live_actions WHERE kind='KICK'",Boolean.class));
    assertEquals(1,db.queryForObject("SELECT count(*) FROM guard.live_kicks WHERE live_id=? AND user_id=? AND review_id=?",Integer.class,live,user,kick));
    assertEquals(ErrorCode.KICKED,assertThrows(ApiException.class,()->guard.checkEntry(live,user)).code());
    // 실행한 명령은 다시 고르지 않는다
    assertEquals(List.of(),guard.pendingKicksWithoutAgent(HANDOFF,20));
  }

  @Test void kickThatFailsRevalidationKeepsTheDecisionAndRecordsTheFailure() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID kick=kickAfterViolationWithoutJudgment(live);
    // 승인 전에 기준 사건 하나의 위반이 취소되면 재검증을 통과하지 못한다
    db.update("UPDATE guard.findings SET revoked_at=current_timestamp WHERE case_id=(SELECT basis_case_ids[1] FROM guard.reviews WHERE id=?)",kick);
    decide(kick,"APPROVE",1);
    // 결정은 남고 검토는 INVALIDATED, 시도하지 않은 실패가 기록된다
    assertEquals("APPROVE",db.queryForObject("SELECT value FROM guard.decisions WHERE review_id=?",String.class,kick));
    assertEquals("INVALIDATED",db.queryForObject("SELECT status FROM guard.reviews WHERE id=?",String.class,kick));
    assertEquals("FAILED:REVIEW_NOT_APPROVED:0",kickAction(kick));
    assertEquals(true,db.queryForObject("SELECT result ?& array['expiresAt','errorCode','startedAt'] FROM guard.live_actions WHERE kind='KICK'",Boolean.class));
    runPendingKicks();
    assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.live_kicks WHERE live_id=?",Integer.class,live));
    assertDoesNotThrow(()->guard.checkEntry(live,user));
  }

  @Test void kickThatFailsAtExecutionIsRecordedAsFailed() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID kick=kickAfterViolationWithoutJudgment(live);
    decide(kick,"APPROVE",1);
    // 실행 명령을 만든 뒤 대상 상태가 바뀌면 실행이 STALE_VERSION으로 거절된다(다시 해도 같은 결과)
    db.update("UPDATE guard.live_user_state SET version=version+1 WHERE live_id=? AND user_id=?",live,user);
    runPendingKicks();
    assertEquals("FAILED:STALE_VERSION:1",kickAction(kick));
    assertEquals("APPROVE",db.queryForObject("SELECT value FROM guard.decisions WHERE review_id=?",String.class,kick));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.live_kicks WHERE live_id=?",Integer.class,live));
    assertEquals(List.of(),guard.pendingKicksWithoutAgent(HANDOFF,20));
  }

  @Test void rejectedKickWithoutJudgmentExecutesNothing() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID kick=kickAfterViolationWithoutJudgment(live);
    decide(kick,"REJECT",1);
    runPendingKicks();
    assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.agent_task_outbox WHERE payload->>'reviewId'=?",Integer.class,kick.toString()));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.live_kicks WHERE live_id=?",Integer.class,live));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.live_actions WHERE kind='KICK'",Integer.class));
  }

  @Test void agentPathKickCommandsAreLeftToTheAgent() {
    UUID live=lives.start(channel,moderator).liveId();
    List<UUID> cs=new ArrayList<>(),rs=new ArrayList<>();
    for(int i=0;i<3;i++){UUID c=candidate(live,"너 꺼져 Agent 강퇴 "+i);cs.add(c);rs.add(proposal(c,"VIOLATION"));
      apply(c,rs.get(i),decide(rs.get(i),"CONFIRM_VIOLATION",1));
      db.update("UPDATE guard.live_timeouts SET revoked_at=current_timestamp WHERE live_id=?",live);}
    UUID kick=db.queryForObject("SELECT r.id FROM guard.reviews r JOIN guard.cases c ON c.id=r.case_id WHERE c.live_id=? AND r.kind='KICK'",UUID.class,live);
    var d=decide(kick,"APPROVE",1);
    guard.revalidate(kick,UUID.fromString(d.get("decisionId").toString()));
    // AI 판정이 있는 사건의 강퇴 명령은 Agent가 kick_user로 실행한다. BE 실행기는 고르지 않는다
    assertEquals(List.of(),guard.pendingKicksWithoutAgent(HANDOFF,20));
    assertEquals(1,db.queryForObject("SELECT count(*) FROM guard.agent_task_outbox WHERE payload->>'reviewId'=?",Integer.class,kick.toString()));
  }

  // ---- Agent가 이어받지 않은 강퇴 승인 넘겨받기(A-25) ----

  /** 규칙 확정 사건 3건으로 강퇴 검토를 만든다. Agent는 분석 작업으로 이 검토의 결정을 기다리는 것으로 본다. */
  UUID ruleKick(UUID live) {
    for(String text:List.of("씨발 넘김 하나","씨발 넘김 둘","씨발 넘김 셋")) {
      candidate(live,text);
      db.update("UPDATE guard.live_timeouts SET revoked_at=current_timestamp WHERE live_id=?",live);
    }
    return db.queryForObject("SELECT r.id FROM guard.reviews r JOIN guard.cases c ON c.id=r.case_id WHERE c.live_id=? AND r.kind='KICK'",UUID.class,live);
  }

  /** 승인 뒤 시간이 지난 것과 같게 결정 시각을 앞당긴다. */
  void ageDecision(UUID d, java.time.Duration by) {
    db.update("UPDATE guard.decisions SET decided_at=decided_at-?::interval WHERE id=?",by.toSeconds()+" seconds",d);
  }

  @Test void ruleKickApprovalNotTakenByAgentIsHandedOffAfterTheDeadline() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID kick=ruleKick(live);
    UUID d=UUID.fromString(decide(kick,"APPROVE",1).get("decisionId").toString());
    // 규칙 사건은 Agent가 결정을 기다리므로 재개 작업을 보낸다. 마감 전에는 BE가 가져가지 않는다
    assertEquals(1,db.queryForObject("SELECT count(*) FROM guard.agent_task_outbox WHERE payload->>'reviewId'=?",Integer.class,kick.toString()));
    assertEquals(List.of(),guard.kickApprovalsAwaitingHandoff(HANDOFF,20));
    runPendingKicks();
    assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.live_actions WHERE kind='KICK'",Integer.class));
    // Agent가 분석 중 실패해 재개할 곳이 없으면 재검증이 오지 않는다. 마감이 지나면 BE가 재검증해 실행한다
    ageDecision(d,HANDOFF.plusSeconds(1));
    assertEquals(List.of(d),guard.kickApprovalsAwaitingHandoff(HANDOFF,20));
    runPendingKicks();
    assertEquals("SUCCEEDED:-:1",kickAction(kick));
    assertEquals(ErrorCode.KICKED,assertThrows(ApiException.class,()->guard.checkEntry(live,user)).code());
    assertEquals(List.of(),guard.kickApprovalsAwaitingHandoff(HANDOFF,20));
    // Agent가 늦게 재개해도 같은 명령과 실행 결과를 돌려받는다. 그 사이 대상 상태가 바뀌거나 승인한 운영자의 권한이
    // 회수돼도 이미 실행한 강퇴의 검토를 무효로 돌리지 않는다
    db.update("UPDATE guard.live_user_state SET version=version+1 WHERE live_id=? AND user_id=?",live,user);
    db.update("UPDATE platform.channel_members SET member_role='VIEWER' WHERE channel_id=? AND user_id=?",channel,operator);
    var late=guard.revalidate(kick,d);
    assertEquals("APPROVED",late.get("status"));
    var command=(Map<String,Object>)late.get("actionCommand");
    UUID action=UUID.fromString(command.get("actionId").toString());
    assertEquals("SUCCEEDED",guard.execute(action,command).get("status"));
    assertEquals("DECIDED",db.queryForObject("SELECT status FROM guard.reviews WHERE id=?",String.class,kick));
    assertEquals(1,db.queryForObject("SELECT count(*) FROM guard.live_kicks WHERE live_id=?",Integer.class,live));
    assertEquals(1,db.queryForObject("SELECT count(*) FROM guard.live_actions WHERE kind='KICK'",Integer.class));
  }

  @Test void agentCommandLeftUnexecutedIsExecutedAfterTheDeadline() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID kick=ruleKick(live);
    UUID d=UUID.fromString(decide(kick,"APPROVE",1).get("decisionId").toString());
    // Agent가 재검증해 명령을 받았지만 kick_user를 부르기 전에 멈췄다
    guard.revalidate(kick,d);
    runPendingKicks();
    assertEquals("PENDING:-:-",kickAction(kick));
    ageDecision(d,HANDOFF.plusSeconds(1));
    // 명령이 이미 있으므로 넘겨받기 대상은 아니고, 마감이 지난 미실행 명령으로 실행한다
    assertEquals(List.of(),guard.kickApprovalsAwaitingHandoff(HANDOFF,20));
    runPendingKicks();
    assertEquals("SUCCEEDED:-:1",kickAction(kick));
  }

  @Test void handoffThatFailsRevalidationIsRecordedAsFailed() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID kick=ruleKick(live);
    UUID d=UUID.fromString(decide(kick,"APPROVE",1).get("decisionId").toString());
    // 승인 뒤 기준 사건 하나의 위반이 취소됐다
    db.update("UPDATE guard.findings SET revoked_at=current_timestamp WHERE case_id=(SELECT basis_case_ids[1] FROM guard.reviews WHERE id=?)",kick);
    ageDecision(d,HANDOFF.plusSeconds(1));
    runPendingKicks();
    assertEquals("FAILED:REVIEW_NOT_APPROVED:0",kickAction(kick));
    assertEquals("INVALIDATED",db.queryForObject("SELECT status FROM guard.reviews WHERE id=?",String.class,kick));
    assertEquals(List.of(),guard.kickApprovalsAwaitingHandoff(HANDOFF,20));
    assertDoesNotThrow(()->guard.checkEntry(live,user));
  }

  @Test void handoffRejectedByOperatorAuthorityIsRecordedWithItsCode() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID kick=ruleKick(live);
    UUID d=UUID.fromString(decide(kick,"APPROVE",1).get("decisionId").toString());
    // 승인한 운영자의 권한이 회수됐다. 다시 해도 같은 결과이므로 한 번에 FAILED로 남긴다. 재검증 단계라 강퇴는 시도하지 않았다
    db.update("UPDATE platform.channel_members SET member_role='VIEWER' WHERE channel_id=? AND user_id=?",channel,operator);
    ageDecision(d,HANDOFF.plusSeconds(1));
    runPendingKicks();
    assertEquals("FAILED:FORBIDDEN:0",kickAction(kick));
    assertNull(db.queryForObject("SELECT result->>'startedAt' FROM guard.live_actions WHERE kind='KICK'",String.class));
    // 실패를 알린 뒤 Agent가 늦게 재검증해도 새 명령을 만들지 않는다(실행 기록은 하나)
    db.update("UPDATE platform.channel_members SET member_role='MODERATOR' WHERE channel_id=? AND user_id=?",channel,operator);
    assertEquals("INVALID",guard.revalidate(kick,d).get("status"));
    assertEquals(1,db.queryForObject("SELECT count(*) FROM guard.live_actions WHERE kind='KICK'",Integer.class));
    runPendingKicks();
    assertEquals(List.of(),guard.kickApprovalsAwaitingHandoff(HANDOFF,20));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.live_kicks WHERE live_id=?",Integer.class,live));
  }

  @Test void decisionOnCaseWithoutRouteDoesNotFail() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID c=candidate(live,"너 꺼져 경로 없음");
    assertTrue(guard.openReviewWithoutJudgment(c,java.time.Duration.ZERO));
    // 실제로는 조사 경로 사건에만 CONTEXT 검토가 생기고 처리 경로 없는 이전 사건은 live_case_state가 없다. 방어용으로,
    // 처리 경로가 비어 있어도 결정이 NPE로 실패하지 않는지만 본다(Agent가 기다리는 것으로 본다)
    db.update("UPDATE guard.cases SET handling_route=NULL WHERE id=?",c);
    assertDoesNotThrow(()->decide(contextReview(c),"DEFER",1));
  }

  @Test void casesOfEndedLivesDoNotCrowdOutTheLiveInProgress() {
    UUID ended=lives.start(channel,moderator).liveId();
    UUID old=candidate(ended,"너 꺼져 지난 방송");
    lives.end(ended,moderator);
    UUID current=lives.start(channel,moderator).liveId();
    UUID fresh=candidate(current,"너 꺼져 지금 방송");
    // 끝난 방송의 사건이 더 오래됐어도 진행 중인 방송의 사건이 먼저 고른 자리를 차지하지 않는다
    assertEquals(List.of(fresh),guard.casesAwaitingJudgment(java.time.Duration.ZERO,1));
    assertFalse(guard.casesAwaitingJudgment(java.time.Duration.ZERO,50).contains(old));
  }

  @Test void endedLiveGetsNoReviewWithoutJudgment() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID c=candidate(live,"너 꺼져 끝난 방송");
    lives.end(live,moderator);
    assertFalse(guard.casesAwaitingJudgment(java.time.Duration.ZERO,50).contains(c));
    assertFalse(guard.openReviewWithoutJudgment(c,java.time.Duration.ZERO));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.reviews WHERE case_id=?",Integer.class,c));
  }

  // ---- 수용 시나리오 (PROJECT.md AC) ----

  /** 사건에 적용된 TIMEOUT(초). 조치 시각부터 만료까지다. */
  List<Long> timeoutSeconds(UUID c) {
    return db.queryForList("SELECT round(extract(epoch FROM t.expires_at-a.created_at))::bigint FROM guard.live_timeouts t"
        + " JOIN guard.live_actions a ON a.id=t.action_id WHERE t.case_id=?",Long.class,c);
  }

  /** 이미 만든 후보 사건을 주어진 유형으로 위반 확정한다. 제한에 막히지 않도록 후보를 먼저 모두 만든 뒤 부른다. */
  Map<String,Object> confirmViolation(UUID c, List<String> categories) {
    UUID r=proposal(c,"VIOLATION");
    return apply(c,r,decide(r,"CONFIRM_VIOLATION",1,categories));
  }

  @Test void ac11MultipleCategoriesInOneCaseCountOnceAndApplyOnlyTheLongestTimeout() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID prior=candidate(live,"너 꺼져 이전 괴롭힘"),multi=candidate(live,"너 꺼져 여러 유형");
    confirmViolation(prior,List.of("HARASSMENT"));
    // 유형별 후보: PHISHING 첫 위반 600초, HARASSMENT 두 번째 86400초, PROFANITY 첫 위반 HIDE만(0초).
    // 합산(87000)·첫 값(600)·마지막 값(0)·0 아닌 최솟값(600)이 아니라 가장 긴 하나(86400)만 적용한다
    confirmViolation(multi,List.of("PHISHING","HARASSMENT","PROFANITY"));
    assertEquals(3,db.queryForObject("SELECT count(DISTINCT category) FROM guard.findings WHERE case_id=? AND source='BACKEND'"
        + " AND status='CONFIRMED'",Integer.class,multi));
    assertEquals(List.of(86400L),timeoutSeconds(multi));
    assertEquals(1,db.queryForObject("SELECT count(*) FROM guard.live_actions WHERE case_id=? AND kind='TIMEOUT'",Integer.class,multi));
    assertEquals("HIDDEN",visibilityOf(multi));
    // 강퇴 집계에는 유형 수가 아니라 사건 하나로 들어간다(이전 사건과 합쳐 2건)
    assertEquals(2,queries.kickEligibility(multi).count());
  }

  @Test void ac12TwoConfirmedCasesDoNotRequestKickReviewButTheThirdDoes() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID first=candidate(live,"너 꺼져 강퇴 집계 하나"),c=candidate(live,"너 꺼져 강퇴 집계 둘"),
        third=candidate(live,"너 꺼져 강퇴 집계 셋");
    confirmViolation(first,List.of("PROFANITY","HARASSMENT"));
    var applied=confirmViolation(c,List.of("HARASSMENT"));
    // 유형이 여럿인 첫 사건을 포함해도 확정 사건은 2건이다
    assertEquals(false,((Map<?,?>)applied.get("kickReview")).get("required"));
    assertEquals(2,queries.kickEligibility(third).count());
    assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.reviews r JOIN guard.cases c ON c.id=r.case_id"
        + " WHERE c.live_id=? AND r.kind='KICK'",Integer.class,live));
    confirmViolation(third,List.of("HARASSMENT"));
    UUID kick=db.queryForObject("SELECT id FROM guard.reviews WHERE case_id=? AND kind='KICK'",UUID.class,third);
    assertEquals(List.of(first,c,third).stream().map(UUID::toString).toList(),
        ((List<?>)guard.reviewCard(kick).get("basisCaseIds")).stream().map(Object::toString).toList());
  }

  @Test
  @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  void ac20NewLiveIsNotBlockedByThePreviousLivesKickOrTimeout() {
    try {
      UUID live=lives.start(channel,moderator).liveId();
      kick(live);
      assertTrue(db.queryForObject("SELECT count(*) FROM guard.live_timeouts WHERE live_id=? AND user_id=? AND revoked_at IS NULL"
          + " AND expires_at>current_timestamp",Integer.class,live,user)>0);
      assertEquals(ErrorCode.KICKED,assertThrows(ApiException.class,
          ()->lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),"강퇴 중"))).code());
      // 커밋되므로 방송 종료 뒤 guard 종료 처리(liveEnded)도 운영과 같이 실행된다
      lives.end(live,moderator);
      assertEquals("INVALIDATED",db.queryForObject("SELECT r.status FROM guard.reviews r JOIN guard.cases c ON c.id=r.case_id"
          + " WHERE c.live_id=? AND r.kind='KICK'",String.class,live));
      UUID next=lives.start(channel,moderator).liveId();
      assertDoesNotThrow(()->lives.history(next,viewer));
      assertDoesNotThrow(()->lives.send(next,viewer,new SendMessageRequest(UUID.randomUUID(),"새 방송 인사")));
      // 이전 방송의 위반은 새 방송의 단계·집계에 들어가지 않는다: 첫 HARASSMENT는 600초, 강퇴 집계 1건
      UUID c=candidate(next,"너 꺼져 새 방송");
      confirmViolation(c,List.of("HARASSMENT"));
      assertEquals(0,queries.userHistory(c,null).violationCount());
      assertEquals(List.of(600L),timeoutSeconds(c));
      assertEquals(1,queries.kickEligibility(c).count());
    } finally {
      deleteCommitted();
    }
  }

  @Test void ac21TimeoutExpiryDoesNotCancelTheViolationOrItsHistory() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID first=candidate(live,"너 꺼져 만료 전");
    confirmViolation(first,List.of("HARASSMENT"));
    assertEquals(ErrorCode.TIMED_OUT,assertThrows(ApiException.class,
        ()->lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),"제한 중"))).code());
    // 취소가 아니라 시간만 지난 것: 이 사건의 확정·조치·만료 시각을 601초 앞당겨 600초 제한이 1초 전에 끝난 것과 같게 한다
    db.update("UPDATE guard.findings SET created_at=created_at-interval '601 seconds',confirmed_at=confirmed_at-interval '601 seconds'"
        + " WHERE case_id=?",first);
    db.update("UPDATE guard.live_actions SET created_at=created_at-interval '601 seconds' WHERE case_id=? AND kind='TIMEOUT'",first);
    db.update("UPDATE guard.live_timeouts SET expires_at=expires_at-interval '601 seconds' WHERE case_id=?",first);
    assertDoesNotThrow(()->lives.send(live,viewer,new SendMessageRequest(UUID.randomUUID(),"만료 뒤 인사")));
    // 만료된 TIMEOUT 기록도 취소되지 않고 남는다
    assertEquals(List.of(600L),timeoutSeconds(first));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM guard.live_timeouts WHERE case_id=? AND revoked_at IS NOT NULL",Integer.class,first));
    assertEquals("CONFIRMED_VIOLATION",db.queryForObject("SELECT confirmation_status FROM guard.live_case_state WHERE case_id=?",String.class,first));
    assertEquals(1,db.queryForObject("SELECT count(*) FROM guard.findings WHERE case_id=? AND status='CONFIRMED' AND revoked_at IS NULL",Integer.class,first));
    assertEquals("HIDDEN",visibilityOf(first));
    // 다음 위반은 만료된 이력을 이전 확정으로 센다: 두 번째 HARASSMENT는 86400초
    UUID secondCase=candidate(live,"너 꺼져 만료 후");
    confirmViolation(secondCase,List.of("HARASSMENT"));
    assertEquals(1,queries.userHistory(secondCase,null).violationCount());
    assertEquals(List.of(86400L),timeoutSeconds(secondCase));
    assertEquals(2,queries.kickEligibility(secondCase).count());
  }
}
