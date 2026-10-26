package com.lifeguards.liveguard.platform.port;

import com.lifeguards.liveguard.contracts.dto.response.Live;
import java.util.*;

public interface LivePlatformPort {

  Live live(UUID liveId);

  void lockScope(UUID liveId);

  void bindCase(UUID messageId, UUID caseId, String route);

  void authorize(UUID liveId, UUID userId, boolean operator);

  void authorize(UUID liveId, UUID userId, LiveAccess access);

  List<Map<String, Object>> snapshot(UUID liveId, UUID targetId, UUID authorId, List<UUID> evidenceIds);

  void visibility(UUID caseId, String visibility);

  void releaseRevokedCase(UUID caseId);

  void verifyTargets(UUID liveId, List<Map<String, Object>> targets);

  void lockActive(UUID liveId);

  void emit(
      UUID liveId, String event, Map<String, Object> payload, UUID authorId, boolean privateOnly);
}
