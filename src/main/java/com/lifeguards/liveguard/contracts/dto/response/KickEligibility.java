package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
		Backend-confirmed distinct cases across all five categories for the same user/channel/live.
		Includes the current case within the last 24 hours; excludes revoked and consumed cases.
		TIMEOUT expiry does not revoke a violation. Pending review reservations must be checked
		before creating a review. Eligibility is not operator approval or permission to execute KICK.
		""")
public record KickEligibility(
		UUID userId,
		UUID channelId,
		UUID liveId,
		Instant asOf,
		@Schema(allowableValues = "24") int windowHours,
		int violationCount,
		List<UUID> eligibleCaseIds) {

	public KickEligibility {
		DtoValidation.requireNonNull(userId, "userId");
		DtoValidation.requireNonNull(channelId, "channelId");
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(asOf, "asOf");
		if (windowHours != 24) {
			throw new InvalidContractException("windowHours must be 24");
		}
		eligibleCaseIds = DtoValidation.immutableList(eligibleCaseIds, "eligibleCaseIds");
		if (eligibleCaseIds.stream().distinct().count() != eligibleCaseIds.size()) {
			throw new InvalidContractException("eligibleCaseIds must contain distinct cases");
		}
		if (violationCount != eligibleCaseIds.size()) {
			throw new InvalidContractException("violationCount must match eligibleCaseIds size");
		}
	}
}
