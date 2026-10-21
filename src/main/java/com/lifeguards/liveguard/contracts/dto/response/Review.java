package com.lifeguards.liveguard.contracts.dto.response;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.enums.ReviewKind;

/** 검토 목록 항목(openapi Review = ContextReview | KickReview, kind로 구분). */
public sealed interface Review permits ContextReview, KickReview {
	UUID reviewId();

	ReviewKind kind();
}
