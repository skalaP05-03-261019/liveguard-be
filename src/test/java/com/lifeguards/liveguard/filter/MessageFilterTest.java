package com.lifeguards.liveguard.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.Visibility;

class MessageFilterTest {

	private static final Set<String> CHANNEL_A_HOSTS = Set.of("live-a.example.com");

	private final MessageFilter filter = new MessageFilter(FilterDictionary.loadDefault());

	// 평가 사례 (docs/live-guard/v0.3/policies/evaluation_cases.md). D01만 원문이 있고 D03·D06·D07은 설명에 맞춰 작성

	@Test
	void d01PositiveExaggerationIsReviewAndStaysPublic() {
		FilterDecision decision = classify("무대 미쳤다ㅋㅋ");

		assertEquals(FilterResult.REVIEW, decision.result());
		assertEquals(Visibility.PUBLIC, decision.visibility());
		assertEquals(List.of(MessageFilter.ATTACK_TERM), decision.matchingTags());
	}

	@Test
	void d03OfficialNoticeWithOfficialHostIsPassEvenWithLoginWord() {
		FilterDecision decision = classify("[공식] 신규 가입 이벤트는 https://live-a.example.com/event 에서 로그인 후 참여하세요");

		assertEquals(FilterResult.PASS, decision.result());
		assertEquals(Visibility.PUBLIC, decision.visibility());
		assertEquals(List.of(MessageFilter.OFFICIAL_CLAIM, MessageFilter.CREDENTIAL_REQUEST), decision.matchingTags());
	}

	@Test
	void d06ExternalNewsLinkAloneIsPass() {
		FilterDecision decision = classify("오늘 기사 떴네요 https://news.example.org/article/123");

		assertEquals(FilterResult.PASS, decision.result());
		assertEquals(List.of(MessageFilter.UNOFFICIAL_URL), decision.matchingTags());
	}

	@Test
	void d07QuotedScamWarningIsHoldAndHeld() {
		FilterDecision decision = classify(
				"주의! \"공식 이벤트 당첨, http://live-a-event.evil.test 에서 로그인하세요\" 이런 메시지는 사기입니다");

		assertEquals(FilterResult.HOLD, decision.result());
		assertEquals(Visibility.HELD, decision.visibility());
		assertEquals(List.of(MessageFilter.OFFICIAL_CLAIM, MessageFilter.CREDENTIAL_REQUEST,
				MessageFilter.UNOFFICIAL_URL), decision.matchingTags());
	}

	@Test
	void holdNeedsAllThreeConditions() {
		assertEquals(FilterResult.PASS, classify("공식 이벤트 http://evil.test 참여").result());
		assertEquals(FilterResult.PASS, classify("이 사이트 로그인 http://evil.test").result());
		assertEquals(FilterResult.PASS, classify("공식 이벤트입니다 로그인하세요").result());
		assertEquals(FilterResult.HOLD, classify("공식 이벤트입니다 evil.test/login 에서 로그인하세요").result());
	}

	@Test
	void hostIsComparedExactlyAgainstOfficialHosts() {
		String template = "공식 안내: %s 에서 로그인하세요";

		assertEquals(FilterResult.PASS, classify(template.formatted("https://live-a.example.com/x")).result());
		assertEquals(FilterResult.PASS, classify(template.formatted("https://LIVE-A.example.com./x")).result());
		assertEquals(FilterResult.HOLD, classify(template.formatted("https://live-a.example.com.evil.test")).result());
		assertEquals(FilterResult.HOLD, classify(template.formatted("https://www.live-a.example.com")).result());
		assertEquals(FilterResult.HOLD, classify(template.formatted("https://user@evil.test:8443/a")).result());
	}

