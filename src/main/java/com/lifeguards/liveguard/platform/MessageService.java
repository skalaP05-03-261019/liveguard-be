package com.lifeguards.liveguard.platform;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.event.ChatEvent;
import com.lifeguards.liveguard.contracts.dto.request.SendMessageRequest;
import com.lifeguards.liveguard.contracts.dto.response.Message;
import com.lifeguards.liveguard.contracts.dto.response.MessagePage;
import com.lifeguards.liveguard.contracts.enums.AuthorRole;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.Visibility;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import com.lifeguards.liveguard.filter.TextNormalizer;
import com.lifeguards.liveguard.platform.MessageRepository.HistoryRow;
import com.lifeguards.liveguard.platform.MessageRepository.StoredMessage;

@Service
class MessageService {

	private static final int MAX_PAGE_SIZE = 100;

	private final MessageRepository messages;
	private final JdbcChannelMembershipQuery memberships;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	MessageService(MessageRepository messages, JdbcChannelMembershipQuery memberships, ApplicationEventPublisher events,
			Clock clock) {
		this.messages = messages;
		this.memberships = memberships;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	Message send(UUID channelId, AuthenticatedUser author, SendMessageRequest request) {
		requireStorable(request.text());
		if (!messages.isMember(channelId, author.userId())) {
			// 채널 존재 여부가 드러나지 않도록 없는 채널도 같은 응답
			throw new ApiException(ErrorCode.FORBIDDEN, "Not a member of this channel");
		}

		// 1차 필터는 8번에서 연결한다. 그 전까지는 모두 PASS·PUBLIC
		// timestamptz는 마이크로초 정밀도라 미리 잘라 재전송 응답의 sentAt이 최초와 같게 한다
		Instant sentAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
		var inserted = messages.insertIfAbsent(channelId, author.userId(), request.clientMessageId(), request.text(),
				TextNormalizer.normalize(request.text()), FilterResult.PASS, Visibility.PUBLIC, sentAt);
		if (inserted.isPresent()) {
			Message message = toMessage(channelId, author, inserted.get());
			events.publishEvent(new MessageCreatedEvent(channelId, author.userId(), toChatEvent(message)));
			return message;
		}

		StoredMessage existing = messages.find(channelId, author.userId(), request.clientMessageId()).orElseThrow();
		if (!existing.text().equals(request.text())) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
					"clientMessageId was already used with a different text");
		}
		return toMessage(channelId, author, existing);
	}

	@Transactional(readOnly = true)
	MessagePage history(UUID channelId, AuthenticatedUser viewer, String cursor, int limit) {
		if (limit < 1 || limit > MAX_PAGE_SIZE) {
			throw new InvalidContractException("limit must be between 1 and " + MAX_PAGE_SIZE);
		}
		MessageCursor after = cursor == null ? null : MessageCursor.decode(cursor);
		AuthorRole role = memberships.memberRole(channelId, viewer.userId())
				.orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "Not a member of this channel"));

		// 다음 페이지 유무를 알기 위해 하나 더 읽는다
		List<HistoryRow> rows = messages.history(channelId, viewer.userId(), role == AuthorRole.MODERATOR, after,
				limit + 1);
		List<HistoryRow> page = rows.subList(0, Math.min(limit, rows.size()));
		String nextCursor = rows.size() > limit
				? new MessageCursor(page.getLast().sentAt(), page.getLast().id()).encode()
				: null;
		List<Message> items = page.stream()
				.map(row -> new Message(row.id(), channelId, row.authorNickname(), row.text(), row.visibility(),
						row.version(), row.sentAt(), row.authorId().equals(viewer.userId())))
				.toList();
		return new MessagePage(items, nextCursor);
	}

	private static Message toMessage(UUID channelId, AuthenticatedUser author, StoredMessage stored) {
		return new Message(stored.id(), channelId, author.nickname(), stored.text(), stored.visibility(),
				stored.version(), stored.sentAt(), true);
	}

	private static ChatEvent toChatEvent(Message message) {
		return new ChatEvent(message.messageId(), message.authorAlias(), message.text(), message.visibility(),
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
