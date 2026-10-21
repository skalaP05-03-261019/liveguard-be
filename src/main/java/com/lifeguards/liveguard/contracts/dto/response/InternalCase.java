package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.HandlingRoute;
import com.lifeguards.liveguard.contracts.enums.Visibility;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record InternalCase(
		UUID caseId,
		UUID channelId,
		UUID liveId,
		UUID snapshotId,
		int evidenceVersion,
		String policyVersion,
		HandlingRoute handlingRoute,
		FilterResult filterResult,
		List<String> matchingTags,
		int version,
		List<MaskedMessage> messages,
		List<Target> targets) {
	public InternalCase {
		DtoValidation.requireNonNull(caseId, "caseId");
		DtoValidation.requireNonNull(channelId, "channelId");
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(snapshotId, "snapshotId");
		policyVersion = DtoValidation.requireText(policyVersion, "policyVersion");
		DtoValidation.requireNonNull(handlingRoute, "handlingRoute");
		if (evidenceVersion < 1 || version < 1) {
			throw new InvalidContractException("Evidence and case versions must be positive");
		}
		DtoValidation.requireNonNull(filterResult, "filterResult");
		matchingTags = DtoValidation.immutableList(matchingTags, "matchingTags");
		messages = DtoValidation.immutableList(messages, "messages");
		targets = DtoValidation.immutableList(targets, "targets");
		DtoValidation.requireNotEmpty(targets, "targets");
		if (targets.stream().map(Target::messageId).distinct().count() != targets.size()) {
			throw new InvalidContractException("Targets must reference distinct messages");
		}
		for (Target target : targets) {
			if (messages.stream().noneMatch(message -> message.messageId().equals(target.messageId()))) {
				throw new InvalidContractException("Target must reference a snapshot message");
			}
		}
	}

	@JsonProperty("targetMessageIds")
	public List<UUID> targetMessageIds() {
		return targets.stream().map(Target::messageId).toList();
	}

	@JsonProperty("targetVersions")
	public Map<UUID, Integer> targetVersions() {
		return targets.stream().collect(Collectors.toUnmodifiableMap(Target::messageId, Target::version));
	}

	public record Target(UUID messageId, Visibility visibility, int version) {
		public Target {
			DtoValidation.requireNonNull(messageId, "messageId");
			DtoValidation.requireNonNull(visibility, "visibility");
			if (version < 1) {
				throw new InvalidContractException("Target version must be positive");
			}
		}
	}
}
