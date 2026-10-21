package com.lifeguards.liveguard.platform.port;

import java.time.Instant;
import java.util.*;

public interface LiveGuardPort {
  UUID candidate(CandidateRequest request);

  boolean floodRecurrence(UUID liveId, UUID userId, String normalizedText, Instant receivedAt);

  Instant timeoutUntil(UUID liveId, UUID userId);

  void checkAccess(UUID liveId, UUID userId);

  void checkEntry(UUID liveId, UUID userId);
}
