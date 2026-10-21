package com.lifeguards.liveguard.platform;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.enums.Visibility;

/** 메시지 한 건의 공개 범위 변경. text·authorAlias·sentAt은 복원(PUBLIC) 이벤트에만 쓴다. */
record VisibilityChange(UUID messageId, UUID channelId, UUID authorId, Visibility previous, Visibility current,
		int version, String text, String authorAlias, Instant sentAt) {
}
