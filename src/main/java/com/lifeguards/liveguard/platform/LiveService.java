package com.lifeguards.liveguard.platform;

import com.lifeguards.liveguard.common.*;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.request.SendMessageRequest;
import com.lifeguards.liveguard.contracts.dto.response.Live;
import com.lifeguards.liveguard.contracts.enums.*;
import com.lifeguards.liveguard.filter.*;
import com.lifeguards.liveguard.platform.port.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiveService implements LivePlatformPort {
  private final JdbcClient db;
  private final Clock clock;
  private final LiveGuardPort guard;
  private final CandidateSink candidates;
  private final KickStatusQuery legacyKicks;
  private final MessageFilter filter;
  private final MessageRepository messages;
  private final ChannelEventHub legacyEvents;
  private final JdbcLiveQuery liveQueries;
  private final org.springframework.context.ApplicationEventPublisher applicationEvents;
  private final SseRegistry chats = new SseRegistry("live-chat-v1", Duration.ofSeconds(15));
  private final SseRegistry events = new SseRegistry("live-guard-v1", Duration.ofSeconds(15));
  private final String policy;

  public LiveService(
      JdbcClient db,
      Clock clock,
      LiveGuardPort guard,
      CandidateSink candidates,
      KickStatusQuery legacyKicks,
      MessageFilter filter,
      MessageRepository messages,
      ChannelEventHub legacyEvents,
      JdbcLiveQuery liveQueries,
      org.springframework.context.ApplicationEventPublisher applicationEvents,
      @Value("${liveguard.policy.version:v3.1}") String policy) {
    this.db = db;
    this.clock = clock;
    this.guard = guard;
    this.candidates = candidates;
    this.legacyKicks = legacyKicks;
    this.filter = filter;
    this.messages = messages;
    this.legacyEvents=legacyEvents;
    this.liveQueries = liveQueries;
    this.applicationEvents = applicationEvents;
    this.policy = policy;
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
  }

  @Override
  public Live live(UUID id) {
    return liveQueries.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Live not found"));
  }

  public boolean integrationEnabled() { return true; }

  @Override
  public void authorize(UUID liveId, UUID userId, boolean operator) {
    authorizeChannel(live(liveId).channelId(), userId, operator);
  }

  void authorizeChannel(UUID channelId, UUID userId, boolean operator) {
    var role =
        db.sql("SELECT member_role FROM platform.channel_members WHERE channel_id=? AND user_id=?")
            .params(channelId, userId)
            .query(String.class)
            .optional();
    if (role.isEmpty() || (operator && !role.get().equals("MODERATOR")))
      throw new ApiException(ErrorCode.FORBIDDEN, "Channel permission required");
  }

  private void requireHost(UUID channel,UUID user) {
    String role=db.sql("SELECT member_role FROM platform.channel_members WHERE channel_id=? AND user_id=?")
      .params(channel,user).query(String.class).optional().orElse("");
    if(!Set.of("STREAMER","MODERATOR").contains(role)) throw new ApiException(ErrorCode.FORBIDDEN,"Only hosts can manage a live");
  }

  @Override public void lockScope(UUID id){lockLive(id);}

  @Override public void bindCase(UUID message,UUID caseId,String route) {
    db.sql("UPDATE platform.messages SET case_id=? WHERE id=?").params(caseId,message).update();
    db.sql("INSERT INTO platform.message_hold_reasons(message_id,case_id,reason,created_at) VALUES(?,?,?,?) ON CONFLICT DO NOTHING")
      .params(message,caseId,route,now()).update();
  }

  // Lifecycle uses the team PR #34 repository and its transaction/end event contract.
  @Transactional
  public Live start(UUID channel, AuthenticatedUser user) {
    requireHost(channel,user.userId());
    return liveQueries.start(channel,now().toInstant()).orElseThrow(() ->
        new ApiException(ErrorCode.LIVE_ALREADY_ACTIVE,"A live is already in progress in this channel"));
  }

  @Transactional(readOnly=true)
  public Live current(UUID channel, AuthenticatedUser user) {
    authorizeChannel(channel,user.userId(),false);
    return liveQueries.currentLive(channel).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,"No live in progress"));
  }

  @Transactional
  public Live end(UUID id, AuthenticatedUser user) {
    requireHost(live(id).channelId(),user.userId());
    Live ended=liveQueries.end(id,now().toInstant()).orElseThrow(() -> new ApiException(ErrorCode.LIVE_ENDED,"Live has already ended"));
    applicationEvents.publishEvent(new LiveEndedEvent(ended.liveId(),ended.channelId(),ended.endedAt()));
    return ended;
  }

  @org.springframework.transaction.event.TransactionalEventListener
  void ended(LiveEndedEvent event) {
    emit(event.liveId(),"LIVE_ENDED",Map.of("liveId",event.liveId()),null,false);
  }

  private void lockLive(UUID id) {
    db.sql("SELECT id FROM platform.lives WHERE id=? FOR UPDATE")
        .param(id)
        .query(UUID.class)
        .single();
  }

  private void active(UUID id) {
    if (live(id).endedAt() != null) throw new ApiException(ErrorCode.LIVE_ENDED, "Live ended");
  }

  @Override
  public void lockActive(UUID id) {
    lockLive(id);
    active(id);
  }

  @Override
  public void verifyTargets(UUID live, List<Map<String, Object>> targets) {
    for (var t : targets) {
      int count =
          db.sql(
                  "SELECT count(*) FROM platform.messages WHERE live_id=? AND id=? AND version=?"
                      + " AND visibility=?")
              .params(
                  live,
                  UUID.fromString(t.get("messageId").toString()),
                  t.get("version"),
                  t.get("visibility"))
              .query(Integer.class)
              .single();
      if (count != 1)
        throw new ApiException(ErrorCode.STALE_VERSION, "Target visibility or version changed");
    }
  }

  @Transactional
  public Map<String,Object> sendToChannel(UUID channel,AuthenticatedUser user,SendMessageRequest req) {
    authorizeChannel(channel,user.userId(),false);
    messages.lockAuthorInChannel(channel,user.userId());
    var previous=messages.find(channel,user.userId(),req.clientMessageId());
    if(previous.isPresent()) {
      var old=previous.get();
      if(old.liveId()!=null) return send(old.liveId(),user,req);
      if(!old.text().equals(req.text())) throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,"Message request conflict");
      return message(old.id(),user.userId());
    }
    UUID id=liveQueries.currentLive(channel).map(Live::liveId)
      .orElseThrow(()->new ApiException(ErrorCode.LIVE_ENDED,"No live in progress"));
    return send(id,user,req);
  }

  @Transactional
  public Map<String, Object> send(UUID id, AuthenticatedUser user, SendMessageRequest req) {
    authorize(id, user.userId(), false);
    var live = live(id);
    legacyKicks.activeKickUntil(live.channelId(),user.userId()).ifPresent(until->{
      throw new ApiException(ErrorCode.KICKED,"Kicked from this channel",until,null);
    });
    guard.checkEntry(id,user.userId());
    if (req.text().indexOf(0)>=0) throw new ApiException(ErrorCode.VALIDATION_FAILED,"Unsupported text");
    messages.lockAuthorInChannel(live.channelId(),user.userId());
    lockLive(id);
    guard.checkAccess(id,user.userId());
    var old=messages.find(live.channelId(),user.userId(),req.clientMessageId());
    if(old.isPresent()) {
      if(!old.get().text().equals(req.text())) throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,"Message request conflict");
      return message(old.get().id(),user.userId());
    }
    active(id);
    Timestamp at = now();
    String normalized = TextNormalizer.normalize(req.text());
    int repeated =
        db.sql(
                    "SELECT count(*) FROM platform.messages WHERE live_id=? AND author_id=? AND"
                        + " normalized_text=? AND sent_at>?")
                .params(
                    id, user.userId(), normalized, Timestamp.from(at.toInstant().minusSeconds(10)))
                .query(Integer.class)
                .single()
            + 1;
    var d =
        filter.classify(
            new FilterInput(
                req.text(), normalized, repeated, guard.floodRecurrence(id,user.userId(),normalized,at.toInstant())));
    String route = d.handlingRoute().name();
    String visibility = d.visibility().name();
    String reasonCode=d.moderationReasonCode()==null?null:d.moderationReasonCode().name();
    UUID message = UUID.randomUUID();
    db.sql(
            "INSERT INTO"
                + " platform.messages(id,channel_id,live_id,author_id,client_message_id,text,normalized_text,filter_result,visibility,moderation_reason_code,sent_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?)")
        .params(
            message,
            live.channelId(),
            id,
            user.userId(),
            req.clientMessageId(),
            req.text(),
            normalized,
            d.result().name(),
            visibility,
            reasonCode,
            at)
        .update();
    if (!route.equals("PASS")) {
      List<UUID> related=d.result()==FilterResult.FLOOD
        ? messages.sameTextMessageIds(id,user.userId(),normalized,at.toInstant().minusSeconds(10)).stream().filter(m->!m.equals(message)).toList()
        : List.of();
      try {
        UUID caseId=candidates.open(new CandidateRequest(live.channelId(),id,user.userId(),message,d.result(),
          d.handlingRoute(),d.ruleId(),d.matchingTags(),MessageFilter.version(),policy,at.toInstant(),related));
        db.sql("UPDATE platform.messages SET case_id=? WHERE id=?").params(caseId,message).update();
      } catch(ApiException ex) {
        throw ex;
      } catch(RuntimeException ex) {
        throw new ApiException(ErrorCode.TEMPORARY_UNAVAILABLE,"Candidate processing unavailable");
      }
    }
    var result = message(message, user.userId());
    applicationEvents.publishEvent(new MessageCreatedEvent(live.channelId(),user.userId(),
      new com.lifeguards.liveguard.contracts.dto.event.ChatEvent(message,id,result.get("authorAlias").toString(),
        req.text(),Visibility.valueOf(result.get("visibility").toString()),((Number)result.get("version")).intValue(),at.toInstant())));
    emit(id, "CHAT", result, user.userId(), !visibility.equals("PUBLIC"));
    return result;
  }

  Map<String, Object> message(UUID id, UUID viewer) {
    return db.sql(
            "SELECT m.*,u.nickname FROM platform.messages m JOIN platform.users u ON"
                + " u.id=m.author_id WHERE m.id=?")
        .param(id)
        .query(
            (r, n) -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("messageId", id);
              m.put("liveId", r.getObject("live_id"));
              m.put("channelId", r.getObject("channel_id"));
              m.put("authorAlias", r.getString("nickname"));
              m.put("text", r.getString("text"));
              m.put("visibility", r.getString("visibility"));
              m.put("version", r.getInt("version"));
              m.put("sentAt", r.getTimestamp("sent_at").toInstant());
              m.put("isMine", viewer.equals(r.getObject("author_id", UUID.class)));
              decorate(m,viewer);
              return m;
            })
        .single();
  }

  private void decorate(Map<String,Object> message,UUID viewer) {
    if(!Boolean.TRUE.equals(message.get("isMine"))||message.get("visibility").toString().equals("PUBLIC"))return;
    UUID id=(UUID)message.get("messageId");
    String route=db.sql("SELECT reason FROM platform.message_hold_reasons WHERE message_id=? ORDER BY created_at LIMIT 1")
      .param(id).query(String.class).optional().orElse("");
    String code=db.sql("SELECT moderation_reason_code FROM platform.messages WHERE id=?").param(id).query((r,n)->r.getString(1)).optional().orElse(null);
    message.put("moderationReasonCode",code);
    message.put("moderationNotice",switch(route){case "RULE_CONFIRMED"->"금지된 욕설 또는 링크가 포함되어 숨김 처리되었습니다.";case "FLOOD_REVIEW"->"동일 문장 반복으로 숨김 처리되었습니다.";case "PII_INVESTIGATION"->"개인정보 노출 가능성을 확인하고 있습니다.";default->message.get("visibility").toString().equals("HELD")?"문맥 확인과 운영자 결정을 기다립니다.":"운영자 검토로 위반이 확정되어 숨김 처리되었습니다.";});
    message.put("reviewStatus",message.get("visibility").toString().equals("HELD")?"PENDING":null);
    message.put("timeoutUntil",guard.timeoutUntil((UUID)message.get("liveId"),viewer));
  }

  @Transactional(readOnly=true)
  public Map<String,Object> history(UUID id,AuthenticatedUser user) { return history(id,user,null,50); }

  @Transactional(readOnly=true)
  public Map<String,Object> history(UUID id,AuthenticatedUser user,String cursor,int limit) {
    authorize(id,user.userId(),false);
    guard.checkEntry(id,user.userId());
    if(limit<1||limit>100) throw new ApiException(ErrorCode.VALIDATION_FAILED,"limit must be 1..100");
    MessageCursor after=cursor==null?null:MessageCursor.decode(cursor);
    var rows=messages.history(live(id).channelId(),id,user.userId(),isOperator(id,user.userId()),after,limit+1);
    var page=rows.subList(0,Math.min(limit,rows.size()));
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("items",page.stream().map(r->{
      Map<String,Object> m=new LinkedHashMap<>();
      m.put("messageId",r.id());m.put("liveId",r.liveId());m.put("channelId",live(id).channelId());
      m.put("authorAlias",r.authorNickname());m.put("text",r.text());m.put("visibility",r.visibility());
      m.put("version",r.version());m.put("sentAt",r.sentAt());m.put("isMine",r.authorId().equals(user.userId()));
      decorate(m,user.userId());return m;
    }).toList());
    result.put("nextCursor",rows.size()>limit?new MessageCursor(page.getLast().sentAt(),page.getLast().id()).encode():null);
    return result;
  }

  boolean isOperator(UUID id, UUID user) {
    return db.sql(
                "SELECT count(*) FROM platform.channel_members WHERE channel_id=? AND user_id=? AND"
                    + " member_role = 'MODERATOR'")
            .params(live(id).channelId(), user)
            .query(Integer.class)
            .single()
        > 0;
  }

  @Transactional
  public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribe(
      UUID id, AuthenticatedUser user, String session, boolean operator) {
    authorize(id, user.userId(), operator);
    lockLive(id);
    active(id);
    guard.checkEntry(id, user.userId());
    return (operator ? events : chats)
        .subscribe(id, user.userId(), isOperator(id, user.userId()), session);
  }

  @Override
  public List<Map<String, Object>> snapshot(UUID id, UUID target, UUID author, List<UUID> evidenceIds) {
    Map<UUID,String> aliases = new LinkedHashMap<>();
    aliases.put(author, "U1");
    var rows =
        db.sql(
                """
                WITH evidence AS (
                  SELECT id,sent_at FROM platform.messages WHERE live_id=:live
                    AND (case_id=(SELECT case_id FROM platform.messages WHERE id=:target)
                      OR id=ANY(string_to_array(:evidence,',')::uuid[]))),
                first_target AS (SELECT * FROM evidence ORDER BY sent_at,id LIMIT 1),
                last_target AS (SELECT * FROM evidence ORDER BY sent_at DESC,id DESC LIMIT 1),
                selected AS (
                  (SELECT id FROM platform.messages WHERE live_id=:live
                    AND (sent_at,id)<(SELECT sent_at,id FROM first_target) ORDER BY sent_at DESC,id DESC LIMIT 10)
                  UNION (SELECT id FROM platform.messages WHERE live_id=:live
                    AND (sent_at,id)>=(SELECT sent_at,id FROM first_target)
                    AND (sent_at,id)<=(SELECT sent_at,id FROM last_target))
                  UNION (SELECT id FROM platform.messages WHERE live_id=:live
                    AND (sent_at,id)>(SELECT sent_at,id FROM last_target) ORDER BY sent_at,id LIMIT 10))
                SELECT id,author_id,text,visibility,version,sent_at FROM platform.messages
                  WHERE id IN (SELECT id FROM selected) ORDER BY sent_at DESC,id DESC
                """)
            .param("live",id).param("target",target)
            .param("evidence",String.join(",",evidenceIds.stream().map(UUID::toString).toList()))
            .query(
                (r, n) -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("messageId", r.getObject("id", UUID.class));
                  m.put(
                      "authorAlias",
                      aliases.computeIfAbsent(r.getObject("author_id", UUID.class),
                          ignored -> "U" + (aliases.size() + 1)));
                  m.put("text", PiiMasker.mask(r.getString("text")));
                  m.put("visibility", r.getString("visibility"));
                  m.put("version", r.getInt("version"));
                  m.put("sentAt", r.getTimestamp("sent_at").toInstant());
                  return m;
                })
            .list();
    var ordered = new ArrayList<>(rows);
    Collections.reverse(ordered);
    return ordered;
  }

  @Override
  public void visibility(UUID caseId, String visibility) {
    changeVisibility(caseId,visibility,false);
  }

  @Override
  public void releaseRevokedCase(UUID caseId) {
    changeVisibility(caseId,"PUBLIC",true);
  }

  private void changeVisibility(UUID caseId,String visibility,boolean revoked) {
    if (visibility.equals("PUBLIC"))
      db.sql(
              "UPDATE platform.message_hold_reasons SET released_at=? WHERE case_id=? AND"
                  + " released_at IS NULL AND (reason<>'RULE_CONFIRMED' OR ?)")
          .params(now(), caseId, revoked)
          .update();
    // 소유 사건(case_id)이 아니어도 이 사건의 원인이 걸려 있던 메시지를 본다. 소유 사건이 먼저 풀리고
    // 다른 사건의 원인이 마지막으로 남았다가 풀리는 경우, case_id만 보면 그 메시지가 복구되지 않는다
    var ids =
        db.sql(
                "SELECT id FROM platform.messages WHERE case_id=? OR id IN (SELECT"
                    + " message_id FROM platform.message_hold_reasons WHERE case_id=?)"
                    + " ORDER BY id FOR UPDATE")
            .params(caseId, caseId)
            .query(UUID.class)
            .list();
    for (UUID id : ids) {
      if (visibility.equals("PUBLIC")
          && db.sql(
                      "SELECT count(*) FROM platform.message_hold_reasons WHERE message_id=? AND"
                          + " released_at IS NULL")
                  .param(id)
                  .query(Integer.class)
                  .single()
              > 0) continue;
      int changed =
          db.sql(
                  "UPDATE platform.messages SET visibility=?,version=version+1 WHERE id=? AND"
                      + " visibility<>?")
              .params(visibility, id, visibility)
              .update();
      if (changed > 0) {
        var data = message(id, new UUID(0, 0));
        UUID liveId = (UUID) data.get("liveId");
        emit(
            liveId,
            "MESSAGE_VISIBILITY_CHANGED",
            Map.of("messageId", id, "visibility", visibility, "version", data.get("version")),
            null,
            false);
        if (visibility.equals("PUBLIC")) emit(liveId, "MESSAGE_RESTORED", data, null, false);
      }
    }
  }

  @Override
  public void emit(
      UUID id, String event, Map<String, Object> data, UUID author, boolean restricted) {
    Map<String,Object> envelope=new LinkedHashMap<>();
    envelope.put("eventId",UUID.randomUUID());envelope.put("occurredAt",clock.instant());
    envelope.put("entityVersion",data.get("version"));envelope.put("payload",data);
    AfterCommit.run(
        () -> {
          if (event.equals("LIVE_ACCESS_BLOCKED"))
            chats.broadcast(id, s -> s.userId().equals(author), event, envelope, true);
          else if (Set.of(
                  "CHAT",
                  "MESSAGE_VISIBILITY_CHANGED",
                  "MESSAGE_RESTORED",
                  "LIVE_ENDED",
                  "ACTION_APPLIED",
                  "ACTION_FAILED")
              .contains(event))
            chats.broadcast(
                id,
                s -> !restricted || s.operator() || s.userId().equals(author),
                event,
                envelope,
                event.equals("LIVE_ENDED"));
          if(!event.equals("CHAT"))legacyEvents.integrated(live(id).channelId(),author,restricted,event,data);
          events.broadcast(id, s -> true, event, envelope);
        });
  }

  @org.springframework.context.event.EventListener
  void logout(org.springframework.security.web.session.HttpSessionDestroyedEvent e) {
    chats.closeSession(e.getId());
    events.closeSession(e.getId());
  }

  @org.springframework.context.event.EventListener
  void reauthenticated(com.lifeguards.liveguard.common.security.SessionReauthenticatedEvent event) { chats.closeSession(event.previousSessionId()); events.closeSession(event.previousSessionId()); }

  @jakarta.annotation.PreDestroy
  void shutdown() {
    chats.shutdown();
    events.shutdown();
  }
}