	@Test
	void hostIsParsedFromOriginalTextNotFromNormalizedText() {
		// 정규화하면 wwwww → ww로 줄지만 호스트는 원문 그대로 비교해야 한다
		FilterDecision decision = filter.classify(new FilterInput("공식 로그인 https://wwwww.example.com",
				TextNormalizer.normalize("공식 로그인 https://wwwww.example.com"), Set.of("wwwww.example.com"), 1));

		assertEquals(FilterResult.PASS, decision.result());
	}

	@Test
	void fullwidthUrlIsStillDetected() {
		assertEquals(FilterResult.HOLD, classify("공식 이벤트 ｅｖｉｌ．ｔｅｓｔ 에서 로그인").result());
	}

	@Test
	void urlAloneOrPhoneOrAccountPatternAloneIsPass() {
		assertEquals(FilterResult.PASS, classify("http://evil.test").result());
		assertEquals(FilterResult.PASS, classify("연락주세요 010-1234-5678").result());
		assertEquals(FilterResult.PASS, classify("국민 123456-78-901234 입금").result());
	}

	@Test
	void floodStartsAtTheThirdSameTextWithinWindow() {
		assertEquals(FilterResult.PASS, classify("같은 말", 1).result());
		assertEquals(FilterResult.PASS, classify("같은 말", 2).result());
		FilterDecision third = classify("같은 말", 3);
		assertEquals(FilterResult.FLOOD, third.result());
		assertEquals(Visibility.HELD, third.visibility());
		assertEquals(List.of(MessageFilter.REPEATED_TEXT), third.matchingTags());
	}

	@Test
	void precedenceIsHoldThenFloodThenReview() {
		FilterDecision holdAndFlood = classify("공식 로그인 http://evil.test 꺼져", 5);
		assertEquals(FilterResult.HOLD, holdAndFlood.result());
		assertTrue(holdAndFlood.matchingTags().containsAll(List.of(MessageFilter.ATTACK_TERM,
				MessageFilter.REPEATED_TEXT, MessageFilter.UNOFFICIAL_URL)));

		assertEquals(FilterResult.FLOOD, classify("꺼져", 3).result());
		assertEquals(FilterResult.REVIEW, classify("꺼져", 2).result());
	}

	@ParameterizedTest
	@ValueSource(strings = { "ＯＦＦＩＣＩＡＬ 안내", "OFFICIAL 관리자", "무대 미쳤다", "꺼져  제발" })
	void dictionaryTermsAreComparedInNormalizedForm(String text) {
		assertTrue(classify(text).matchingTags().stream()
				.anyMatch(tag -> tag.equals(MessageFilter.ATTACK_TERM) || tag.equals(MessageFilter.OFFICIAL_CLAIM)));
	}

	@Test
	void dictionaryEntriesAreNormalizedOnLoad() {
		FilterDictionary dictionary = new FilterDictionary(List.of("꺼져", "  "), List.of("OFFICIAL"), List.of(),
				List.of("ㅅㅂ"), List.of("ＳＩＢＡＬ점"));

		assertEquals(List.of("꺼져"), dictionary.attackTerms());
		assertEquals(List.of("official"), dictionary.officialClaimTerms());
		assertEquals(List.of(TextNormalizer.normalize("ㅅㅂ")), dictionary.certainProfanityTerms());
		assertEquals("ᄉᄇ", dictionary.certainProfanityTerms().get(0), "호환 자모가 초성 자모로 바뀐 형태로 저장된다");
		assertEquals(List.of("sibal점"), dictionary.profanityExceptions());
	}

	// ----- 확실한 욕설: HOLD (피싱과 구분은 matchingTags) -----

	@ParameterizedTest
	@ValueSource(strings = { "시발", "너 진짜 씨발 ㅋㅋ", "ㅅㅂ", "ㅅㅂㅅㅂ", "진짜  ㅂㅅ  같네", "ㅆㅂ", "개새끼야", "시발발발발", "ㅅㅂㅂㅂㅂ",
			"느금마", "좆같네" })
	void certainProfanityIsHoldAndHeld(String text) {
		FilterDecision decision = classify(text);

		assertEquals(FilterResult.HOLD, decision.result(), text);
		assertEquals(Visibility.HELD, decision.visibility());
		assertEquals(List.of(MessageFilter.CERTAIN_PROFANITY), decision.matchingTags());
	}

