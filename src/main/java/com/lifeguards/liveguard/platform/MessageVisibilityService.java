package com.lifeguards.liveguard.platform;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lifeguards.liveguard.contracts.enums.Visibility;
import com.lifeguards.liveguard.platform.MessageRepository.ChangedMessage;
import com.lifeguards.liveguard.platform.port.MessageVisibilityPort;

@Service
class MessageVisibilityService implements MessageVisibilityPort {

	private final MessageRepository messages;
	private final ChannelEventHub channelEvents;

	MessageVisibilityService(MessageRepository messages, ChannelEventHub channelEvents) {
		this.messages = messages;
		this.channelEvents = channelEvents;
	}

	// 기본 전파(REQUIRED): 호출자(판정 처리) 트랜잭션에 참여하고, 없으면 새로 시작한다
	@Override
	@Transactional
	public int hideCaseMessages(UUID caseId) {
		return change(caseId, Visibility.HIDDEN, List.of(Visibility.PUBLIC, Visibility.HELD));
	}

	@Override
	@Transactional
	public int restoreCaseMessages(UUID caseId) {
		return change(caseId, Visibility.PUBLIC, List.of(Visibility.HELD, Visibility.HIDDEN));
	}

	private int change(UUID caseId, Visibility target, List<Visibility> from) {
		List<ChangedMessage> changed = messages.changeCaseVisibility(caseId, target, from);
		for (ChangedMessage message : changed) {
			channelEvents.visibilityChanged(new VisibilityChange(message.id(), message.channelId(),
					message.authorId(), message.previous(), target, message.version(), message.text(),
					message.authorNickname(), message.sentAt()));
		}
		return changed.size();
	}

}
