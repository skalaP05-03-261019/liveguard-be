package com.lifeguards.liveguard.platform.port;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.Visibility;

/**
 * {@link LiveMessageQuery}가 돌려주는 메시지 한 건의 현재 상태.
 *
 * @param authorId 내부 가명(U1, U2…) 부여에만 쓴다. 외부 응답에 싣지 않는다
 * @param maskedText 개인정보를 마스킹한 본문(PiiMasker). 원문은 넘기지 않는다
 * @param visibility 지금의 공개 범위
 * @param version 지금의 메시지 버전. 가시성이 바뀔 때마다 올라간다
 */
public record MessageView(UUID messageId, UUID authorId, String maskedText, Visibility visibility, int version,
		Instant sentAt) {

	public MessageView {
		DtoValidation.requireNonNull(messageId, "messageId");
		DtoValidation.requireNonNull(authorId, "authorId");
		DtoValidation.requireNonNull(maskedText, "maskedText");
		DtoValidation.requireNonNull(visibility, "visibility");
		DtoValidation.requireNonNull(sentAt, "sentAt");
	}

}
