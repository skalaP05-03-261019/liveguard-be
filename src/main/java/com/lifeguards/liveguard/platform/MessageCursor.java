package com.lifeguards.liveguard.platform;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/** 이력 페이지 커서: (sent_at 마이크로초, id)를 base64url로 감싼 불투명 문자열. */
record MessageCursor(Instant sentAt, UUID id) {

	String encode() {
		String raw = ChronoUnit.MICROS.between(Instant.EPOCH, sentAt) + ":" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	static MessageCursor decode(String cursor) {
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			int separator = raw.indexOf(':');
			long micros = Long.parseLong(raw.substring(0, separator));
			return new MessageCursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS),
					UUID.fromString(raw.substring(separator + 1)));
		} catch (RuntimeException ex) {
			throw new InvalidContractException("cursor is invalid");
		}
	}

}
