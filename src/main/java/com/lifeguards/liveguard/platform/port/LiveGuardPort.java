package com.lifeguards.liveguard.platform.port;

import java.time.Instant;
import java.util.*;

public interface LiveGuardPort {
  UUID candidate(
      UUID liveId,
      UUID channelId,
      UUID userId,
      UUID messageId,
      String route,
      String policyVersion,
      List<String> tags,
      Instant receivedAt);

  void checkAccess(UUID liveId, UUID userId);

  void checkEntry(UUID liveId, UUID userId);
}
