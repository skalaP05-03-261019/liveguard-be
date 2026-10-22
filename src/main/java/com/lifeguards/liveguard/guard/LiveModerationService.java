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
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
public class LiveModerationService implements LiveGuardPort {
  private final JdbcClient db;
  private final Clock clock;
  private final LivePlatformPort platform;
  private final JsonMapper json;
  private final AgentTaskOutbox outbox;
  private final FindingService findings;
  private final PolicyProperties policy;
  private final LiveMessageQuery messages;

  public LiveModerationService(
      JdbcClient db,
      Clock clock,
      @Lazy LivePlatformPort platform,
      JsonMapper json,
      AgentTaskOutbox outbox, FindingService findings, PolicyProperties policy, LiveMessageQuery messages) {
    this.db = db;
    this.clock = clock;
    this.platform = platform;
    this.json = json;
    this.outbox = outbox;
    this.findings = findings;
    this.policy = policy;
    this.messages = messages;
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
    if(platform.live(live).endedAt()!=null)return;
    if (db.sql("SELECT count(*) FROM guard.live_kicks WHERE live_id=? AND user_id=?")
            .params(live, user)
            .query(Integer.class)
            .single()
        > 0) throw error(ErrorCode.KICKED, "Access blocked until live ends");
  }

  @Override
  public void checkAccess(UUID live, UUID user) {
    checkEntry(live, user);
    if(platform.live(live).endedAt()!=null)return;
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
    if(platform.live(live).endedAt()!=null)return null;
    var value=db.sql("SELECT max(expires_at) FROM guard.live_timeouts WHERE live_id=? AND user_id=? AND revoked_at IS NULL AND expires_at>?")
      .params(live,user,now()).query((r,n)->r.getTimestamp(1)).optional();
    return value.map(Timestamp::toInstant).orElse(null);
  }

