package com.lifeguards.liveguard.guard;

import com.lifeguards.liveguard.common.*;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.enums.*;
import com.lifeguards.liveguard.guard.outbox.*;
import com.lifeguards.liveguard.platform.port.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
public class LiveModerationService implements LiveGuardPort {
  private final JdbcClient db;
  private final Clock clock;
  private final LivePlatformPort platform;
  private final JsonMapper json;
  private final AgentTaskOutbox outbox;

  public LiveModerationService(
      JdbcClient db,
      Clock clock,
      @Lazy LivePlatformPort platform,
      JsonMapper json,
      AgentTaskOutbox outbox) {
    this.db = db;
    this.clock = clock;
    this.platform = platform;
    this.json = json;
    this.outbox = outbox;
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant());
  }

  private UUID id(Object v) {
    return UUID.fromString(v.toString());
  }

  private Map<String, Object> parse(String s) {
    return json.readValue(s, Map.class);
  }

  private String encode(Object o) {
    return json.writeValueAsString(o);
  }

  private ApiException error(ErrorCode c, String m) {
    return new ApiException(c, m);
  }

  Map<String, Object> row(UUID c, boolean lock) {
    if(lock) {
      var scope=db.sql("SELECT live_id,user_id FROM guard.cases WHERE id=?").param(c)
        .query(new org.springframework.jdbc.core.ColumnMapRowMapper()).optional()
        .orElseThrow(()->error(ErrorCode.NOT_FOUND,"Live case not found"));
      platform.lockScope(id(scope.get("live_id")));
      scopeLock(id(scope.get("live_id")),id(scope.get("user_id")));
    }
    return db.sql(
            "SELECT"
                + " c.*,s.task_id,s.thread_id,s.snapshot::text,s.confirmation_status,s.judgment::text,s.judgment_receipt::text,s.applied_result::text"
                + " FROM guard.cases c JOIN guard.live_case_state s ON s.case_id=c.id WHERE c.id=?"
                + (lock ? " FOR UPDATE OF c,s" : ""))
        .param(c)
        .query(new org.springframework.jdbc.core.ColumnMapRowMapper())
        .optional()
        .orElseThrow(() -> error(ErrorCode.NOT_FOUND, "Live case not found"));
  }

  public boolean owns(UUID c) {
    return db.sql("SELECT count(*) FROM guard.live_case_state WHERE case_id=?")
            .param(c)
            .query(Integer.class)
            .single()
        > 0;
  }

  private void active(Map<String, Object> c) {
    platform.lockActive(id(c.get("live_id")));
  }

  private void scopeLock(UUID live, UUID user) {
    db.sql("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")
        .param(live + ":" + user)
        .query((r, n) -> true)
        .single();
  }

  @Override
  public void checkEntry(UUID live, UUID user) {
    if (db.sql("SELECT count(*) FROM guard.live_kicks WHERE live_id=? AND user_id=?")
            .params(live, user)
            .query(Integer.class)
            .single()
        > 0) throw error(ErrorCode.KICKED, "Access blocked until live ends");
  }

  @Override
  public void checkAccess(UUID live, UUID user) {
    checkEntry(live, user);
    var until =
        db.sql(
                "SELECT max(expires_at) FROM guard.live_timeouts WHERE live_id=? AND user_id=? AND"
                    + " revoked_at IS NULL AND expires_at>?")
            .params(live, user, now())
            .query((r, n) -> r.getTimestamp(1))
            .optional();
    if (until.isPresent())
      throw new ApiException(
          ErrorCode.TIMED_OUT, "Chat temporarily restricted", until.get().toInstant(), null);
  }

  @Override public Instant timeoutUntil(UUID live,UUID user) {
    var value=db.sql("SELECT max(expires_at) FROM guard.live_timeouts WHERE live_id=? AND user_id=? AND revoked_at IS NULL AND expires_at>?")
      .params(live,user,now()).query((r,n)->r.getTimestamp(1)).optional();
    return value.map(Timestamp::toInstant).orElse(null);
  }

  @Override
  public UUID candidate(
      UUID live,
      UUID channel,
      UUID user,
      UUID message,
      String route,
      String policy,
      List<String> tags,
      Instant at, String normalizedText) {
    platform.lockScope(live);
    scopeLock(live, user);
    if(route.equals("FLOOD_REVIEW")) {
      var existing=db.sql("SELECT m.case_id FROM guard.flood_marks m WHERE m.live_id=? AND m.user_id=? AND m.text_hash=? AND m.last_sent_at>? AND EXISTS(SELECT 1 FROM guard.findings f WHERE f.case_id=m.case_id AND f.category='FLOOD' AND f.status='CONFIRMED') ORDER BY m.confirmed_at DESC LIMIT 1")
        .params(live,user,textHash(normalizedText),Timestamp.from(at.minusSeconds(10))).query(UUID.class).optional();
      if(existing.isPresent()) {
        UUID owner=existing.get();platform.bindCase(message,owner,route);
        db.sql("INSERT INTO guard.case_messages(case_id,message_id,owned,is_target) VALUES(?,?,true,true)").params(owner,message).update();
        db.sql("INSERT INTO guard.candidates(message_id,case_id,filter_result,matching_tags,filter_version,related_message_ids,sent_at,snapshot_id,evidence_version) VALUES(?,?,'FLOOD',ARRAY['REPEATED_TEXT'],'norm-v1+dict-v2','{}',?,?,1)")
          .params(message,owner,Timestamp.from(at),UUID.randomUUID()).update();
        db.sql("UPDATE guard.flood_marks SET last_sent_at=? WHERE case_id=?").params(Timestamp.from(at),owner).update();
        platform.visibility(owner,"HIDDEN");return owner;
      }
    }
    UUID c = UUID.randomUUID(), snapshot = UUID.randomUUID(), task = UUID.randomUUID();
    String thread = "case:" + c + ":analysis:1";
    db.sql(
            "INSERT INTO"
                + " guard.cases(id,channel_id,user_id,live_id,policy_version,handling_route,snapshot_id,occurred_at,created_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?)")
        .params(c, channel, user, live, policy, route, snapshot, Timestamp.from(at), now())
        .update();
    db.sql(
            "INSERT INTO"
                + " guard.candidates(message_id,case_id,filter_result,matching_tags,filter_version,related_message_ids,sent_at,snapshot_id,evidence_version)"
                + " VALUES(?,?,?,string_to_array(?,','),?,'{}',?,?,1)")
        .params(
            message,
            c,
            route.equals("FLOOD_REVIEW") ? "FLOOD" : "HOLD",
            String.join(",", tags),
            "norm-v1+dict-v2",
            Timestamp.from(at),
            snapshot)
        .update();
    db.sql(
            "INSERT INTO guard.case_messages(case_id,message_id,owned,is_target)"
                + " VALUES(?,?,true,true)")
        .params(c, message)
        .update();
    platform.bindCase(message,c,route);
    var messages = platform.snapshot(live, message, user);
    Map<String, Object> frozen = new LinkedHashMap<>();
    frozen.put("caseId", c);
    frozen.put("channelId", channel);
    frozen.put("liveId", live);
    frozen.put("userId", user);
    frozen.put("snapshotId", snapshot);
    frozen.put("evidenceVersion", 1);
    frozen.put("policyVersion", policy);
    frozen.put("handlingRoute", route);
    frozen.put("filterResult", route.equals("FLOOD_REVIEW") ? "FLOOD" : "HOLD");
    frozen.put("matchingTags", tags);
    frozen.put("version", 1);
    frozen.put("messages", messages);
    frozen.put("targetMessageIds", List.of(message));
    frozen.put("targetVersions", Map.of(message.toString(), 1));
    frozen.put(
        "targets",
        messages.stream()
            .filter(m -> m.get("messageId").equals(message))
            .map(
                m ->
                    Map.of(
                        "messageId",
                        message,
                        "visibility",
                        m.get("visibility"),
                        "version",
                        m.get("version")))
            .toList());
    db.sql(
            "INSERT INTO guard.live_case_state(case_id,task_id,thread_id,snapshot)"
                + " VALUES(?,?,?,?::jsonb)")
        .params(c, task, thread, encode(frozen))
        .update();
    if(route.equals("FLOOD_REVIEW")) {
      confirm(row(c,true),List.of("FLOOD"),"rule:flood:"+message);
      db.sql("INSERT INTO guard.flood_marks(live_id,user_id,text_hash,case_id,confirmed_at,last_sent_at) VALUES(?,?,?,?,?,?)")
        .params(live,user,textHash(normalizedText),c,now(),Timestamp.from(at)).update();
      if(Boolean.TRUE.equals(ensureKick(c).get("required")))enqueue(row(c,false),null,null,null);
    } else if (route.equals("RULE_CONFIRMED")) {
      List<String> ruleCategories=new ArrayList<>();
      if(tags.contains("CERTAIN_PROFANITY"))ruleCategories.add("PROFANITY");
      if(tags.contains("LINK"))ruleCategories.add("PHISHING");
      if(ruleCategories.isEmpty())throw error(ErrorCode.VALIDATION_FAILED,"Rule evidence required");
      confirm(row(c,true),ruleCategories,"rule:"+message);
      var kick = ensureKick(c);
      if (Boolean.TRUE.equals(kick.get("required"))) enqueue(row(c, false), null, null, null);
    } else if (route.equals("CONTEXT_REVIEW") || route.equals("PII_INVESTIGATION")) {
      enqueue(row(c, false), null, null, null);
    }
    platform.emit(live,"CASE_CREATED",caseEvent(c),null,true);
    return c;
  }

  private String textHash(String text) {
    try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  }
  @Override public boolean floodRecurrence(UUID live,UUID user,String text,Instant at) {
    return db.sql("SELECT count(*) FROM guard.flood_marks m WHERE m.live_id=? AND m.user_id=? AND m.text_hash=? AND m.confirmed_at>? AND m.last_sent_at<=? AND EXISTS(SELECT 1 FROM guard.findings f WHERE f.case_id=m.case_id AND f.category='FLOOD' AND f.status='CONFIRMED')")
      .params(live,user,textHash(text),Timestamp.from(at.minusSeconds(86400)),Timestamp.from(at.minusSeconds(10))).query(Integer.class).single()>0;
  }

  private void enqueue(Map<String, Object> c, UUID review, UUID decision, String kind) {
    outbox.enqueue(
        new AgentTaskEnvelope(
            UUID.randomUUID(),
            id(c.get("task_id")),
            id(c.get("id")),
            review == null ? AgentTaskEnvelope.Kind.ANALYZE : AgentTaskEnvelope.Kind.RESUME_REVIEW,
            c.get("thread_id").toString(),
            id(c.get("snapshot_id")),
            ((Number) c.get("evidence_version")).intValue(),
            decision,
            kind == null ? null : AgentTaskEnvelope.ReviewKind.valueOf(kind),
            review,
            UUID.randomUUID()));
  }

  public Map<String, Object> snapshot(UUID c) {
    var current = row(c, false);
    Map<String, Object> result = new LinkedHashMap<>(parse(current.get("snapshot").toString()));
    result.put("confirmationStatus", current.get("confirmation_status"));
    result.put("kickReview", kickFor(c));
    return parse(encode(result));
  }

  public Map<String, Object> context(UUID c) {
    var snapshot = snapshot(c);
    return Map.of(
        "caseId",
        c,
        "snapshotId",
        snapshot.get("snapshotId"),
        "evidenceVersion",
        snapshot.get("evidenceVersion"),
        "messages",
        snapshot.get("messages"),
        "truncated",
        false);
  }

  public com.lifeguards.liveguard.contracts.dto.response.InternalCase internalCase(UUID c) {
    var s = snapshot(c);
    var messages =
        ((List<Map<String, Object>>) s.get("messages"))
            .stream()
                .map(
                    m ->
                        new com.lifeguards.liveguard.contracts.dto.response.MaskedMessage(
                            id(m.get("messageId")),
                            m.get("authorAlias").toString(),
                            m.get("text").toString(),
                            Instant.parse(m.get("sentAt").toString()),
                            m.get("visibility").toString()))
                .toList();
    var targets =
        ((List<Map<String, Object>>) s.get("targets"))
            .stream()
                .map(
                    m ->
                        new com.lifeguards.liveguard.contracts.dto.response.InternalCase.Target(
                            id(m.get("messageId")),
                            Visibility.valueOf(m.get("visibility").toString()),
                            ((Number) m.get("version")).intValue()))
                .toList();
    return new com.lifeguards.liveguard.contracts.dto.response.InternalCase(
        c,
        id(s.get("channelId")),
        id(s.get("liveId")),
        id(s.get("snapshotId")),
        ((Number) s.get("evidenceVersion")).intValue(),
        s.get("policyVersion").toString(),
        HandlingRoute.valueOf(s.get("handlingRoute").toString()),
        FilterResult.valueOf(s.get("filterResult").toString()),
        (List<String>) s.get("matchingTags"),
        ((Number) s.get("version")).intValue(),
        messages,
        targets,
        id(s.get("userId")),
        s.get("confirmationStatus").toString(),
        (Map<String, Object>) s.get("kickReview"));
  }

  public com.lifeguards.liveguard.contracts.dto.response.ContextData internalContext(UUID c) {
    var s = internalCase(c);
    return new com.lifeguards.liveguard.contracts.dto.response.ContextData(
        s.messages(), false, c, s.snapshotId(), s.evidenceVersion());
  }

  public com.lifeguards.liveguard.contracts.dto.response.UserHistory userHistory(UUID id) {
    var c=row(id,false);
    var items=db.sql("SELECT c.id,f.category,f.confirmed_at FROM guard.cases c JOIN guard.findings f ON f.case_id=c.id WHERE c.live_id=? AND c.user_id=? AND c.id<>? AND f.source='BACKEND' AND f.status='CONFIRMED' AND f.verdict='VIOLATION' ORDER BY f.confirmed_at,c.id,f.category")
      .params(c.get("live_id"),c.get("user_id"),id).query((r,n)->new com.lifeguards.liveguard.contracts.dto.response.UserHistory.RecentCase(r.getObject(1,UUID.class),Category.valueOf(r.getString(2)),r.getTimestamp(3).toInstant())).list();
    return new com.lifeguards.liveguard.contracts.dto.response.UserHistory((int)items.stream().map(x->x.caseId()).distinct().count(),items);
  }

  public Map<String, Object> actionStatus(UUID a) {
    var result =
        db.sql("SELECT result::text FROM guard.live_actions WHERE id=?")
            .param(a)
            .query(String.class)
            .optional();
    return result.isPresent()
        ? parse(result.get())
        : Map.of("actionId", a, "status", "PENDING", "observation", "UNKNOWN");
  }

  public boolean ownsAction(UUID a) {
    return db.sql("SELECT count(*) FROM guard.live_actions WHERE id=?")
            .param(a)
            .query(Integer.class)
            .single()
        > 0;
  }

  public List<Map<String, Object>> cases(UUID live, AuthenticatedUser user) {
    platform.authorize(live, user.userId(), true);
    return db
        .sql(
            "SELECT c.id FROM guard.cases c JOIN guard.live_case_state s ON s.case_id=c.id WHERE"
                + " c.live_id=? ORDER BY c.created_at DESC")
        .param(live)
        .query(UUID.class)
        .list()
        .stream()
        .map(c -> detail(c, user))
        .toList();
  }

  public Map<String, Object> detail(UUID c, AuthenticatedUser user) {
    var current = row(c, false);
    platform.authorize(id(current.get("live_id")), user.userId(), true);
    var data = new LinkedHashMap<>(snapshot(c));
    data.put("status", current.get("status"));
    data.put("version",current.get("version"));
    data.put("confirmedFindings",db.sql("SELECT id,category,status,confirmed_at,revoked_at FROM guard.findings WHERE case_id=? AND source='BACKEND' ORDER BY created_at,id")
      .param(c).query((r,n)->{
        Map<String,Object> f=new LinkedHashMap<>();f.put("findingId",r.getObject("id"));f.put("category",r.getString("category"));
        f.put("status",r.getString("status"));f.put("confirmedAt",r.getTimestamp("confirmed_at"));f.put("revokedAt",r.getTimestamp("revoked_at"));return f;
      }).list());
    data.put("occurredAt", current.get("occurred_at"));
    data.put("createdAt",current.get("created_at"));data.put("userAlias","U1");
    data.put("reason",null);data.put("policySnapshots",List.of());
    data.put("evidenceMessageIds",data.get("targetMessageIds"));data.put("violationCount",userHistory(c).violationCount());
    List<Map<String,Object>> browserMessages=new ArrayList<>();
    for(var m:(List<Map<String,Object>>)data.get("messages")){
      var copy=new LinkedHashMap<>(m);copy.put("channelId",current.get("channel_id"));copy.put("liveId",current.get("live_id"));
      copy.put("isMine",user.userId().equals(current.get("user_id"))&&m.get("authorAlias").equals("U1"));browserMessages.add(copy);
    }
    data.put("messages",browserMessages);

    data.put(
        "findings",
        current.get("judgment") == null
            ? List.of()
            : parse(current.get("judgment").toString()).get("messageFindings"));
    return data;
  }

  Map<String,Object> caseEvent(UUID id) {
    var c=row(id,false);
    return Map.of("caseId",id,"liveId",c.get("live_id"),"status",c.get("status"),"handlingRoute",c.get("handling_route"),"confirmationStatus",c.get("confirmation_status"),"version",c.get("version"));
  }

  public void progress(UUID c, String event, Object data) {
    var current = row(c, false);
    platform.emit(
        id(current.get("live_id")), event, json.convertValue(data, Map.class), null, true);
  }

  public Map<String, Object> judgment(UUID c, Map<String, Object> input) {
    if (!input
        .keySet()
        .equals(
            Set.of(
                "caseId",
                "liveId",
                "handlingRoute",
                "snapshotId",
                "evidenceVersion",
                "policyVersion",
                "messageFindings")))
      throw error(ErrorCode.VALIDATION_FAILED, "Invalid judgment fields");
    var current = row(c, true);
    active(current);
    var snapshot = snapshot(c);
    for (String key :
        List.of(
            "caseId",
            "liveId",
            "handlingRoute",
            "snapshotId",
            "evidenceVersion",
            "policyVersion")) {
      if (!Objects.toString(snapshot.get(key)).equals(Objects.toString(input.get(key))))
        throw error(ErrorCode.STALE_VERSION, "Case evidence or policy mismatch");
    }
    if (!Set.of("CONTEXT_REVIEW", "PII_INVESTIGATION").contains(input.get("handlingRoute")))
      throw error(ErrorCode.VALIDATION_FAILED, "Investigation route required");
    if (current.get("judgment") != null) {
      if (!parse(current.get("judgment").toString()).equals(input))
        throw error(ErrorCode.IDEMPOTENCY_CONFLICT, "Judgment conflict");
      return parse(current.get("judgment_receipt").toString());
    }
    List<Map<String, Object>> findings = (List<Map<String, Object>>) input.get("messageFindings");
    if (findings == null || findings.isEmpty())
      throw error(ErrorCode.VALIDATION_FAILED, "Findings required");
    Set<String> targets =
        new HashSet<>(
            ((List<?>) snapshot.get("targetMessageIds")).stream().map(Object::toString).toList());
    Set<String> found = new HashSet<>();
    boolean uncertain = false, violation = false;
    Set<String> categories = new HashSet<>();
    for (var f : findings) {
      if (!f.keySet()
          .equals(
              Set.of("messageId", "verdict", "categories", "policyIds", "reason", "missingFacts")))
        throw error(ErrorCode.VALIDATION_FAILED, "Invalid finding fields");
      String target = Objects.toString(f.get("messageId"));
      String verdict = Objects.toString(f.get("verdict"));
      if (!targets.contains(target)
          || !found.add(target)
          || !Set.of("VIOLATION", "NO_VIOLATION", "NEEDS_REVIEW").contains(verdict)
          || !(f.get("reason") instanceof String reason)
          || reason.isBlank()
          || reason.length() > 12000) throw error(ErrorCode.VALIDATION_FAILED, "Invalid finding");
      List<String> cats = (List<String>) f.get("categories");
      List<String> policyIds = (List<String>) f.get("policyIds");
      if (cats == null || policyIds == null || !(f.get("missingFacts") instanceof List<?> missing))
        throw error(ErrorCode.VALIDATION_FAILED, "Finding fields required");
      if (verdict.equals("NO_VIOLATION") && !cats.isEmpty()
          || verdict.equals("NEEDS_REVIEW") && missing.isEmpty()
          || verdict.equals("VIOLATION") && (cats.isEmpty() || policyIds.isEmpty()))
        throw error(ErrorCode.VALIDATION_FAILED, "Invalid verdict evidence");
      for (String category : cats) Category.valueOf(category);
      for (String p : policyIds)
        if (!Set.of(
                "CP-1.1", "CP-1.2", "CP-2.1", "CP-2.2", "CP-3.1", "CP-3.2", "CP-4.1", "CP-4.2",
                "CP-5.1", "CP-5.2", "SP-2.1", "SP-3.1", "CP-6.1", "MP-1.1", "MP-1.2", "MP-1.3",
                "MP-1.4", "MP-2.1", "MP-2.2", "MP-2.3", "MP-3.1", "MP-3.2", "MP-4.1", "MP-4.2",
                "MP-4.3", "SP-4.1", "MM-1.1", "MM-2.1", "CP-7.1")
            .contains(p)) throw error(ErrorCode.VALIDATION_FAILED, "Unknown policy reference");
      uncertain |= verdict.equals("NEEDS_REVIEW");
      violation |= verdict.equals("VIOLATION");
      categories.addAll(cats);
      for (String cat : cats)
        storeFinding(current, cat, verdict, "AI_PROPOSAL", "PROPOSED", "judgment:" + c);
    }
    if (!found.equals(targets))
      throw error(ErrorCode.VALIDATION_FAILED, "Exactly one finding per target required");
    // The current server has no verified personal-data source. A pattern and an
    // AI claim alone cannot authorize PII confirmation. A-16 handles uncertainty.
    if (input.get("handlingRoute").equals("PII_INVESTIGATION") && violation && !uncertain)
      throw error(
          ErrorCode.REVIEW_NOT_APPROVED, "Verified PII facts unavailable; NEEDS_REVIEW required");
    db.sql("UPDATE guard.live_case_state SET judgment=?::jsonb WHERE case_id=?").params(encode(input),c).update();
    Map<String, Object> receipt = new LinkedHashMap<>();
    if (input.get("handlingRoute").equals("CONTEXT_REVIEW") || uncertain) {
      UUID review = createReview(c, "CONTEXT", List.of());
      receipt.put("confirmationStatus", "PENDING");
      receipt.put("reviewId", review.toString());
      receipt.put("reviewKind", "CONTEXT");
    } else {
      if (violation) confirm(current, new ArrayList<>(categories), "pii:" + c);
      else {
        platform.visibility(c, "PUBLIC");
        db.sql(
                "UPDATE guard.live_case_state SET confirmation_status='CONFIRMED_NORMAL' WHERE"
                    + " case_id=?")
            .param(c)
            .update();
      }
      var kick = ensureKick(c);
      receipt.put("confirmationStatus", violation ? "CONFIRMED_VIOLATION" : "CONFIRMED_NORMAL");
      receipt.put(
          "reviewId", Boolean.TRUE.equals(kick.get("required")) ? kick.get("reviewId") : null);
      receipt.put("reviewKind", Boolean.TRUE.equals(kick.get("required")) ? "KICK" : null);
    }
    db.sql(
            "UPDATE guard.live_case_state SET judgment=?::jsonb,judgment_receipt=?::jsonb WHERE"
                + " case_id=?")
        .params(encode(input), encode(receipt), c)
        .update();
    return parse(encode(receipt));
  }

  private UUID createReview(UUID c, String kind, List<UUID> basis) {
    var old =
        db.sql("SELECT id FROM guard.reviews WHERE case_id=? AND kind=?")
            .params(c, kind)
            .query(UUID.class)
            .optional();
    if (old.isPresent()) return old.get();
    UUID r = UUID.randomUUID();
    db.sql(
            "INSERT INTO guard.reviews(id,case_id,kind,basis_case_ids,basis_snapshot_id,created_at)"
                + " VALUES(?,?,?,string_to_array(?,',')::uuid[],?,?)")
        .params(
            r,
            c,
            kind,
            String.join(",", basis.stream().map(Object::toString).toList()),
            UUID.randomUUID(),
            now())
        .update();
    var current = row(c, false);
    Map<String,Object> event=new LinkedHashMap<>();event.put("reviewId",r);event.put("caseId",c);event.put("liveId",current.get("live_id"));
    if(kind.equals("CONTEXT"))event.put("aiVerdict",((List<Map<String,Object>>)parse(current.get("judgment").toString()).get("messageFindings")).getFirst().get("verdict"));
    else {event.put("summary","서로 다른 확정 사건 3건에 대한 별도 강퇴 승인 요청");event.put("basisCaseIds",basis);}
    platform.emit(id(current.get("live_id")),kind+"_REVIEW_REQUIRED",event,null,true);
    return r;
  }

  private Map<String, Object> review(UUID id, boolean lock) {
    if(lock) {
      UUID owner=db.sql("SELECT case_id FROM guard.reviews WHERE id=?").param(id).query(UUID.class).optional()
        .orElseThrow(()->error(ErrorCode.NOT_FOUND,"Review not found"));
      row(owner,true);
    }
    return db.sql("SELECT * FROM guard.reviews WHERE id=?" + (lock ? " FOR UPDATE" : ""))
        .param(id)
        .query(new org.springframework.jdbc.core.ColumnMapRowMapper())
        .optional()
        .orElseThrow(() -> error(ErrorCode.NOT_FOUND, "Review not found"));
  }

  public List<Map<String, Object>> reviews(UUID live, AuthenticatedUser user) {
    platform.authorize(live, user.userId(), true);
    return db
        .sql(
            "SELECT r.id FROM guard.reviews r JOIN guard.cases c ON c.id=r.case_id WHERE"
                + " c.live_id=? ORDER BY r.created_at")
        .param(live)
        .query(UUID.class)
        .list()
        .stream()
        .map(this::reviewCard)
        .toList();
  }

  public Map<String, Object> reviewCard(UUID r) {
    var rev = review(r, false);
    var c = row(id(rev.get("case_id")), false);
    var data = new LinkedHashMap<String, Object>();
    data.put("reviewId", r);
    data.put("kind", rev.get("kind"));
    data.put("caseId", c.get("id"));
    data.put("liveId", c.get("live_id"));
    data.put("status", rev.get("status"));
    data.put("version", rev.get("version"));
    data.put("createdAt", rev.get("created_at"));
    data.put("evidenceVersion", c.get("evidence_version"));
    data.put("messages", parse(c.get("snapshot").toString()).get("messages"));
    data.put("targetMessageIds", parse(c.get("snapshot").toString()).get("targetMessageIds"));
    data.put(
        "findings",
        c.get("judgment") == null
            ? List.of()
            : parse(c.get("judgment").toString()).get("messageFindings"));
    data.put(
        "aiVerdict",
        c.get("judgment") == null
            ? "VIOLATION"
            : ((List<Map<String, Object>>)
                    parse(c.get("judgment").toString()).get("messageFindings"))
                .getFirst()
                .get("verdict"));
    data.put("basisCaseIds", basis(rev));
    data.put("basisSnapshotId", rev.get("basis_snapshot_id"));
    data.put("userAlias", "U1");
    data.put(
        "summary",
        rev.get("kind").equals("KICK")
            ? "서로 다른 확정 사건 3건 — 방송 종료까지 차단 승인 요청"
            : "AI 제안을 확인하고 최종 결정해 주세요");
    return data;
  }

  private List<UUID> basis(Map<String, Object> r) {
    try {
      return Arrays.asList((UUID[]) ((java.sql.Array) r.get("basis_case_ids")).getArray());
    } catch (Exception e) {
      throw error(ErrorCode.INTERNAL_ERROR, "Invalid stored basis");
    }
  }

  public Map<String, Object> decide(
      UUID r, Map<String, Object> input, AuthenticatedUser user, String kind) {
    if (!Set.of(
                "clientRequestId",
                "expectedReviewVersion",
                "decision",
                "reason",
                "categories",
                "evidenceMessageIds")
            .containsAll(input.keySet())
        || !input
            .keySet()
            .containsAll(Set.of("clientRequestId", "expectedReviewVersion", "decision", "reason")))
      throw error(ErrorCode.VALIDATION_FAILED, "Invalid decision fields");
    var rev = review(r, true);
    var c = row(id(rev.get("case_id")), true);
    platform.authorize(id(c.get("live_id")), user.userId(), true);
    active(c);
    if (!rev.get("kind").equals(kind))
      throw error(ErrorCode.VALIDATION_FAILED, "Review kind mismatch");
    UUID client = id(input.get("clientRequestId"));
    var prior =
        db.sql(
                "SELECT id,request::text FROM guard.decisions WHERE review_id=? AND"
                    + " client_request_id=?")
            .params(r, client)
            .query(new org.springframework.jdbc.core.ColumnMapRowMapper())
            .optional();
    if (prior.isPresent()) {
      if (!parse(prior.get().get("request").toString()).equals(input))
        throw error(ErrorCode.IDEMPOTENCY_CONFLICT, "Decision request conflict");
      return decision(r, id(prior.get().get("id")), false);
    }
    if (!rev.get("status").equals("PENDING"))
      throw error(ErrorCode.IDEMPOTENCY_CONFLICT, "Review already decided");
    if (!Objects.equals(input.get("expectedReviewVersion"), rev.get("version")))
      throw error(ErrorCode.STALE_VERSION, "Review version changed");
    String value = Objects.toString(input.get("decision"));
    String reason = Objects.toString(input.get("reason"), "");
    if (reason.isBlank()
        || reason.length() > 500
        || !(kind.equals("CONTEXT")
                ? Set.of("CONFIRM_NORMAL", "CONFIRM_VIOLATION", "DEFER")
                : Set.of("APPROVE", "REJECT"))
            .contains(value)) throw error(ErrorCode.VALIDATION_FAILED, "Invalid decision");
    List<String> cats = (List<String>) input.getOrDefault("categories", List.of());
    List<String> evidence = (List<String>) input.getOrDefault("evidenceMessageIds", List.of());
    if (value.equals("CONFIRM_VIOLATION")) {
      if (cats.isEmpty() || evidence.isEmpty() || new HashSet<>(cats).size() != cats.size())
        throw error(ErrorCode.VALIDATION_FAILED, "Categories and evidence required");
      cats.forEach(Category::valueOf);
      Set<String> known =
          new HashSet<>(
              ((List<Map<String, Object>>) parse(c.get("snapshot").toString()).get("messages"))
                  .stream().map(m -> m.get("messageId").toString()).toList());
      if (!known.containsAll(evidence))
        throw error(ErrorCode.VALIDATION_FAILED, "Evidence outside case snapshot");
    } else if (!cats.isEmpty() || !evidence.isEmpty())
      throw error(ErrorCode.VALIDATION_FAILED, "Unexpected violation fields");
    UUID d = UUID.randomUUID();
    db.sql(
            "INSERT INTO"
                + " guard.decisions(id,review_id,client_request_id,operator_id,value,reason,categories,evidence_message_ids,decided_at,request)"
                + " VALUES(?,?,?,?,?,?,string_to_array(?,','),string_to_array(?,',')::uuid[],?,?::jsonb)")
        .params(
            d,
            r,
            client,
            user.userId(),
            value,
            reason,
            String.join(",", cats),
            String.join(",", evidence),
            now(),
            encode(input))
        .update();
    db.sql("UPDATE guard.reviews SET version=version+1,status=? WHERE id=?")
        .params(value.equals("DEFER") ? "PENDING" : "DECIDED", r)
        .update();
    if (kind.equals("KICK"))
      db.sql("UPDATE guard.live_reservations SET state='CONSUMED' WHERE review_id=?")
          .param(r)
          .update();
    enqueue(c, r, d, kind);
    platform.emit(
        id(c.get("live_id")),
        "REVIEW_DECIDED",
        Map.of("reviewId", r, "decisionId", d, "kind", kind, "value", value),
        null,
        true);
    return decision(r, d, false);
  }

  public Map<String, Object> decision(UUID r, UUID d, boolean internal) {
    var rev = review(r, false);
    var c = row(id(rev.get("case_id")), false);
    var stored =
        db.sql("SELECT * FROM guard.decisions WHERE id=? AND review_id=?")
            .params(d, r)
            .query(new org.springframework.jdbc.core.ColumnMapRowMapper())
            .optional()
            .orElseThrow(() -> error(ErrorCode.NOT_FOUND, "Decision not found"));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("decisionId", d);
    result.put("reviewId", r);
    result.put("kind", rev.get("kind"));
    result.put("value", stored.get("value"));
    result.put("operatorId", stored.get("operator_id"));
    result.put("decidedAt", stored.get("decided_at"));
    if (internal) {
      result.putAll(refs(c));
      result.put("threadId", c.get("thread_id"));
      result.put("status", "DECIDED");
      result.put("decision", stored.get("value"));
      try {
        result.put(
            "categories",
            Arrays.asList((String[]) ((java.sql.Array) stored.get("categories")).getArray()));
      } catch (Exception e) {
        throw error(ErrorCode.INTERNAL_ERROR, "Invalid stored decision");
      }
    }
    return parse(encode(result));
  }

  private Map<String, Object> refs(Map<String, Object> c) {
    return Map.of(
        "caseId",
        c.get("id"),
        "liveId",
        c.get("live_id"),
        "snapshotId",
        c.get("snapshot_id"),
        "evidenceVersion",
        c.get("evidence_version"),
        "policyVersion",
        c.get("policy_version"));
  }

  public Map<String, Object> apply(UUID r, Map<String, Object> input) {
    if (!r.toString().equals(Objects.toString(input.get("reviewId"))))
      throw error(ErrorCode.VALIDATION_FAILED, "Review path mismatch");
    var rev = review(r, true);
    UUID cId = id(rev.get("case_id"));
    var c = row(cId, true);
    for (var ref : refs(c).entrySet())
      if (!Objects.toString(input.get(ref.getKey())).equals(ref.getValue().toString()))
        throw error(ErrorCode.STALE_VERSION, "Apply scope mismatch");
    if (!Objects.toString(input.get("threadId")).equals(c.get("thread_id"))
        || !Objects.toString(input.get("taskId")).equals(c.get("task_id").toString()))
      throw error(ErrorCode.STALE_VERSION, "Apply task mismatch");
    var d = decision(r, id(input.get("decisionId")), true);
    if (!rev.get("kind").equals("CONTEXT")
        || !rev.get("status").equals("DECIDED")
        || !Set.of("CONFIRM_NORMAL", "CONFIRM_VIOLATION").contains(d.get("decision")))
      throw error(ErrorCode.REVIEW_NOT_APPROVED, "Final context decision required");
    if (c.get("applied_result") != null) return parse(c.get("applied_result").toString());
    active(c);
    platform.authorize(id(c.get("live_id")), id(d.get("operatorId")), true);
    platform.verifyTargets(
        id(c.get("live_id")),
        (List<Map<String, Object>>) parse(c.get("snapshot").toString()).get("targets"));
    scopeLock(id(c.get("live_id")), id(c.get("user_id")));
    boolean violation = d.get("decision").equals("CONFIRM_VIOLATION");
    if (violation)
      confirm(c, (List<String>) d.get("categories"), "decision:" + d.get("decisionId"));
    else {
      platform.visibility(cId, "PUBLIC");
      db.sql(
              "UPDATE guard.live_case_state SET confirmation_status='CONFIRMED_NORMAL' WHERE"
                  + " case_id=?")
          .param(cId)
          .update();
      db.sql("UPDATE guard.cases SET status='NO_ACTION',version=version+1 WHERE id=?")
          .param(cId)
          .update();
    }
    var result = new LinkedHashMap<>(refs(c));
    result.put("observation", "CONFIRMED");
    result.put("status", violation ? "APPLIED" : "NO_ACTION");
    result.put("kickReview", ensureKick(cId));
    db.sql("UPDATE guard.live_case_state SET applied_result=?::jsonb WHERE case_id=?")
        .params(encode(result), cId)
        .update();
    platform.emit(id(c.get("live_id")),"CASE_UPDATED",caseEvent(cId),null,true);
    return parse(encode(result));
  }

  private void storeFinding(
      Map<String, Object> c,
      String cat,
      String verdict,
      String source,
      String status,
      String reference) {
    UUID f = UUID.randomUUID();
    db.sql(
            "INSERT INTO"
                + " guard.findings(id,case_id,category,source,verdict,status,policy_version,evidence_reference,evidence_version,created_at,confirmed_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?)")
        .params(
            f,
            c.get("id"),
            cat,
            source,
            verdict,
            status,
            c.get("policy_version"),
            reference,
            c.get("evidence_version"),
            now(),
            status.equals("CONFIRMED") ? now() : null)
        .update();
    db.sql(
            "INSERT INTO"
                + " guard.finding_events(id,finding_id,event_type,decision_reference,reason,created_at)"
                + " VALUES(?,?,?,?,?,?)")
        .params(UUID.randomUUID(), f, status, reference, "Validated " + source + " finding", now())
        .update();
  }

  private void confirm(Map<String, Object> c, List<String> cats, String reference) {
    UUID live = id(c.get("live_id")), user = id(c.get("user_id")), caseId = id(c.get("id"));
    scopeLock(live, user);
    int seconds = 0;
    for (String cat : new LinkedHashSet<>(cats)) {
      int previous =
          db.sql(
                  "SELECT count(DISTINCT c.id) FROM guard.cases c JOIN guard.findings f ON"
                      + " f.case_id=c.id WHERE c.live_id=? AND c.user_id=? AND c.id<>? AND"
                      + " f.category=? AND f.source='BACKEND' AND f.status='CONFIRMED' AND"
                      + " f.verdict='VIOLATION'")
              .params(live, user, caseId, cat)
              .query(Integer.class)
              .single();
      seconds=Math.max(seconds,TimeoutPolicy.seconds(cat,previous));
      storeFinding(c, cat, "VIOLATION", "BACKEND", "CONFIRMED", reference);
    }
    db.sql(
            "UPDATE guard.live_case_state SET confirmation_status='CONFIRMED_VIOLATION' WHERE"
                + " case_id=?")
        .param(caseId)
        .update();
    db.sql("UPDATE guard.cases SET status='HIDDEN',judged_at=?,version=version+1 WHERE id=?")
        .params(now(), caseId)
        .update();
    platform.visibility(caseId, "HIDDEN");
    bumpTarget(live,user);
    if (seconds > 0) {
      UUID action = UUID.randomUUID();
      db.sql(
              "INSERT INTO guard.live_actions(id,case_id,kind,status,created_at)"
                  + " VALUES(?,?,'TIMEOUT','SUCCEEDED',?)")
          .params(action, caseId, now())
          .update();
      db.sql(
              "INSERT INTO guard.live_timeouts(id,live_id,user_id,case_id,action_id,expires_at)"
                  + " VALUES(?,?,?,?,?,?)")
          .params(
              UUID.randomUUID(),
              live,
              user,
              caseId,
              action,
              Timestamp.from(clock.instant().plusSeconds(seconds)))
          .update();
    }
  }

  private int targetVersion(UUID live,UUID user) {
    db.sql("INSERT INTO guard.live_user_state(live_id,user_id) VALUES(?,?) ON CONFLICT DO NOTHING").params(live,user).update();
    return db.sql("SELECT version FROM guard.live_user_state WHERE live_id=? AND user_id=? FOR UPDATE").params(live,user).query(Integer.class).single();
  }
  void bumpTarget(UUID live,UUID user) {
    targetVersion(live,user);
    db.sql("UPDATE guard.live_user_state SET version=version+1 WHERE live_id=? AND user_id=?").params(live,user).update();
  }

  private Map<String, Object> ensureKick(UUID cId) {
    var c = row(cId, false);
    scopeLock(id(c.get("live_id")), id(c.get("user_id")));
    var existing = kickFor(cId);
    if (Boolean.TRUE.equals(existing.get("required"))) return existing;
    var candidates =
        db.sql(
                "SELECT c.id FROM guard.cases c WHERE c.live_id=? AND c.user_id=? AND EXISTS(SELECT"
                    + " 1 FROM guard.findings f WHERE f.case_id=c.id AND f.source='BACKEND' AND"
                    + " f.status='CONFIRMED' AND f.verdict='VIOLATION' AND f.confirmed_at>?) AND"
                    + " NOT EXISTS(SELECT 1 FROM guard.live_reservations r WHERE r.case_id=c.id AND r.state IN ('RESERVED','CONSUMED'))"
                    + " ORDER BY c.judged_at,c.id LIMIT 3")
            .params(
                c.get("live_id"),
                c.get("user_id"),
                Timestamp.from(clock.instant().minusSeconds(86400)))
            .query(UUID.class)
            .list();
    if (candidates.size() < 3) return Map.of("required", false);
    UUID review = createReview(cId, "KICK", candidates);
    for (UUID basis : candidates)
      db.sql("INSERT INTO guard.live_reservations(case_id,review_id,state) VALUES(?,?,'RESERVED') ON CONFLICT(case_id) DO UPDATE SET review_id=EXCLUDED.review_id,state='RESERVED' WHERE guard.live_reservations.state='RELEASED'")
          .params(basis, review)
          .update();
    return kickFor(cId);
  }

  private Map<String, Object> kickFor(UUID c) {
    var r =
        db.sql("SELECT id FROM guard.reviews WHERE case_id=? AND kind='KICK' AND status='PENDING'")
            .param(c)
            .query(UUID.class)
            .optional();
    return r.isEmpty()
        ? Map.of("required", false)
        : Map.of(
            "required", true, "reviewId", r.get(), "basisCaseIds", basis(review(r.get(), false)));
  }

  public Map<String, Object> revalidate(UUID r, UUID decision) {
    var rev = review(r, true);
    var c = row(id(rev.get("case_id")), true);
    active(c);
    var d = decision(r, decision, true);
    var result = new LinkedHashMap<>(refs(c));
    result.put("valid",false);result.put("validationRef",null);result.put("basisCaseIds",basis(rev));
    result.put("reviewId", r);
    result.put("decisionId", decision);
    if (!rev.get("kind").equals("KICK") || !d.get("decision").equals("APPROVE")) {
      result.put("status", "REJECTED");
      return parse(encode(result));
    }
    platform.authorize(id(c.get("live_id")), id(d.get("operatorId")), true);
    if (!validBasis(r, c)) {
      result.put("status", "INVALID");
      db.sql("UPDATE guard.reviews SET status='INVALIDATED',version=version+1 WHERE id=?").param(r).update();
      return parse(encode(result));
    }
    var old =
        db.sql("SELECT command::text FROM guard.live_actions WHERE decision_id=? AND kind='KICK'")
            .param(decision)
            .query(String.class)
            .optional();
    Map<String, Object> command;
    if (old.isPresent()) {
      command=parse(old.get());
      if(((Number)command.get("expectedTargetVersion")).intValue()!=targetVersion(id(c.get("live_id")),id(c.get("user_id")))) {
        db.sql("UPDATE guard.reviews SET status='INVALIDATED',version=version+1 WHERE id=?").param(r).update();
        result.put("status","INVALID");return parse(encode(result));
      }
    }
    else {
      UUID action = UUID.randomUUID();
      command = new LinkedHashMap<>();
      command.put("actionId", action);
      command.put("planId", action);
      command.put("caseId", c.get("id"));
      command.put("liveId", c.get("live_id"));
      command.put("type", "KICK");
      command.put("targetId", c.get("user_id"));
      command.put("expectedEvidenceVersion", c.get("evidence_version"));
      command.put("expectedTargetVersion", targetVersion(id(c.get("live_id")),id(c.get("user_id"))));
      command.put("policyVersion", c.get("policy_version"));
      command.put("idempotencyKey", action.toString());
      command.put("decisionId", decision);
      command.put("reviewId", r);
      command.put("basisSnapshotId", rev.get("basis_snapshot_id"));
      command.put("validationRef", UUID.randomUUID());
      command.put("basisCaseIds", basis(rev));
      db.sql(
              "INSERT INTO"
                  + " guard.live_actions(id,case_id,decision_id,kind,status,command,created_at)"
                  + " VALUES(?,?,?,'KICK','PENDING',?::jsonb,?)")
          .params(action, c.get("id"), decision, encode(command), now())
          .update();
    }
    result.put("status", "APPROVED");
    result.put("valid", true);
    result.put("validationRef", command.get("validationRef"));
    result.put("basisCaseIds", basis(rev));
    result.put("actionCommand", command);
    return parse(encode(result));
  }

  private boolean validBasis(UUID r, Map<String, Object> c) {
    for (UUID b : basis(review(r, false))) {
      int valid =
          db.sql(
                  "SELECT count(*) FROM guard.live_reservations res JOIN guard.cases c ON"
                      + " c.id=res.case_id WHERE res.state='CONSUMED' AND res.review_id=? AND res.case_id=? AND c.live_id=?"
                      + " AND c.user_id=? AND EXISTS(SELECT 1 FROM guard.findings f WHERE"
                      + " f.case_id=c.id AND f.status='CONFIRMED' AND f.source='BACKEND' AND"
                      + " f.verdict='VIOLATION' AND f.confirmed_at>?)")
              .params(
                  r,
                  b,
                  c.get("live_id"),
                  c.get("user_id"),
                  Timestamp.from(clock.instant().minusSeconds(86400)))
              .query(Integer.class)
              .single();
      if (valid != 1) return false;
    }
    return basis(review(r, false)).size() == 3;
  }

  public Map<String, Object> execute(UUID a, Map<String, Object> command) {
    UUID owner=db.sql("SELECT case_id FROM guard.live_actions WHERE id=?").param(a).query(UUID.class).optional()
      .orElseThrow(()->error(ErrorCode.NOT_FOUND,"Action not found"));
    row(owner,true);
    var stored =
        db.sql("SELECT * FROM guard.live_actions WHERE id=? FOR UPDATE")
            .param(a)
            .query(new org.springframework.jdbc.core.ColumnMapRowMapper())
            .optional()
            .orElseThrow(() -> error(ErrorCode.NOT_FOUND, "Action not found"));
    if (stored.get("command") == null || !parse(stored.get("command").toString()).equals(command))
      throw error(ErrorCode.REVIEW_NOT_APPROVED, "Backend-issued command required");
    if (stored.get("result") != null) return parse(stored.get("result").toString());
    var c = row(id(stored.get("case_id")), true);
    active(c);
    UUID r = id(command.get("reviewId")), d = id(command.get("decisionId"));
    if (!decision(r, d, true).get("decision").equals("APPROVE") || !validBasis(r, c))
      throw error(ErrorCode.REVIEW_NOT_APPROVED, "Kick no longer approved");
    scopeLock(id(c.get("live_id")), id(c.get("user_id")));
    if(((Number)command.get("expectedTargetVersion")).intValue()!=targetVersion(id(c.get("live_id")),id(c.get("user_id"))))
      throw error(ErrorCode.STALE_VERSION,"Target moderation state changed");
    db.sql(
            "INSERT INTO guard.live_kicks(live_id,user_id,review_id,action_id) VALUES(?,?,?,?) ON"
                + " CONFLICT(review_id) DO NOTHING")
        .params(c.get("live_id"), c.get("user_id"), r, a)
        .update();
    var result = new LinkedHashMap<String, Object>();
    result.put("actionId", a);
    result.put("caseId", c.get("id"));
    result.put("liveId", c.get("live_id"));
    result.put("type", "KICK");
    result.put("status", "SUCCEEDED");
    result.put("observation", "CONFIRMED");
    result.put("applied", true);
    result.put("attemptCount", 1);
    result.put("retryCount", 0);
    result.put("startedAt", clock.instant());
    db.sql("UPDATE guard.live_actions SET result=?::jsonb,status='SUCCEEDED' WHERE id=?")
        .params(encode(result), a)
        .update();
    platform.emit(
        id(c.get("live_id")),
        "LIVE_ACCESS_BLOCKED",
        Map.of("liveId", c.get("live_id"), "activeUntilLiveEnd", true),
        id(c.get("user_id")),
        true);
    platform.emit(id(c.get("live_id")), "ACTION_APPLIED", result, id(c.get("user_id")), true);
    return parse(encode(result));
  }
}
