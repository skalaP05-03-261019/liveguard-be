package com.lifeguards.liveguard.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.MatchingTag;

/**
 * 1차 필터 (docs/live-guard/v0.3/policies/classification-and-actions.md 3절).
 * <ul>
 * <li>HOLD: 피싱(공식 자칭 + 로그인·인증 요구 + 비공식 URL이 모두 있음) 또는 확실한 욕설.
 * 둘은 matchingTags(UNOFFICIAL_URL 등 / CERTAIN_PROFANITY)로 구분한다</li>
 * <li>FLOOD: 같은 사용자·채널에서 10초 안에 같은 정규화 문장 3회 이상 (세 번째부터)</li>
 * <li>REVIEW: 공격·비하 사전 매칭 (애매한 표현)</li>
 * </ul>
 * URL 하나만, 전화·계좌 패턴만으로는 HOLD가 아니다. 입력만으로 결정하는 순수 함수다.
 */
@Component
public class MessageFilter {

	public static final int FLOOD_THRESHOLD = 3;

	public static final String ATTACK_TERM = MatchingTag.ATTACK_TERM.name();
	public static final String CERTAIN_PROFANITY = MatchingTag.CERTAIN_PROFANITY.name();
	public static final String OFFICIAL_CLAIM = MatchingTag.OFFICIAL_CLAIM.name();
	public static final String CREDENTIAL_REQUEST = MatchingTag.CREDENTIAL_REQUEST.name();
	public static final String UNOFFICIAL_URL = MatchingTag.UNOFFICIAL_URL.name();
	public static final String REPEATED_TEXT = MatchingTag.REPEATED_TEXT.name();

	private final FilterDictionary dictionary;

	public MessageFilter() {
		this(FilterDictionary.loadDefault());
	}

	public MessageFilter(FilterDictionary dictionary) {
		this.dictionary = dictionary;
	}

	public FilterDecision classify(FilterInput input) {
		String normalized = input.normalizedText();
		boolean attack = containsAny(normalized, dictionary.attackTerms());
		boolean certainProfanity = containsAny(withoutExceptions(normalized), dictionary.certainProfanityTerms());
		boolean officialClaim = containsAny(normalized, dictionary.officialClaimTerms());
		boolean credential = containsAny(normalized, dictionary.credentialTerms());
		Set<String> official = input.officialHosts().stream().map(UrlHosts::normalizeHost).collect(Collectors.toSet());
		boolean unofficialUrl = UrlHosts.extract(input.text()).stream().anyMatch(host -> !official.contains(host));
		boolean repeated = input.sameTextCountInWindow() >= FLOOD_THRESHOLD;

		List<String> tags = new ArrayList<>();
		if (attack) {
			tags.add(ATTACK_TERM);
		}
		if (certainProfanity) {
			tags.add(CERTAIN_PROFANITY);
		}
		if (officialClaim) {
			tags.add(OFFICIAL_CLAIM);
		}
		if (credential) {
			tags.add(CREDENTIAL_REQUEST);
		}
		if (unofficialUrl) {
			tags.add(UNOFFICIAL_URL);
		}
		if (repeated) {
			tags.add(REPEATED_TEXT);
		}

		FilterResult result;
		if ((officialClaim && credential && unofficialUrl) || certainProfanity) {
			result = FilterResult.HOLD;
		} else if (repeated) {
			result = FilterResult.FLOOD;
		} else if (attack) {
			result = FilterResult.REVIEW;
		} else {
			result = FilterResult.PASS;
		}
		return new FilterDecision(result, tags);
	}

	/** 욕설 글자를 포함하는 정상 단어(시발점 등)를 비교 전에 지운다. */
	private String withoutExceptions(String normalized) {
		String text = normalized;
		for (String exception : dictionary.profanityExceptions()) {
			text = text.replace(exception, " ");
		}
		return text;
	}

	private static boolean containsAny(String text, List<String> terms) {
		return terms.stream().anyMatch(text::contains);
	}

}
