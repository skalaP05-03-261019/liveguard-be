package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record OfficialLinksResponse(List<OfficialLink> links) {
	public OfficialLinksResponse {
		links = DtoValidation.immutableList(links, "links");
	}
}
