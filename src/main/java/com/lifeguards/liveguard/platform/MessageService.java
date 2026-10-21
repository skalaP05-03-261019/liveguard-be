package com.lifeguards.liveguard.platform;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.event.ChatEvent;
import com.lifeguards.liveguard.contracts.dto.request.SendMessageRequest;
import com.lifeguards.liveguard.contracts.dto.response.Live;
import com.lifeguards.liveguard.contracts.dto.response.Message;
import com.lifeguards.liveguard.contracts.dto.response.MessagePage;
import com.lifeguards.liveguard.contracts.enums.AuthorRole;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import com.lifeguards.liveguard.filter.FilterDecision;
import com.lifeguards.liveguard.filter.FilterInput;
import com.lifeguards.liveguard.filter.MessageFilter;
import com.lifeguards.liveguard.filter.TextNormalizer;
import com.lifeguards.liveguard.platform.MessageRepository.HistoryRow;
import com.lifeguards.liveguard.platform.MessageRepository.StoredMessage;
import com.lifeguards.liveguard.platform.port.CandidateRequest;
import com.lifeguards.liveguard.platform.port.CandidateSink;
import com.lifeguards.liveguard.platform.port.KickStatusQuery;

@Service
class MessageService {

	private static final Logger log = LoggerFactory.getLogger(MessageService.class);
	private static final int MAX_PAGE_SIZE = 100;
	private static final Duration FLOOD_WINDOW = Duration.ofSeconds(10);

	private final MessageRepository messages;
	private final JdbcLiveQuery lives;
	private final JdbcChannelMembershipQuery memberships;
	private final MessageFilter filter;
	private final CandidateSink candidateSink;
	private final KickStatusQuery kickStatus;
	private final ApplicationEventPublisher events;
	private final Clock clock;
	private final LiveService moderation;
	private final tools.jackson.databind.json.JsonMapper mapper;

	MessageService(MessageRepository messages, JdbcLiveQuery lives, JdbcChannelMembershipQuery memberships,
			MessageFilter filter, CandidateSink candidateSink, KickStatusQuery kickStatus, ApplicationEventPublisher events,
			Clock clock, LiveService moderation, tools.jackson.databind.json.JsonMapper mapper) {
		this.messages = messages;
		this.lives = lives;
		this.memberships = memberships;
		this.filter = filter;
		this.candidateSink = candidateSink;
		this.kickStatus = kickStatus;
		this.events = events;
		this.clock = clock;
		this.moderation=moderation;
		this.mapper=mapper;
	}

