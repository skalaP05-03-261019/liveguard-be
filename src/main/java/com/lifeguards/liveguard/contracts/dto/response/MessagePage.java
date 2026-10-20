package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record MessagePage(List<Message> items, String nextCursor) {
	public MessagePage {
		items = DtoValidation.immutableList(items, "items");
	}
}
