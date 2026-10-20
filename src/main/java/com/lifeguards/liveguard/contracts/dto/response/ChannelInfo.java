package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.AuthorRole;

public record ChannelInfo(
		List<String> officialHosts,
		List<EventInfo> events,
		AuthorRole authorRole,
		boolean isOfficialOperator,
		List<ObservedLink> observedLinks) {
	public ChannelInfo {
		officialHosts = DtoValidation.immutableList(officialHosts, "officialHosts");
		events = DtoValidation.immutableList(events, "events");
		DtoValidation.requireNonNull(authorRole, "authorRole");
		observedLinks = DtoValidation.immutableList(observedLinks, "observedLinks");
	}

	public record EventInfo(String title, String summary) {
	}

	public record ObservedLink(UUID messageId, String host, boolean isOfficial) {
		public ObservedLink {
			DtoValidation.requireNonNull(messageId, "messageId");
			DtoValidation.requireNonNull(host, "host");
		}
	}
}
