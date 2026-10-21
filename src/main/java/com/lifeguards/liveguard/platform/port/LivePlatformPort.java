package com.lifeguards.liveguard.platform.port;

import java.time.Instant;
import java.util.*;

public interface LivePlatformPort {
  record Live(UUID liveId, UUID channelId, Instant startedAt, Instant endedAt) {}

  Live live(UUID liveId);

  void authorize(UUID liveId, UUID userId, boolean operator);

  List<Map<String, Object>> snapshot(UUID liveId, UUID targetId, UUID authorId);

  void visibility(UUID caseId, String visibility);

  void verifyTargets(UUID liveId, List<Map<String, Object>> targets);

  void lockActive(UUID liveId);

  void emit(
      UUID liveId, String event, Map<String, Object> payload, UUID authorId, boolean privateOnly);
}
