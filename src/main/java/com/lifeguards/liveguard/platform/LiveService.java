package com.lifeguards.liveguard.platform;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.response.Live;
import com.lifeguards.liveguard.contracts.enums.AuthorRole;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.platform.port.LiveEndedEvent;

/** 방송 시작·종료·현재 조회(A-1, A-2). 시작·종료는 그 채널의 STREAMER 또는 MODERATOR만 한다. */
@Service
class LiveService {

	private static final Set<AuthorRole> HOSTS = Set.of(AuthorRole.STREAMER, AuthorRole.MODERATOR);

	private final JdbcLiveQuery lives;
	private final JdbcChannelMembershipQuery memberships;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	LiveService(JdbcLiveQuery lives, JdbcChannelMembershipQuery memberships, ApplicationEventPublisher events,
			Clock clock) {
		this.lives = lives;
		this.memberships = memberships;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	Live start(UUID channelId, AuthenticatedUser user) {
		requireHost(channelId, user);
		return lives.start(channelId, now()).orElseThrow(() -> new ApiException(ErrorCode.LIVE_ALREADY_ACTIVE,
				"A live is already in progress in this channel"));
	}

	@Transactional
	Live end(UUID liveId, AuthenticatedUser user) {
		Live live = lives.find(liveId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Live not found"));
		requireHost(live.channelId(), user);
		Live ended = lives.end(liveId, now())
				.orElseThrow(() -> new ApiException(ErrorCode.LIVE_ENDED, "Live has already ended"));
		events.publishEvent(new LiveEndedEvent(ended.liveId(), ended.channelId(), ended.endedAt()));
		return ended;
	}

	@Transactional(readOnly = true)
	Live current(UUID channelId, AuthenticatedUser user) {
		if (memberships.memberRole(channelId, user.userId()).isEmpty()) {
			// 채널 존재 여부가 드러나지 않도록 없는 채널도 같은 응답
			throw new ApiException(ErrorCode.FORBIDDEN, "Not a member of this channel");
		}
		return lives.currentLive(channelId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No live in progress"));
	}

	private void requireHost(UUID channelId, AuthenticatedUser user) {
		AuthorRole role = memberships.memberRole(channelId, user.userId())
				.orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "Not a member of this channel"));
		if (!HOSTS.contains(role)) {
			throw new ApiException(ErrorCode.FORBIDDEN, "Only the streamer or a moderator can start or end a live");
		}
	}

	// timestamptz는 마이크로초 정밀도라 미리 잘라 응답과 저장값을 같게 한다
	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

}
