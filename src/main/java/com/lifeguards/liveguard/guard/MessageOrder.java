package com.lifeguards.liveguard.guard;

import java.util.Comparator;

import com.lifeguards.liveguard.platform.port.MessageView;

/** 사건 메시지·대상의 순서. 보낸 시각, 같으면 id다. uuid 문자열 순서는 PostgreSQL uuid 정렬(바이트 순서)과 같다. */
final class MessageOrder {

	static final Comparator<MessageView> BY_SENT_AT = Comparator.comparing(MessageView::sentAt)
			.thenComparing(view -> view.messageId().toString());

	private MessageOrder() {
	}

}