  @Override
  public UUID candidate(CandidateRequest request) {
    com.lifeguards.liveguard.contracts.dto.DtoValidation.requireNonNull(request,"request");
    UUID live=request.liveId(), channel=request.channelId(), user=request.userId(), message=request.messageId();
    String route=request.handlingRoute().name(), policy=request.policyVersion();
    List<String> tags=request.matchingTags();
    Instant at=request.sentAt();
    if(request.filterResult()==FilterResult.PASS
        || request.filterResult()!=FilterResult.FLOOD&&!request.relatedMessageIds().isEmpty()
        || request.relatedMessageIds().contains(message)
        || request.relatedMessageIds().stream().distinct().count()!=request.relatedMessageIds().size())
      throw new com.lifeguards.liveguard.contracts.exception.InvalidContractException("Invalid candidate evidence");
    var prior=db.sql("SELECT case_id,request::text FROM guard.candidates WHERE message_id=?")
      .param(message).query(new org.springframework.jdbc.core.ColumnMapRowMapper()).optional();
    if(prior.isPresent()) {
      if(prior.get().get("request")==null||!json.readTree(encode(request)).equals(json.readTree(prior.get().get("request").toString())))
        throw error(ErrorCode.IDEMPOTENCY_CONFLICT,"Candidate provenance differs or is unavailable");
      return id(prior.get().get("case_id"));
    }
    // platform 메시지는 조회 포트로만 읽는다(다른 방송이거나 없는 메시지면 포트가 실패한다)
    MessageView stored=messageInLive(live,List.of(message),"Candidate message scope or filter provenance differs").getFirst();
    // 방송·채널 일치는 messages(live_id, channel_id) → lives(id, channel_id) FK가 보장한다. 저장 시각은 마이크로초다
    if(!stored.channelId().equals(channel)||!stored.authorId().equals(user)||!stored.sentAt().equals(micros(at))
        ||stored.filterResult()!=request.filterResult())
      throw new com.lifeguards.liveguard.contracts.exception.InvalidContractException("Candidate message scope or filter provenance differs");
    if(!this.policy.version().equals(policy))
      throw error(ErrorCode.VALIDATION_FAILED,"Candidate policy version is not active");
    platform.lockScope(live);
    scopeLock(live,user);
    // Another opener can commit while this call waits for the live/scope lock.
    var concurrent=db.sql("SELECT case_id,request::text FROM guard.candidates WHERE message_id=?")
      .param(message).query(new org.springframework.jdbc.core.ColumnMapRowMapper()).optional();
    if(concurrent.isPresent()) {
      if(concurrent.get().get("request")==null||!json.readTree(encode(request)).equals(json.readTree(concurrent.get().get("request").toString())))
        throw error(ErrorCode.IDEMPOTENCY_CONFLICT,"Candidate provenance differs");
      return id(concurrent.get().get("case_id"));
    }
    platform.lockActive(live);
    // 도배 표식(flood_marks.text_hash)과 같은 norm-v1 문장 해시. 정규화 문장 자체는 포트가 넘기지 않는다
    String normalizedHash=stored.normalizedTextHash();
    for(MessageView related:messageInLive(live,request.relatedMessageIds(),"Related message is outside candidate scope")) {
      boolean valid=related.channelId().equals(channel)&&related.authorId().equals(user)
        &&related.normalizedTextHash().equals(normalizedHash)
        &&related.sentAt().isAfter(at.minusSeconds(10))&&!related.sentAt().isAfter(at);
      if(!valid)throw new com.lifeguards.liveguard.contracts.exception.InvalidContractException("Related message is outside candidate scope");
    }
    if(route.equals("RULE_CONFIRMED")
        && !(com.lifeguards.liveguard.filter.MessageFilter.RULE_CERTAIN_PROFANITY.equals(request.ruleId())&&tags.contains("CERTAIN_PROFANITY")
          || com.lifeguards.liveguard.filter.MessageFilter.RULE_LINK_BLOCKED.equals(request.ruleId())&&tags.contains("LINK")))
      throw new com.lifeguards.liveguard.contracts.exception.InvalidContractException("Known rule and matching evidence required");
    if(route.equals("FLOOD_REVIEW")) {
      var existing=db.sql("SELECT m.case_id FROM guard.flood_marks m WHERE m.live_id=? AND m.user_id=? AND m.text_hash=? AND m.last_sent_at>? AND EXISTS(SELECT 1 FROM guard.findings f WHERE f.case_id=m.case_id AND f.category='FLOOD' AND f.status='CONFIRMED') ORDER BY m.confirmed_at DESC LIMIT 1")
        .params(live,user,normalizedHash,Timestamp.from(at.minusSeconds(10))).query(UUID.class).optional();
      if(existing.isPresent()) {
        UUID owner=existing.get();
        UUID nextSnapshot=UUID.randomUUID();
        int evidence=db.sql("UPDATE guard.cases SET evidence_version=evidence_version+1,version=version+1,snapshot_id=? WHERE id=? RETURNING evidence_version")
          .params(nextSnapshot,owner).query(Integer.class).single();
        linkEvidence(owner,request);
        persistCandidate(owner,nextSnapshot,evidence,request);
        db.sql("UPDATE guard.flood_marks SET last_sent_at=? WHERE case_id=?").params(Timestamp.from(at),owner).update();
        platform.visibility(owner,"HIDDEN");
        freeze(owner,request,nextSnapshot,evidence);
        reanalyzeAfterEvidence(owner);
        platform.emit(live,"CASE_UPDATED",caseEvent(owner),null,true);
        return owner;
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
    persistCandidate(c,snapshot,1,request);
    linkEvidence(c,request);
    db.sql("INSERT INTO guard.live_case_state(case_id,task_id,thread_id,snapshot) VALUES(?,?,?,'{}'::jsonb)")
      .params(c,task,thread).update();
    freeze(c,request,snapshot,1);
    if(route.equals("FLOOD_REVIEW")) {
      confirm(row(c,true),List.of("FLOOD"),"rule:flood:"+message);
      db.sql("INSERT INTO guard.flood_marks(live_id,user_id,text_hash,case_id,confirmed_at,last_sent_at) VALUES(?,?,?,?,?,?)")
        .params(live,user,normalizedHash,c,now(),Timestamp.from(at)).update();
      if(Boolean.TRUE.equals(ensureKick(c).get("required")))enqueue(row(c,false),null,null,null);
    } else if (route.equals("RULE_CONFIRMED")) {
      List<String> ruleCategories=new ArrayList<>();
      if(tags.contains("CERTAIN_PROFANITY"))ruleCategories.add("PROFANITY");
      if(tags.contains("LINK"))ruleCategories.add("PHISHING");
      confirm(row(c,true),ruleCategories,"rule:"+request.ruleId()+":"+message);
      var kick = ensureKick(c);
      if (Boolean.TRUE.equals(kick.get("required"))) enqueue(row(c, false), null, null, null);
    } else {
      enqueue(row(c, false), null, null, null);
    }
    platform.emit(live,"CASE_CREATED",caseEvent(c),null,true);
    return c;
  }

  private void persistCandidate(UUID c,UUID snapshot,int evidence,CandidateRequest r) {
    db.sql("INSERT INTO guard.candidates(message_id,case_id,filter_result,matching_tags,filter_version,related_message_ids,sent_at,snapshot_id,evidence_version,live_id,handling_route,rule_id,policy_version,request) VALUES(?,?,?,string_to_array(?,','),?,string_to_array(?,',')::uuid[],?,?,?,?,?,?,?,?::jsonb)")
      .params(r.messageId(),c,r.filterResult().name(),String.join(",",r.matchingTags()),r.filterVersion(),
        String.join(",",r.relatedMessageIds().stream().map(UUID::toString).toList()),Timestamp.from(r.sentAt()),snapshot,evidence,
        r.liveId(),r.handlingRoute().name(),r.ruleId(),r.policyVersion(),encode(r)).update();
  }

  private void linkEvidence(UUID c,CandidateRequest r) {
    for(UUID message:r.relatedMessageIds())
      db.sql("INSERT INTO guard.case_messages(case_id,message_id,owned,is_target) VALUES(?,?,false,false) ON CONFLICT DO NOTHING")
        .params(c,message).update();
    db.sql("INSERT INTO guard.case_messages(case_id,message_id,owned,is_target) VALUES(?,?,true,true) ON CONFLICT(case_id,message_id) DO UPDATE SET owned=true,is_target=true")
      .params(c,r.messageId()).update();
    platform.bindCase(r.messageId(),c,r.handlingRoute().name());
  }

  private void freeze(UUID c,CandidateRequest r,UUID snapshot,int evidence) {
    var evidenceIds = db.sql("SELECT message_id FROM guard.case_messages WHERE case_id=? ORDER BY message_id")
        .param(c).query(UUID.class).list();
    var messages = platform.snapshot(r.liveId(), r.messageId(), r.userId(), evidenceIds);
    Map<String, Object> frozen = new LinkedHashMap<>();
    frozen.put("caseId", c);
    frozen.put("channelId", r.channelId());
    frozen.put("liveId", r.liveId());
    frozen.put("userId", r.userId());
    frozen.put("snapshotId", snapshot);
    frozen.put("evidenceVersion", evidence);
    frozen.put("policyVersion", r.policyVersion());
    frozen.put("handlingRoute", r.handlingRoute().name());
    frozen.put("filterResult", r.filterResult().name());
    frozen.put("matchingTags", r.matchingTags());
    frozen.put("version", row(c,false).get("version"));
    frozen.put("messages", messages);
    frozen.put("evidenceMessageIds", evidenceIds);
    var targets=currentTargets(c);
    frozen.put("targets",targets);
    frozen.put("targetMessageIds",targets.stream().map(t->t.get("messageId")).toList());
    frozen.put("targetVersions",targets.stream().collect(java.util.stream.Collectors.toMap(t->t.get("messageId").toString(),t->t.get("version"))));
    db.sql("UPDATE guard.live_case_state SET snapshot=?::jsonb WHERE case_id=?").params(encode(frozen),c).update();
    db.sql("INSERT INTO guard.case_snapshots(snapshot_id,case_id,evidence_version,snapshot) VALUES(?,?,?,?::jsonb)")
      .params(snapshot,c,evidence,encode(frozen)).update();
  }

  private List<Map<String,Object>> currentTargets(UUID c) {
    var rows=db.sql("SELECT cm.message_id,c.live_id FROM guard.case_messages cm JOIN guard.cases c ON c.id=cm.case_id"
        + " WHERE cm.case_id=? AND cm.owned AND cm.is_target")
      .param(c).query((rs,n)->Map.entry(rs.getObject(1,UUID.class),Optional.ofNullable(rs.getObject(2,UUID.class)))).list();
    if(rows.isEmpty()||rows.getFirst().getValue().isEmpty())return List.of();
    // 지금의 가시성·버전은 조회 포트로 읽는다
    return messages.messages(rows.getFirst().getValue().get(),rows.stream().map(Map.Entry::getKey).toList()).stream()
      .sorted(MessageOrder.BY_SENT_AT)
      .map(v->Map.<String,Object>of("messageId",v.messageId(),"visibility",v.visibility().name(),"version",v.version()))
      .toList();
  }

  /** PostgreSQL timestamptz처럼 마이크로초로 반올림한다. */
  private static Instant micros(Instant at) {
    return at.plusNanos(500).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  /** 조회 포트로 이 방송의 메시지를 읽는다. 다른 방송이거나 없는 메시지면 후보 계약 위반으로 본다. */
  private List<MessageView> messageInLive(UUID live,List<UUID> ids,String violation) {
    try {
      return messages.messages(live,ids);
    } catch(ApiException ex) {
      // 다른 방송(VALIDATION_FAILED)인지 없는 메시지(NOT_FOUND)인지 남긴다. 본문은 싣지 않는다
      throw new com.lifeguards.liveguard.contracts.exception.InvalidContractException(violation+" ("+ex.code()+")");
    }
  }

  private String textHash(String text) {
    try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  }
  @Override public boolean floodRecurrence(UUID live,UUID user,String text,Instant at) {
    return db.sql("SELECT count(*) FROM guard.flood_marks m WHERE m.live_id=? AND m.user_id=? AND m.text_hash=? AND m.confirmed_at>? AND m.last_sent_at<=? AND EXISTS(SELECT 1 FROM guard.findings f WHERE f.case_id=m.case_id AND f.category='FLOOD' AND f.status='CONFIRMED')")
      .params(live,user,textHash(text),Timestamp.from(at.minusSeconds(86400)),Timestamp.from(at.minusSeconds(10))).query(Integer.class).single()>0;
  }

  /**
   * FLOOD 증거가 추가되어 snapshot이 바뀌면, 이미 넣은 Agent 분석은 이전 snapshot을 가리켜 Agent가 STALE_EVIDENCE로 멈춘다.
   * 강퇴 검토가 아직 결정되지 않았으면 새 작업(taskId·threadId)으로 새 snapshot을 다시 분석하게 하고, 아직 보내지 않은
   * 이전 작업은 멈춘다. 이미 보낸 이전 작업은 Agent가 snapshot 불일치로 멈춘다. 결정된 뒤에는 강퇴 판단 근거(사건 3건)가
   * 바뀌지 않으므로 다시 분석하지 않는다(재개 작업은 결정 때의 snapshot으로 이미 묶여 있다).
   */
  private void reanalyzeAfterEvidence(UUID c) {
    UUID previous=db.sql("SELECT task_id FROM guard.live_case_state WHERE case_id=?").param(c).query(UUID.class).single();
    boolean dispatched=db.sql("SELECT EXISTS(SELECT 1 FROM guard.agent_task_outbox WHERE task_id=?)")
      .param(previous).query(Boolean.class).single();
    boolean decided=db.sql("SELECT EXISTS(SELECT 1 FROM guard.reviews WHERE case_id=? AND status<>'PENDING')")
      .param(c).query(Boolean.class).single();
    if(!dispatched||decided)return;
    db.sql("UPDATE guard.agent_task_outbox SET status='FAILED',last_error_code='SUPERSEDED',updated_at=? WHERE task_id=? AND status IN ('PENDING','RETRY')")
      .params(now(),previous).update();
    var current=row(c,false);
    UUID task=UUID.randomUUID();
    String thread="case:"+c+":analysis:"+current.get("evidence_version");
    db.sql("UPDATE guard.live_case_state SET task_id=?,thread_id=? WHERE case_id=?").params(task,thread,c).update();
    outbox.enqueue(new AgentTaskEnvelope(UUID.randomUUID(),task,c,AgentTaskEnvelope.Kind.REANALYZE,thread,
        id(current.get("snapshot_id")),((Number)current.get("evidence_version")).intValue(),null,null,null,UUID.randomUUID()));
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

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Map<String, Object> snapshot(UUID c) {
    if(!owns(c)) {
      boolean exists=db.sql("SELECT EXISTS(SELECT 1 FROM guard.cases WHERE id=?)").param(c).query(Boolean.class).single();
      throw error(exists?ErrorCode.CASE_NOT_READY:ErrorCode.NOT_FOUND,"Case immutable evidence is unavailable");
    }
    var current = row(c, false);
    if(current.get("live_id")==null||current.get("policy_version")==null||current.get("handling_route")==null)
      throw error(ErrorCode.CASE_NOT_READY,"Case provenance is unavailable");
    Map<String, Object> result = new LinkedHashMap<>(parse(current.get("snapshot").toString()));
    if(!Objects.toString(current.get("snapshot_id")).equals(Objects.toString(result.get("snapshotId")))
        || !Objects.toString(current.get("evidence_version")).equals(Objects.toString(result.get("evidenceVersion")))
        || !db.sql("SELECT EXISTS(SELECT 1 FROM guard.case_snapshots WHERE snapshot_id=? AND case_id=? AND evidence_version=? AND snapshot=?::jsonb)")
          .params(current.get("snapshot_id"),c,current.get("evidence_version"),encode(result)).query(Boolean.class).single()
        || !db.sql("SELECT EXISTS(SELECT 1 FROM guard.candidates WHERE case_id=? AND snapshot_id=? AND evidence_version=? AND request IS NOT NULL)")
          .params(c,current.get("snapshot_id"),current.get("evidence_version")).query(Boolean.class).single()
        || !Objects.toString(current.get("live_id")).equals(Objects.toString(result.get("liveId")))
        || !Objects.toString(current.get("policy_version")).equals(Objects.toString(result.get("policyVersion")))
        || !Objects.toString(current.get("handling_route")).equals(Objects.toString(result.get("handlingRoute")))
        || currentTargets(c).isEmpty())
      throw error(ErrorCode.CASE_NOT_READY,"Immutable current evidence or targets are unavailable");
    result.put("confirmationStatus", current.get("confirmation_status"));
    result.put("kickReview", kickFor(c));
    return parse(encode(result));
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Map<String, Object> context(UUID c) {
    var snapshot = internalContext(c);
    return Map.of(
        "caseId",
        c,
        "snapshotId",
        snapshot.snapshotId(),
        "evidenceVersion",
        snapshot.evidenceVersion(),
        "messages",
        snapshot.messages(),
        "truncated",
        snapshot.truncated());
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
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
        currentTargets(c)
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
        ((Number) row(c,false).get("version")).intValue(),
        messages,
        targets,
        id(s.get("userId")),
        s.get("confirmationStatus").toString(),
        (Map<String, Object>) s.get("kickReview"));
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public com.lifeguards.liveguard.contracts.dto.response.ContextData internalContext(UUID c) {
    return internalContext(c,10,10);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public com.lifeguards.liveguard.contracts.dto.response.ContextData internalContext(UUID c,int before,int after) {
    if(before<0||before>10||after<0||after>10)
      throw new com.lifeguards.liveguard.contracts.exception.InvalidContractException("Invalid context range");
    var s = internalCase(c);
    List<com.lifeguards.liveguard.contracts.dto.response.MaskedMessage> messages=s.messages();
    Set<UUID> targets=new HashSet<>(s.targetMessageIds());
    var frozen = snapshot(c);
    if (frozen.get("evidenceMessageIds") instanceof List<?> evidenceIds) {
      evidenceIds.forEach(value -> targets.add(id(value)));
    }
    int first=0,last=messages.size()-1;
    while(first<messages.size()&&!targets.contains(messages.get(first).messageId()))first++;
    while(last>=0&&!targets.contains(messages.get(last).messageId()))last--;
    int from=Math.max(0,first-before),to=Math.min(messages.size(),last+after+1);
    var window=messages.subList(Math.min(from,to),to);
    List<Integer> anchors=new ArrayList<>(),keep=new ArrayList<>();
    for(int i=0;i<window.size();i++)if(targets.contains(window.get(i).messageId()))anchors.add(i);
    keep.addAll(anchors.subList(Math.max(0,anchors.size()-20),anchors.size()));
    List<Integer> surrounding=new ArrayList<>();
    for(int i=0;i<window.size();i++)if(!targets.contains(window.get(i).messageId()))surrounding.add(i);
    surrounding.sort(Comparator.comparingInt((Integer i)->anchors.stream().mapToInt(a->Math.abs(a-i)).min().orElse(Integer.MAX_VALUE)).thenComparingInt(i->i));
    for(int index:surrounding){if(keep.size()==20)break;keep.add(index);}
    var selected=keep.stream().sorted().map(window::get).toList();
    return new com.lifeguards.liveguard.contracts.dto.response.ContextData(
        selected, selected.size()<window.size(), c, s.snapshotId(), s.evidenceVersion());
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public com.lifeguards.liveguard.contracts.dto.response.UserHistory userHistory(UUID id) {
    var c=row(id,false);
    var items=db.sql("SELECT c.id,f.category,f.confirmed_at FROM guard.cases c JOIN guard.findings f ON f.case_id=c.id WHERE c.live_id=? AND c.user_id=? AND c.id<>? AND f.source='BACKEND' AND f.status='CONFIRMED' AND f.verdict='VIOLATION' AND f.revoked_at IS NULL AND f.confirmed_at<=? ORDER BY f.confirmed_at,c.id,f.category")
      .params(c.get("live_id"),c.get("user_id"),id,now()).query((r,n)->new com.lifeguards.liveguard.contracts.dto.response.UserHistory.RecentCase(r.getObject(1,UUID.class),Category.valueOf(r.getString(2)),r.getTimestamp(3).toInstant())).list();
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

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
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

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Map<String, Object> detail(UUID c, AuthenticatedUser user) {
    var current = row(c, false);
    platform.authorize(id(current.get("live_id")), user.userId(), true);
    var data = new LinkedHashMap<>(snapshot(c));
    var targets=currentTargets(c);
    data.put("targets",targets);
    data.put("targetMessageIds",targets.stream().map(t->t.get("messageId")).toList());
    data.put("targetVersions",targets.stream().collect(java.util.stream.Collectors.toMap(t->t.get("messageId").toString(),t->t.get("version"))));
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

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
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

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
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

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
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
    var input=new FindingService.FindingInput(id(c.get("id")),List.of(Category.valueOf(cat)),Verdict.valueOf(verdict),
      c.get("policy_version").toString(),reference,((Number)c.get("evidence_version")).intValue(),reference,
      "Validated "+source+" finding");
    if(status.equals("CONFIRMED"))findings.confirm(input);
    else findings.propose(input);
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
                      + " f.verdict='VIOLATION' AND f.revoked_at IS NULL AND f.confirmed_at<=?")
              .params(live, user, caseId, cat,now())
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
                    + " f.status='CONFIRMED' AND f.verdict='VIOLATION' AND f.revoked_at IS NULL AND f.confirmed_at>? AND f.confirmed_at<=?) AND"
                    + " NOT EXISTS(SELECT 1 FROM guard.live_reservations r WHERE r.case_id=c.id AND r.state IN ('RESERVED','CONSUMED'))"
                    + " ORDER BY (SELECT min(f.confirmed_at) FROM guard.findings f WHERE f.case_id=c.id AND f.source='BACKEND' AND f.status='CONFIRMED' AND f.verdict='VIOLATION' AND f.revoked_at IS NULL AND f.confirmed_at>? AND f.confirmed_at<=?),c.id LIMIT 3")
            .params(
                c.get("live_id"),
                c.get("user_id"),
                Timestamp.from(clock.instant().minusSeconds(86400)),now(),
                Timestamp.from(clock.instant().minusSeconds(86400)),now())
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
    var original=basis(review(r,false));
    if(original.size()!=3||original.stream().distinct().count()!=3)return false;
    for (UUID b : basis(review(r, false))) {
      int valid =
          db.sql(
                  "SELECT count(*) FROM guard.live_reservations res JOIN guard.cases c ON"
                      + " c.id=res.case_id WHERE res.state='CONSUMED' AND res.review_id=? AND res.case_id=? AND c.live_id=?"
                      + " AND c.user_id=? AND EXISTS(SELECT 1 FROM guard.findings f WHERE"
                      + " f.case_id=c.id AND f.status='CONFIRMED' AND f.source='BACKEND' AND"
                      + " f.verdict='VIOLATION' AND f.revoked_at IS NULL AND f.confirmed_at>? AND f.confirmed_at<=?)")
              .params(
                  r,
                  b,
                  c.get("live_id"),
                  c.get("user_id"),
                  Timestamp.from(clock.instant().minusSeconds(86400)),now())
              .query(Integer.class)
              .single();
      if (valid != 1) return false;
    }
    return basis(review(r, false)).size() == 3;
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public com.lifeguards.liveguard.contracts.dto.response.KickReviewBasis kickReviewBasis(UUID reviewId) {
    var r=review(reviewId,false);
    if(!r.get("kind").equals("KICK"))throw error(ErrorCode.VALIDATION_FAILED,"Kick review required");
    var c=row(id(r.get("case_id")),false);
    if(basis(r).size()!=3||basis(r).stream().distinct().count()!=3)
      throw error(ErrorCode.CASE_NOT_READY,"Fixed kick basis is incomplete");
    var decision=db.sql("SELECT id FROM guard.decisions WHERE review_id=? AND value IN ('APPROVE','REJECT')")
      .param(reviewId).query(UUID.class).optional()
      .orElseThrow(()->error(ErrorCode.CASE_NOT_READY,"Kick review has no final decision"));
    List<com.lifeguards.liveguard.contracts.dto.response.KickReviewBasis.Consumption> consumed=new ArrayList<>();
    for(UUID caseId:basis(r)) {
      consumed.addAll(db.sql("SELECT res.case_id,res.review_id,d.id FROM guard.live_reservations res JOIN guard.decisions d ON d.review_id=res.review_id AND d.value IN ('APPROVE','REJECT') WHERE res.case_id=? AND res.state='CONSUMED'")
        .param(caseId).query((rs,n)->new com.lifeguards.liveguard.contracts.dto.response.KickReviewBasis.Consumption(
          rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class))).list());
    }
    return new com.lifeguards.liveguard.contracts.dto.response.KickReviewBasis(reviewId,decision,
      id(c.get("live_id")),id(c.get("user_id")),id(r.get("basis_snapshot_id")),basis(r),consumed);
  }

  @org.springframework.transaction.event.TransactionalEventListener
  @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public void liveEnded(LiveEndedEvent event) {
    platform.lockScope(event.liveId());
    db.sql("UPDATE guard.reviews r SET status='INVALIDATED',version=r.version+1 FROM guard.cases c WHERE r.case_id=c.id AND c.live_id=? AND r.kind='KICK' AND r.status<>'INVALIDATED'")
      .param(event.liveId()).update();
    db.sql("UPDATE guard.live_reservations res SET state='RELEASED' FROM guard.reviews r JOIN guard.cases c ON c.id=r.case_id WHERE res.review_id=r.id AND c.live_id=? AND r.kind='KICK' AND res.state='RESERVED'")
      .param(event.liveId()).update();
    var pending=db.sql("SELECT a.id,a.case_id FROM guard.live_actions a JOIN guard.cases c ON c.id=a.case_id WHERE c.live_id=? AND a.kind='KICK' AND a.status='PENDING' AND a.result IS NULL FOR UPDATE OF a")
      .param(event.liveId()).query(new org.springframework.jdbc.core.ColumnMapRowMapper()).list();
    for(var action:pending) {
      var result=new LinkedHashMap<String,Object>();
      result.put("actionId",action.get("id"));result.put("caseId",action.get("case_id"));result.put("liveId",event.liveId());
      result.put("type","KICK");result.put("status","FAILED");result.put("observation","CONFIRMED");result.put("applied",false);
      result.put("attemptCount",0);result.put("retryCount",0);result.put("startedAt",null);result.put("errorCode","LIVE_ENDED");
      db.sql("UPDATE guard.live_actions SET status='FAILED',result=?::jsonb WHERE id=?")
        .params(encode(result),action.get("id")).update();
    }
    // Accepted/uncertain/in-flight delivery is not retroactively declared failed. Execution rechecks live state.
    db.sql("UPDATE guard.agent_task_outbox o SET status=CASE WHEN o.delivery_uncertain THEN 'UNKNOWN' ELSE 'FAILED' END,last_error_code='LIVE_ENDED',updated_at=?,publish_token=NULL,publish_lease_until=NULL FROM guard.reviews r JOIN guard.cases c ON c.id=r.case_id WHERE c.live_id=? AND r.kind='KICK' AND o.payload->>'reviewId'=r.id::text AND o.status IN ('PENDING','RETRY')")
      .params(Timestamp.from(event.endedAt()),event.liveId()).update();
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
    var approvedDecision=decision(r,d,true);
    platform.authorize(id(c.get("live_id")),id(approvedDecision.get("operatorId")),true);
    if (!approvedDecision.get("decision").equals("APPROVE") || !validBasis(r, c))
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
