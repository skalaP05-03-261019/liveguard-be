package com.lifeguards.liveguard.platform;

import com.lifeguards.liveguard.common.*;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.request.SendMessageRequest;
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
  private final MessageFilter filter;
  private final MessageRepository messages;
  private final SseRegistry chats = new SseRegistry("live-chat-v1", Duration.ofSeconds(15));
  private final SseRegistry events = new SseRegistry("live-guard-v1", Duration.ofSeconds(15));
  private final String policy;
  private final boolean fixtureEnabled;

  public LiveService(
      JdbcClient db,
      Clock clock,
      LiveGuardPort guard,
      MessageFilter filter,
      MessageRepository messages,
      @Value("${LIVEGUARD_POLICY_VERSION:}") String policy,
      @Value("${LIVEGUARD_TBD_FIXTURE_ENABLED:false}") boolean fixtureEnabled) {
    this.db = db;
    this.clock = clock;
    this.guard = guard;
    this.filter = filter;
    this.messages = messages;
    this.policy = policy;
    this.fixtureEnabled = fixtureEnabled;
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
  }

  @Override
  public Live live(UUID id) {
    return db.sql("SELECT * FROM platform.lives WHERE id=?")
        .param(id)
        .query(
            (r, n) ->
                new Live(
                    r.getObject("id", UUID.class),
                    r.getObject("channel_id", UUID.class),
                    r.getTimestamp("started_at").toInstant(),
                    r.getTimestamp("ended_at") == null
                        ? null
                        : r.getTimestamp("ended_at").toInstant()))
        .optional()
        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Live not found"));
  }

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
    if (role.isEmpty() || (operator && !Set.of("STREAMER", "MODERATOR").contains(role.get())))
      throw new ApiException(ErrorCode.FORBIDDEN, "Channel permission required");
  }

  @Transactional
  public Live start(UUID channel, AuthenticatedUser user) {
    authorizeChannel(channel, user.userId(), true);
    if (!policy.equals("v3.1") || !fixtureEnabled)
      throw new ApiException(
          ErrorCode.CASE_NOT_READY,
          "Explicit shared policy and integration fixture configuration required");
    db.sql("SELECT id FROM platform.channels WHERE id=? FOR UPDATE")
        .param(channel)
        .query(UUID.class)
        .single();
    if (db.sql("SELECT count(*) FROM platform.lives WHERE channel_id=? AND ended_at IS NULL")
            .param(channel)
            .query(Integer.class)
            .single()
        > 0) throw new ApiException(ErrorCode.LIVE_ALREADY_ACTIVE, "Live already active");
    UUID id = UUID.randomUUID();
    db.sql("INSERT INTO platform.lives(id,channel_id,started_at) VALUES(?,?,?)")
        .params(id, channel, now())
        .update();
    return live(id);
  }

  @Transactional(readOnly = true)
  public Live current(UUID channel, AuthenticatedUser user) {
    authorizeChannel(channel, user.userId(), false);
    UUID id =
        db.sql("SELECT id FROM platform.lives WHERE channel_id=? AND ended_at IS NULL")
            .param(channel)
            .query(UUID.class)
            .optional()
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No active live"));
    return live(id);
  }

  @Transactional
  public Live end(UUID id, AuthenticatedUser user) {
    authorize(id, user.userId(), true);
    lockLive(id);
    if (live(id).endedAt() != null) throw new ApiException(ErrorCode.LIVE_ENDED, "Live ended");
    db.sql("UPDATE platform.lives SET ended_at=? WHERE id=?").params(now(), id).update();
    emit(id, "LIVE_ENDED", Map.of("liveId", id), null, false);
    return live(id);
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
  public Map<String, Object> send(UUID id, AuthenticatedUser user, SendMessageRequest req) {
    authorize(id, user.userId(), false);
    lockLive(id);
    active(id);
    guard.checkAccess(id, user.userId());
    if (req.text().indexOf(0) >= 0)
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported text");
    var old =
        db.sql(
                "SELECT * FROM platform.messages WHERE live_id=? AND author_id=? AND"
                    + " client_message_id=?")
            .params(id, user.userId(), req.clientMessageId())
            .query((r, n) -> r.getObject("id", UUID.class))
            .optional();
    if (old.isPresent()) {
      String stored =
          db.sql("SELECT text FROM platform.messages WHERE id=?")
              .param(old.get())
              .query(String.class)
              .single();
      if (!stored.equals(req.text()))
        throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "Message request conflict");
      return message(old.get(), user.userId());
    }
    var live = live(id);
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
                req.text(), normalized, messages.officialHosts(live.channelId()), repeated));
    String route =
        d.result() == FilterResult.REVIEW
            ? "CONTEXT_REVIEW"
            : d.result() == FilterResult.HOLD
                ? "RULE_CONFIRMED"
                : d.result() == FilterResult.FLOOD ? "FLOOD_REVIEW" : "PASS";
    List<String> tags = new ArrayList<>(d.matchingTags());
    if (!UrlHosts.extract(req.text()).isEmpty()) {
      route = "RULE_CONFIRMED";
      if (!tags.contains("LINK")) tags.add("LINK");
    } else if (!route.equals("RULE_CONFIRMED")
        && !route.equals("FLOOD_REVIEW")
        && req.text()
            .matches(
                "(?s).*(01[016789][- .]?[0-9]{3,4}[-"
                    + " .]?[0-9]{4}|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}).*")) {
      route = "PII_INVESTIGATION";
      tags.add(req.text().contains("@") ? "PII_EMAIL" : "PII_PHONE");
    }
    String visibility =
        route.equals("PASS") ? "PUBLIC" : route.equals("RULE_CONFIRMED") ? "HIDDEN" : "HELD";
    UUID message = UUID.randomUUID();
    db.sql(
            "INSERT INTO"
                + " platform.messages(id,channel_id,live_id,author_id,client_message_id,text,normalized_text,filter_result,visibility,sent_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?)")
        .params(
            message,
            live.channelId(),
            id,
            user.userId(),
            req.clientMessageId(),
            req.text(),
            normalized,
            route.equals("PASS") ? "PASS" : route.equals("FLOOD_REVIEW") ? "FLOOD" : "HOLD",
            visibility,
            at)
        .update();
    if (!route.equals("PASS")) {
      UUID caseId =
          guard.candidate(
              id, live.channelId(), user.userId(), message, route, policy, tags, at.toInstant());
      db.sql("UPDATE platform.messages SET case_id=? WHERE id=?").params(caseId, message).update();
      db.sql(
              "INSERT INTO platform.message_hold_reasons(message_id,case_id,reason,created_at)"
                  + " VALUES(?,?,?,?)")
          .params(message, caseId, route, at)
          .update();
    }
    var result = message(message, user.userId());
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
              return m;
            })
        .single();
  }

  @Transactional(readOnly = true)
  public Map<String, Object> history(UUID id, AuthenticatedUser user) {
    authorize(id, user.userId(), false);
    active(id);
    guard.checkEntry(id, user.userId());
    boolean op = isOperator(id, user.userId());
    var ids =
        db.sql(
                "SELECT id FROM platform.messages WHERE live_id=? AND (? OR visibility='PUBLIC' OR"
                    + " author_id=?) ORDER BY sent_at DESC,id DESC LIMIT 50")
            .params(id, op, user.userId())
            .query(UUID.class)
            .list();
    return Map.of(
        "items", ids.stream().map(mid -> message(mid, user.userId())).toList(), "nextCursor", "");
  }

  boolean isOperator(UUID id, UUID user) {
    return db.sql(
                "SELECT count(*) FROM platform.channel_members WHERE channel_id=? AND user_id=? AND"
                    + " member_role IN ('STREAMER','MODERATOR')")
            .params(live(id).channelId(), user)
            .query(Integer.class)
            .single()
        > 0;
  }

  public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribe(
      UUID id, AuthenticatedUser user, String session, boolean operator) {
    authorize(id, user.userId(), operator);
    active(id);
    guard.checkEntry(id, user.userId());
    return (operator ? events : chats)
        .subscribe(id, user.userId(), isOperator(id, user.userId()), session);
  }

  @Override
  public List<Map<String, Object>> snapshot(UUID id, UUID target, UUID author) {
    var rows =
        db.sql(
                "SELECT id,author_id,text,visibility,version,sent_at FROM platform.messages WHERE"
                    + " live_id=? ORDER BY sent_at DESC,id DESC LIMIT 20")
            .param(id)
            .query(
                (r, n) -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("messageId", r.getObject("id", UUID.class));
                  m.put(
                      "authorAlias",
                      author.equals(r.getObject("author_id", UUID.class))
                          ? "U1"
                          : "U_" + r.getObject("author_id").toString().substring(0, 8));
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
    if (visibility.equals("PUBLIC"))
      db.sql(
              "UPDATE platform.message_hold_reasons SET released_at=? WHERE case_id=? AND"
                  + " released_at IS NULL")
          .params(now(), caseId)
          .update();
    var ids =
        db.sql("SELECT id FROM platform.messages WHERE case_id=? FOR UPDATE")
            .param(caseId)
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
    Map<String, Object> envelope =
        Map.of(
            "eventId",
            UUID.randomUUID(),
            "occurredAt",
            clock.instant(),
            "entityVersion",
            data.getOrDefault("version", 1),
            "payload",
            data);
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
          events.broadcast(id, s -> true, event, envelope);
        });
  }

  @org.springframework.context.event.EventListener
  void logout(org.springframework.security.web.session.HttpSessionDestroyedEvent e) {
    chats.closeSession(e.getId());
    events.closeSession(e.getId());
  }

  @jakarta.annotation.PreDestroy
  void shutdown() {
    chats.shutdown();
    events.shutdown();
  }
}