	@Test
	void d01StaysReviewBecauseItIsOnlyAnAmbiguousAttackTerm() {
		FilterDecision decision = classify("무대 미쳤다ㅋㅋ");

		assertEquals(FilterResult.REVIEW, decision.result());
		assertEquals(Visibility.PUBLIC, decision.visibility());
		assertEquals(List.of(MessageFilter.ATTACK_TERM), decision.matchingTags());
	}

	@ParameterizedTest
	@ValueSource(strings = { "시발점에서 출발합니다", "다음 역은 시발역입니다", "병신년 새해", "이시 발표 시작", "시바견 귀엽다", "존나 좋다" })
	void benignWordsOrAmbiguousSlangAreNotCertainProfanity(String text) {
		assertEquals(FilterResult.PASS, classify(text).result(), text);
	}

	@Test
	void exceptionWordDoesNotShieldRealProfanityInTheSameMessage() {
		FilterDecision decision = classify("시발점 가는 버스 씨발");

		assertEquals(FilterResult.HOLD, decision.result());
		assertEquals(List.of(MessageFilter.CERTAIN_PROFANITY), decision.matchingTags());
	}

	@Test
	void certainProfanityAndAmbiguousAttackKeepBothTags() {
		FilterDecision decision = classify("씨발 꺼져");

		assertEquals(FilterResult.HOLD, decision.result());
		assertEquals(List.of(MessageFilter.ATTACK_TERM, MessageFilter.CERTAIN_PROFANITY), decision.matchingTags());
	}

	@Test
	void certainProfanityAndPhishingAreOneHoldWithBothTags() {
		FilterDecision decision = classify("공식 이벤트 씨발 evil.test 에서 로그인하세요");

		assertEquals(FilterResult.HOLD, decision.result());
		assertEquals(List.of(MessageFilter.CERTAIN_PROFANITY, MessageFilter.OFFICIAL_CLAIM,
				MessageFilter.CREDENTIAL_REQUEST, MessageFilter.UNOFFICIAL_URL), decision.matchingTags());
	}

	@Test
	void certainProfanityBeatsFloodAndKeepsTheRepeatedTag() {
		FilterDecision decision = classify("씨발", 3);

		assertEquals(FilterResult.HOLD, decision.result());
		assertEquals(List.of(MessageFilter.CERTAIN_PROFANITY, MessageFilter.REPEATED_TEXT), decision.matchingTags());
		assertEquals(FilterResult.HOLD, classify("씨발", 1).result());
	}

	@Test
	void phishingHoldStillCarriesNoProfanityTag() {
		FilterDecision decision = classify("공식 이벤트 evil.test 에서 로그인하세요");

		assertEquals(FilterResult.HOLD, decision.result());
		assertFalse(decision.matchingTags().contains(MessageFilter.CERTAIN_PROFANITY));
		assertTrue(decision.matchingTags().contains(MessageFilter.UNOFFICIAL_URL));
	}

	@Test
	void versionCombinesNormalizationAndDictionaryVersions() {
		assertEquals(TextNormalizer.VERSION + "+" + FilterDictionary.VERSION, MessageFilter.version());
		assertEquals("norm-v1+dict-v2", MessageFilter.version());
	}

	@Test
	void plainChatIsPass() {
		FilterDecision decision = classify("오늘 무대 좋았어요");

		assertEquals(FilterResult.PASS, decision.result());
		assertEquals(List.of(), decision.matchingTags());
	}

	private FilterDecision classify(String text) {
		return classify(text, 1);
	}

	private FilterDecision classify(String text, int sameTextCount) {
		return filter.classify(new FilterInput(text, TextNormalizer.normalize(text), CHANNEL_A_HOSTS, sameTextCount));
	}

}
