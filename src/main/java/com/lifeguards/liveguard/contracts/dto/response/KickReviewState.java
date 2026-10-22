package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;
import java.util.UUID;

/** 사건에 걸린 대기 중 강퇴 검토 (openapi.yaml KickReviewState). 없으면 required=false이고 나머지는 null. */
public record KickReviewState(boolean required, UUID reviewId, List<UUID> basisCaseIds) {
}
