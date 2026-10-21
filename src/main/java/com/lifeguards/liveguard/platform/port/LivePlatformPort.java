package com.lifeguards.liveguard.platform.port;

import com.lifeguards.liveguard.contracts.dto.response.Live;
import java.util.*;

public interface LivePlatformPort {

  Live live(UUID liveId);

  void authorize(UUID liveId, UUID userId, boolean operator);

  List<Map<String, Object>> snapshot(UUID liveId, UUID targetId, UUID authorId);

  void visibility(UUID caseId, String visibility);

  void verifyTargets(UUID liveId, List<Map<String, Object>> targets);

  void lockActive(UUID liveId);

  void emit(
      UUID liveId, String event, Map<String, Object> payload, UUID authorId, boolean privateOnly);
}
