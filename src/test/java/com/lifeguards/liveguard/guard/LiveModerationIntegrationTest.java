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
    properties = {"LIVEGUARD_POLICY_VERSION=v3.1", "LIVEGUARD_TBD_FIXTURE_ENABLED=true"})
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
      com.lifeguards.liveguard.support.CaseFixtures.deleteCases(db);
      db.update("DELETE FROM platform.messages WHERE channel_id=?",channel);
      db.update("DELETE FROM platform.lives WHERE channel_id=?",channel);
      db.update("DELETE FROM platform.channel_members WHERE channel_id=?",channel);
      db.update("DELETE FROM platform.channels WHERE id=?",channel);
      for(UUID id:List.of(user,operator,other))db.update("DELETE FROM platform.users WHERE id=?",id);
    }
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
    assertEquals(600,db.queryForObject("SELECT EXTRACT(EPOCH FROM (t.expires_at-a.created_at))::integer FROM guard.live_timeouts t JOIN guard.live_actions a ON a.id=t.action_id WHERE t.case_id=?",Integer.class,secondCase));
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
    listCases(session,live,"").andExpect(jsonPath("$.data.length()").value(4))
      .andExpect(jsonPath("$.data[*].targetMessageIds").exists())
      .andExpect(jsonPath("$.data[*].findings").exists())
      .andExpect(jsonPath("$.data[*].confirmedFindings").exists())
      .andExpect(jsonPath("$.data[*].violationCount").exists());
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

  @Test void rejectedAnalysisOpensReviewBeforeTheDeadlineButLiveEndAndSupersedeDoNot() {
    UUID live=lives.start(channel,moderator).liveId();
    UUID rejected=candidate(live,"너 꺼져 거절된 분석"),ended=candidate(live,"너 꺼져 종료로 멈춤");
    db.update("UPDATE guard.agent_task_outbox SET status='FAILED',last_error_code='AGENT_HTTP_400' WHERE case_id=?",rejected);
    db.update("UPDATE guard.agent_task_outbox SET status='FAILED',last_error_code='LIVE_ENDED' WHERE case_id=?",ended);
    var due=guard.casesAwaitingJudgment(java.time.Duration.ofHours(1),50);
    assertTrue(due.contains(rejected));
    assertFalse(due.contains(ended));
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

  /** 시험에서는 주기 작업 대신 실행기를 직접 돌린다. */
  void runPendingKicks() {
    new KickWithoutAgentExecutor(guard).executePending();
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
    assertEquals(List.of(),guard.pendingKicksWithoutAgent(20));
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
    assertEquals(List.of(),guard.pendingKicksWithoutAgent(20));
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
    assertEquals(List.of(),guard.pendingKicksWithoutAgent(20));
    assertEquals(1,db.queryForObject("SELECT count(*) FROM guard.agent_task_outbox WHERE payload->>'reviewId'=?",Integer.class,kick.toString()));
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
}
