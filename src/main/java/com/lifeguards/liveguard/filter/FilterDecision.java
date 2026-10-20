package com.lifeguards.liveguard.filter;

import java.util.List;

import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.Visibility;

/** 대표 결과(HOLD > FLOOD > REVIEW > PASS)와 매칭된 태그 전부 (9번에서 InternalCase.matchingTags로 전달). */
public record FilterDecision(FilterResult result, List<String> matchingTags) {

	public FilterDecision {
		matchingTags = List.copyOf(matchingTags);
	}

	/** HOLD·FLOOD는 작성자·담당 운영자에게만 보이는 HELD로 저장한다. */
	public Visibility visibility() {
		return result == FilterResult.HOLD || result == FilterResult.FLOOD ? Visibility.HELD : Visibility.PUBLIC;
	}

}
