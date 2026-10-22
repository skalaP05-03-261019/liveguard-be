package com.lifeguards.liveguard.guard;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.dto.response.InternalCase;
import com.lifeguards.liveguard.contracts.dto.response.UserHistory;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import com.lifeguards.liveguard.guard.CaseQueryRepository.CaseRow;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CaseQueryService {

	/** Internal storage projection, not an Agent response or a frozen historical message snapshot. */
	record CaseDetails(CaseRow metadata, List<CaseQueryRepository.CandidateRow> candidates,
			List<CaseQueryRepository.MessageRow> messages, List<CaseQueryRepository.FindingRow> findings,
			List<CaseQueryRepository.AuditRow> audit) {
	}

	public record TimeoutHistory(Instant since, Instant asOf, int violationCount,
			Map<Category, Long> categoryCounts, List<UserHistory.RecentCase> recentCases) {
		public TimeoutHistory {
			categoryCounts = Map.copyOf(categoryCounts);
			recentCases = List.copyOf(recentCases);
		}
	}

	/** Read-only new-review candidates after reservation and consumption exclusion, not execution authority. */
	public record KickBasis(UUID channelId, UUID userId, UUID liveId, Instant since, Instant asOf,
			List<UUID> confirmedCaseIds) {
		public KickBasis {
			confirmedCaseIds = List.copyOf(confirmedCaseIds);
		}
	}

	private final CaseQueryRepository repository;
	private final Clock clock;
	private final LiveModerationService moderation;

	public CaseQueryService(CaseQueryRepository repository, Clock clock, LiveModerationService moderation) {
		this.repository = repository;
		this.clock = clock;
		this.moderation = moderation;
	}

	CaseDetails details(UUID caseId) {
		CaseRow current = requireCase(caseId);
		return new CaseDetails(current, repository.candidates(caseId), repository.messages(caseId),
				repository.findings(caseId), repository.audit(caseId));
	}

	public InternalCase moderationCase(UUID caseId) {
		CaseRow current = requireCase(caseId);
		if (current.liveId() == null || current.policyVersion() == null || current.policyVersion().isBlank()
				|| current.handlingRoute() == null) {
			throw notReady("Case broadcast, policy version or handling route is not available");
		}
		repository.candidates(caseId).stream()
				.filter(row -> row.snapshotId().equals(current.snapshotId())
						&& row.evidenceVersion() == current.evidenceVersion())
				.findFirst().orElseThrow(() -> notReady("Current case evidence is not available"));
		if (!repository.hasTarget(caseId)) {
			throw notReady("Case targets are not available");
		}
		if(!moderation.owns(caseId))throw notReady("Immutable evidence snapshot is not available");
		return moderation.internalCase(caseId);
	}

	public TimeoutHistory timeoutHistory(UUID caseId, Instant since, Instant asOf) {
		validateRange(since, asOf);
		return history(requireScopedCase(caseId), since, asOf);
	}

	public UserHistory userHistory(UUID caseId, Integer days) {
		if (days != null) throw new InvalidContractException("TIMEOUT history covers the whole live; days is unsupported");
		CaseRow current = requireScopedCase(caseId);
		Instant asOf = clock.instant();
		TimeoutHistory history = history(current, Instant.parse("0001-01-01T00:00:00Z"), asOf);
		return new UserHistory(history.violationCount(), history.recentCases());
	}

	public com.lifeguards.liveguard.contracts.dto.response.KickEligibility kickEligibility(UUID caseId) {
		CaseRow current=requireScopedCase(caseId);
		var basis=kickBasis(current.channelId(),current.userId(),current.liveId(),clock.instant());
		return new com.lifeguards.liveguard.contracts.dto.response.KickEligibility(current.userId(),current.channelId(),
			current.liveId(),basis.asOf(),24,basis.confirmedCaseIds().size(),basis.confirmedCaseIds());
	}

	public KickBasis kickBasis(UUID channelId, UUID userId, UUID liveId, Instant asOf) {
		DtoValidation.requireNonNull(channelId, "channelId");
		DtoValidation.requireNonNull(userId, "userId");
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(asOf, "asOf");
		Instant since = asOf.minus(24, ChronoUnit.HOURS);
		validateRange(since, asOf);
		return new KickBasis(channelId, userId, liveId, since, asOf,
				repository.kickBasis(channelId, userId, liveId, since, asOf));
	}

	private TimeoutHistory history(CaseRow current, Instant since, Instant asOf) {
		List<UserHistory.RecentCase> rows = repository.timeoutHistory(current, since, asOf);
		Map<Category, Long> counts = new EnumMap<>(Category.class);
		for (Category category : Category.values()) {
			counts.put(category, rows.stream().filter(row -> row.category() == category).count());
		}
		int distinctCases = Math.toIntExact(rows.stream().map(UserHistory.RecentCase::caseId).distinct().count());
		return new TimeoutHistory(since, asOf, distinctCases, counts, rows);
	}

	private CaseRow requireCase(UUID caseId) {
		DtoValidation.requireNonNull(caseId, "caseId");
		return repository.find(caseId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Case not found"));
	}

	private CaseRow requireScopedCase(UUID caseId) {
		CaseRow current=requireCase(caseId);
		if(current.liveId()==null||!repository.hasLiveScope(current))
			throw notReady("Case broadcast scope is unavailable or inconsistent");
		return current;
	}

	private void validateRange(Instant since, Instant asOf) {
		DtoValidation.requireNonNull(since, "since");
		DtoValidation.requireNonNull(asOf, "asOf");
		if (!since.isBefore(asOf)) {
			throw new InvalidContractException("History start must be before its end");
		}
	}

	private ApiException notReady(String message) {
		return new ApiException(ErrorCode.CASE_NOT_READY, message);
	}
}