	/** 이 방송으로 전송한다. 없는 방송은 404. */
	@Transactional
	Message sendToLive(UUID liveId, AuthenticatedUser author, SendMessageRequest request) {
		requireStorable(request.text());
		if(moderation.integrationEnabled()) return mapper.convertValue(moderation.send(liveId,author,request),Message.class);
		Live live = lives.find(liveId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Live not found"));
		return send(live.channelId(), liveId, author, request);
	}

	/** 기존 channel 경로(A-18). 그 채널의 진행 중 방송으로 귀속하고, 없으면 409 LIVE_ENDED. */
	@Transactional
	Message sendToChannel(UUID channelId, AuthenticatedUser author, SendMessageRequest request) {
		requireStorable(request.text());
		if(moderation.integrationEnabled()) {
			return mapper.convertValue(moderation.sendToChannel(channelId,author,request),Message.class);
		}
		return send(channelId, null, author, request);
	}

	/** liveId가 null이면 채널의 진행 중 방송을 찾는다. */
	private Message send(UUID channelId, UUID liveId, AuthenticatedUser author, SendMessageRequest request) {
		if (!messages.isMember(channelId, author.userId())) {
			// 채널 존재 여부가 드러나지 않도록 없는 채널도 같은 응답
			throw new ApiException(ErrorCode.FORBIDDEN, "Not a member of this channel");
		}
		// 비회원에게 강퇴 여부가 드러나지 않도록 회원 확인 뒤에 한다. 재전송도 막는다
		kickStatus.activeKickUntil(channelId, author.userId()).ifPresent(until -> {
			throw new ApiException(ErrorCode.KICKED, "Kicked from this channel", until, null);
		});
		// 잠금 순서: 작성자·채널 advisory 잠금 → 재전송 조회 → 방송 행 FOR SHARE. 동시 재전송이 원본 전송의
		// 커밋을 기다린 뒤 조회하게 해서, 원본이 커밋되고 방송이 종료돼도 첫 응답을 돌려준다(A-18)
		messages.lockAuthorInChannel(channelId, author.userId());
		// 재전송은 방송 종료 검사보다 먼저 확인한다. 종료 직전 저장됐는데 응답이 유실된 경우 첫 응답을 돌려준다(A-18)
		var replayed = messages.find(channelId, author.userId(), request.clientMessageId());
		if (replayed.isPresent()) {
			return replay(channelId, author, request, replayed.get());
		}
		UUID targetLive = liveId != null ? liveId : lives.currentLive(channelId).map(Live::liveId)
				.orElseThrow(() -> new ApiException(ErrorCode.LIVE_ENDED, "No live in progress"));
		// 종료 UPDATE와 직렬화한다. 잠근 뒤에 종료 여부를 본다
		Live live = lives.lockForSend(targetLive).orElseThrow();
		if (live.endedAt() != null) {
			throw new ApiException(ErrorCode.LIVE_ENDED, "Live has ended");
		}

		// timestamptz는 마이크로초 정밀도라 미리 잘라 재전송 응답의 sentAt이 최초와 같게 한다
		Instant sentAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
		String normalizedText = TextNormalizer.normalize(request.text());
		// 도배는 같은 사용자·방송 범위다. 이전 방송과 방송 도입 전(live_id NULL) 메시지는 세지 않는다
		List<UUID> sameTextInWindow = messages.sameTextMessageIds(live.liveId(), author.userId(), normalizedText,
				sentAt.minus(FLOOD_WINDOW));
		FilterDecision decision = filter.classify(new FilterInput(request.text(), normalizedText,
				messages.officialHosts(channelId), sameTextInWindow.size() + 1));
		// 동시 재전송이면 아래 INSERT가 무시되고 저장된 결과를 돌려주므로 이번 판정은 쓰이지 않는다
		var inserted = messages.insertIfAbsent(channelId, live.liveId(), author.userId(), request.clientMessageId(),
				request.text(), normalizedText, decision.result(), decision.visibility(), sentAt);
		if (inserted.isPresent()) {
			if (decision.result() != FilterResult.PASS) {
				// FLOOD이면 창 안의 이전 메시지도 같은 사건에 연결한다. 그 외에는 빈 목록
				List<UUID> related = decision.result() == FilterResult.FLOOD ? sameTextInWindow : List.of();
				linkToCase(channelId, author, inserted.get(), decision, related);
			}
			Message message = toMessage(channelId, author, inserted.get());
			events.publishEvent(new MessageCreatedEvent(channelId, author.userId(), toChatEvent(message)));
			return message;
		}
		return replay(channelId, author, request,
				messages.find(channelId, author.userId(), request.clientMessageId()).orElseThrow());
	}

	private Message replay(UUID channelId, AuthenticatedUser author, SendMessageRequest request, StoredMessage existing) {
		if (!existing.text().equals(request.text())) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
					"clientMessageId was already used with a different text");
		}
		return toMessage(channelId, author, existing);
	}

	/** 메시지 저장과 같은 트랜잭션에서 사건을 열고 messages.case_id를 기록한다. 실패하면 저장이 롤백된다. */
	private void linkToCase(UUID channelId, AuthenticatedUser author, StoredMessage stored, FilterDecision decision,
			List<UUID> related) {
		UUID caseId;
		try {
			caseId = candidateSink.open(new CandidateRequest(channelId, stored.liveId(), author.userId(), stored.id(),
					decision.result(), decision.matchingTags(), MessageFilter.version(), stored.sentAt(), related));
		} catch (ApiException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			// 예외 메시지에 채팅 원문이 섞일 수 있어 클래스만 남긴다
			log.error("CandidateSink failed: {}", ex.getClass().getName());
			throw new ApiException(ErrorCode.TEMPORARY_UNAVAILABLE, "Temporarily unavailable");
		}
		List<UUID> toLink = new ArrayList<>(related);
		toLink.add(stored.id());
		messages.linkCase(channelId, author.userId(), toLink, caseId);
	}

	/** 이 방송의 이력. 없는 방송은 404. */
	@Transactional(readOnly = true)
	MessagePage historyOfLive(UUID liveId, AuthenticatedUser viewer, String cursor, int limit) {
		if(moderation.integrationEnabled()) return mapper.convertValue(moderation.history(liveId,viewer,cursor,limit),MessagePage.class);
		Live live = lives.find(liveId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Live not found"));
		return history(live.channelId(), liveId, viewer, cursor, limit);
	}

	/** 기존 channel 경로(A-18). 모든 방송과 방송 도입 전 메시지를 함께 돌려준다. */
	@Transactional(readOnly = true)
	MessagePage historyOfChannel(UUID channelId, AuthenticatedUser viewer, String cursor, int limit) {
		return history(channelId, null, viewer, cursor, limit);
	}

	private MessagePage history(UUID channelId, UUID liveId, AuthenticatedUser viewer, String cursor, int limit) {
		if (limit < 1 || limit > MAX_PAGE_SIZE) {
			throw new InvalidContractException("limit must be between 1 and " + MAX_PAGE_SIZE);
		}
		MessageCursor after = cursor == null ? null : MessageCursor.decode(cursor);
		AuthorRole role = memberships.memberRole(channelId, viewer.userId())
				.orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "Not a member of this channel"));

		// 다음 페이지 유무를 알기 위해 하나 더 읽는다
		List<HistoryRow> rows = messages.history(channelId, liveId, viewer.userId(), role == AuthorRole.MODERATOR,
				after, limit + 1);
		List<HistoryRow> page = rows.subList(0, Math.min(limit, rows.size()));
		String nextCursor = rows.size() > limit
				? new MessageCursor(page.getLast().sentAt(), page.getLast().id()).encode()
				: null;
		List<Message> items = page.stream()
				.map(row -> new Message(row.id(), row.liveId(), channelId, row.authorNickname(), row.text(), row.visibility(),
						row.version(), row.sentAt(), row.authorId().equals(viewer.userId())))
				.toList();
		return new MessagePage(items, nextCursor);
	}

	private static Message toMessage(UUID channelId, AuthenticatedUser author, StoredMessage stored) {
		return new Message(stored.id(), stored.liveId(), channelId, author.nickname(), stored.text(), stored.visibility(),
				stored.version(), stored.sentAt(), true);
	}

	private static ChatEvent toChatEvent(Message message) {
		return new ChatEvent(message.messageId(), message.liveId(), message.authorAlias(), message.text(),
				message.visibility(),
				message.version(), message.sentAt());
	}

	/** Postgres text는 NUL과 짝 없는 서로게이트를 저장하지 못한다 (그대로 두면 500). */
	private static void requireStorable(String text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			boolean hasLowSurrogateNext = i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1));
			if (c == '\u0000' || Character.isLowSurrogate(c) || (Character.isHighSurrogate(c) && !hasLowSurrogateNext)) {
				throw new InvalidContractException("text contains unsupported characters");
			}
			if (Character.isHighSurrogate(c)) {
				i++;
			}
		}
	}

}
