package com.lifeguards.liveguard.guard;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.dto.response.InternalCase;
import com.lifeguards.liveguard.contracts.dto.response.MaskedMessage;
import com.lifeguards.liveguard.contracts.dto.response.UserHistory;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import com.lifeguards.liveguard.filter.PiiMasker;
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

	/** Deliberately has no eligible flag: reservations and consumption have not been applied. */
	public record KickBasis(UUID channelId, UUID userId, UUID liveId, Instant since, Instant asOf,
			List<UUID> confirmedCaseIds) {
		public KickBasis {
			confirmedCaseIds = List.copyOf(confirmedCaseIds);
		}
	}

	private final CaseQueryRepository repository;
	private final Clock clock;

	public CaseQueryService(CaseQueryRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
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
		var candidate = repository.candidates(caseId).stream()
				.filter(row -> row.snapshotId().equals(current.snapshotId())
						&& row.evidenceVersion() == current.evidenceVersion())
				.findFirst().orElseThrow(() -> notReady("Current case evidence is not available"));
		List<CaseQueryRepository.MessageRow> rows = repository.messages(caseId);
		if (rows.stream().noneMatch(CaseQueryRepository.MessageRow::target)) {
			throw notReady("Case targets are not available");
		}
		Map<UUID, String> aliases = new LinkedHashMap<>();
		aliases.put(current.userId(), "U1");
		List<MaskedMessage> messages = rows.stream().map(row -> new MaskedMessage(row.id(),
				aliases.computeIfAbsent(row.authorId(), id -> "U" + (aliases.size() + 1)),
				PiiMasker.mask(row.text()), row.sentAt())).toList();
		List<InternalCase.Target> targets = rows.stream().filter(CaseQueryRepository.MessageRow::target)
				.map(row -> new InternalCase.Target(row.id(), row.visibility(), row.version())).toList();
		return new InternalCase(current.id(), current.channelId(), current.liveId(), current.snapshotId(),
				current.evidenceVersion(), current.policyVersion(), current.handlingRoute(), candidate.filterResult(),
				candidate.matchingTags(), current.version(), messages, targets);
	}

	public TimeoutHistory timeoutHistory(UUID caseId, Instant since, Instant asOf) {
		validateRange(since, asOf);
		return history(requireCase(caseId), since, asOf);
	}

	public UserHistory userHistory(UUID caseId, Integer days) {
		if (days != null && (days < 1 || days > Integer.MAX_VALUE / 24)) {
			throw new InvalidContractException("days must be positive and representable as windowHours");
		}
		CaseRow current = requireCase(caseId);
		if (days == null) {
			throw new ApiException(ErrorCode.NOT_IMPLEMENTED, "Default TIMEOUT history period is not configured");
		}
		Instant asOf = clock.instant();
		TimeoutHistory history = history(current, asOf.minus(days, ChronoUnit.DAYS), asOf);
		return new UserHistory(history.violationCount(), Math.multiplyExact(days, 24), history.recentCases());
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
