package com.lifeguards.liveguard.filter;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.lifeguards.liveguard.contracts.enums.HandlingRoute;
import com.lifeguards.liveguard.contracts.enums.MatchingTag;

/**
 * 1차 필터 (정책 v3.1, TBD-02). 한 메시지는 처리 경로 하나를 갖고, 걸린 신호는 matchingTags에 전부 남긴다.
 * <ol>
 * <li>RULE_CONFIRMED(HIDDEN): 확실한 욕설 또는 링크. 공식 링크도 예외 없이 금지한다</li>
 * <li>FLOOD_REVIEW(HELD): 같은 사용자·방송에서 10초 안에 같은 정규화 문장 3회 이상 (세 번째부터)</li>
 * <li>PII_INVESTIGATION(HELD): 개인정보 필터 PR 전까지 나오지 않는다</li>
 * <li>CONTEXT_REVIEW(HELD): 공격·비하 사전 매칭, 또는 링크 없는 공식 자칭 + 로그인·인증 요구</li>
 * <li>PASS(PUBLIC)</li>
 * </ol>
 * 전화·계좌 패턴만으로는 후보가 아니다. 입력만으로 결정하는 순수 함수다.
 */
@Component
public class MessageFilter {

	public static final int FLOOD_THRESHOLD = 3;

	public static final String ATTACK_TERM = MatchingTag.ATTACK_TERM.name();
	public static final String CERTAIN_PROFANITY = MatchingTag.CERTAIN_PROFANITY.name();
	public static final String OFFICIAL_CLAIM = MatchingTag.OFFICIAL_CLAIM.name();
	public static final String CREDENTIAL_REQUEST = MatchingTag.CREDENTIAL_REQUEST.name();
	public static final String LINK = MatchingTag.LINK.name();
	public static final String REPEATED_TEXT = MatchingTag.REPEATED_TEXT.name();

	/** RULE_CONFIRMED의 확정 근거 규칙 식별자. 사전·정규화 버전은 {@link #version()}, 정책 버전은 따로 기록한다. */
	public static final String RULE_CERTAIN_PROFANITY = "RULE_CERTAIN_PROFANITY";
	public static final String RULE_LINK_BLOCKED = "RULE_LINK_BLOCKED";

	private final FilterDictionary dictionary;

	/** 판정에 쓴 정규화·사전 버전 조합 (예: norm-v1+dict-v2). 후보와 함께 전달해 재현·감사에 쓴다. */
	public static String version() {
		return TextNormalizer.VERSION + "+" + FilterDictionary.VERSION;
	}

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
		boolean link = !UrlHosts.extract(input.text()).isEmpty();
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
		if (link) {
			tags.add(LINK);
		}
		if (repeated) {
			tags.add(REPEATED_TEXT);
		}

		if (certainProfanity) {
			return new FilterDecision(HandlingRoute.RULE_CONFIRMED, RULE_CERTAIN_PROFANITY, tags);
		}
		if (link) {
			return new FilterDecision(HandlingRoute.RULE_CONFIRMED, RULE_LINK_BLOCKED, tags);
		}
		if (repeated) {
			return new FilterDecision(HandlingRoute.FLOOD_REVIEW, null, tags);
		}
		if (attack || (officialClaim && credential)) {
			return new FilterDecision(HandlingRoute.CONTEXT_REVIEW, null, tags);
		}
		return new FilterDecision(HandlingRoute.PASS, null, tags);
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
