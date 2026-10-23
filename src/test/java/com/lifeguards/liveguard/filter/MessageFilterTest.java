package com.lifeguards.liveguard.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.HandlingRoute;
import com.lifeguards.liveguard.contracts.enums.MatchingTag;
import com.lifeguards.liveguard.contracts.enums.ModerationReasonCode;
import com.lifeguards.liveguard.contracts.enums.Visibility;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

class MessageFilterTest {

	private final MessageFilter filter = new MessageFilter(FilterDictionary.loadDefault());

	// 평가 사례 (docs/live-guard/v0.3/policies/evaluation_cases.md). D01만 원문이 있고 D03·D06·D07은 설명에 맞춰 작성.
	// 기대값은 v3.1 기준이다(v0.3과 다름): 공격어는 HELD, 링크는 공식 링크 포함 HIDDEN

	@Test
	void d01PositiveExaggerationIsContextReviewAndHeld() {
		FilterDecision decision = classify("무대 미쳤다ㅋㅋ");

		assertEquals(HandlingRoute.CONTEXT_REVIEW, decision.handlingRoute());
		assertEquals(FilterResult.HOLD, decision.result());
		assertEquals(Visibility.HELD, decision.visibility());
		assertNull(decision.ruleId());
		assertEquals(List.of(MessageFilter.ATTACK_TERM), decision.matchingTags());
	}

	@Test
	void d03OfficialLinkIsBlockedLikeAnyOtherLink() {
		FilterDecision decision = classify("[공식] 신규 가입 이벤트는 https://live-a.example.com/event 에서 로그인 후 참여하세요");

		assertEquals(HandlingRoute.RULE_CONFIRMED, decision.handlingRoute());
		assertEquals(MessageFilter.RULE_LINK_BLOCKED, decision.ruleId());
		assertEquals(Visibility.HIDDEN, decision.visibility());
		assertEquals(List.of(MessageFilter.OFFICIAL_CLAIM, MessageFilter.CREDENTIAL_REQUEST, MessageFilter.LINK),
				decision.matchingTags());
	}

	@Test
	void d06ExternalNewsLinkAloneIsRuleConfirmed() {
		FilterDecision decision = classify("오늘 기사 떴네요 https://news.example.org/article/123");

		assertEquals(HandlingRoute.RULE_CONFIRMED, decision.handlingRoute());
		assertEquals(MessageFilter.RULE_LINK_BLOCKED, decision.ruleId());
		assertEquals(FilterResult.HOLD, decision.result());
		assertEquals(Visibility.HIDDEN, decision.visibility());
		assertEquals(List.of(MessageFilter.LINK), decision.matchingTags());
	}

	@Test
	void d07QuotedScamWarningWithLinkIsRuleConfirmedAndHidden() {
		FilterDecision decision = classify(
				"주의! \"공식 이벤트 당첨, http://live-a-event.evil.test 에서 로그인하세요\" 이런 메시지는 사기입니다");

		assertEquals(HandlingRoute.RULE_CONFIRMED, decision.handlingRoute());
		assertEquals(Visibility.HIDDEN, decision.visibility());
		assertEquals(List.of(MessageFilter.OFFICIAL_CLAIM, MessageFilter.CREDENTIAL_REQUEST,
				MessageFilter.LINK), decision.matchingTags());
	}

	@Test
	void officialClaimWithCredentialRequestAndNoLinkIsContextReview() {
		FilterDecision decision = classify("공식 이벤트입니다 로그인하세요");

		assertEquals(HandlingRoute.CONTEXT_REVIEW, decision.handlingRoute());
		assertEquals(FilterResult.HOLD, decision.result());
		assertEquals(Visibility.HELD, decision.visibility());
		assertEquals(List.of(MessageFilter.OFFICIAL_CLAIM, MessageFilter.CREDENTIAL_REQUEST), decision.matchingTags());
	}

	@Test
	void officialClaimOrCredentialRequestAloneIsPass() {
		assertEquals(HandlingRoute.PASS, classify("공식 이벤트입니다").handlingRoute());
		assertEquals(HandlingRoute.PASS, classify("로그인하세요").handlingRoute());
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://live-a.example.com/x", "https://LIVE-A.example.com./x",
			"https://live-a.example.com.evil.test", "https://www.live-a.example.com", "https://user@evil.test:8443/a",
			"http://evil.test", "evil.test/login" })
	void everyLinkIsBlockedRegardlessOfHost(String url) {
		FilterDecision decision = classify("공식 안내: " + url + " 에서 확인하세요");

		assertEquals(HandlingRoute.RULE_CONFIRMED, decision.handlingRoute(), url);
		assertEquals(MessageFilter.RULE_LINK_BLOCKED, decision.ruleId(), url);
		assertTrue(decision.matchingTags().contains(MessageFilter.LINK), url);
	}

	@Test
	void fullwidthUrlIsStillDetected() {
		FilterDecision decision = classify("공식 이벤트 ｅｖｉｌ．ｔｅｓｔ 에서 로그인");

		assertEquals(HandlingRoute.RULE_CONFIRMED, decision.handlingRoute());
		assertTrue(decision.matchingTags().contains(MessageFilter.LINK));
	}

	@Test
	void phoneAccountOrEmailFormatIsAPiiCandidateAndHeld() {
		FilterDecision phone = classify("연락주세요 010-1234-5678");
		assertEquals(HandlingRoute.PII_INVESTIGATION, phone.handlingRoute());
		assertEquals(FilterResult.HOLD, phone.result());
		assertEquals(Visibility.HELD, phone.visibility());
		assertNull(phone.ruleId());
		assertEquals(ModerationReasonCode.PII_PENDING, phone.moderationReasonCode());
		assertEquals(List.of(MatchingTag.PII_PHONE.name()), phone.matchingTags());

		assertEquals(List.of(MatchingTag.PII_ACCOUNT.name()), classify("국민 123456-78-901234 입금").matchingTags());
		assertEquals(List.of(MatchingTag.PII_EMAIL.name()), classify("메일 me@example.com").matchingTags());
		assertEquals(List.of(MatchingTag.PII_PHONE.name(), MatchingTag.PII_ACCOUNT.name(), MatchingTag.PII_EMAIL.name()),
				classify("010-1234-5678 / 110-123-456789 / me@example.com").matchingTags());
	}

	@ParameterizedTest
	@ValueSource(strings = { "12345678901234", "1004", "2026-10-20 21:30", "3.5점", "1,000,000원", "123-45-6789" })
	void numbersAloneOrShortNumberGroupsAreNotPiiCandidates(String text) {
		// AC-06: 숫자라는 이유만으로 후보가 되지 않는다. 하이픈 묶음은 10자리 이상만 계좌 형식이다
		assertEquals(HandlingRoute.PASS, classify(text).handlingRoute(), text);
		assertEquals(List.of(), classify(text).matchingTags(), text);
	}

	@Test
	void fullWidthDigitsDoNotEvadeThePhoneFormat() {
		assertEquals(HandlingRoute.PII_INVESTIGATION, classify("연락 ０１０-１２３４-５６７８").handlingRoute());
	}

	@Test
	void piiRanksBelowRuleAndFloodButAboveContext() {
		// TBD-02: RULE_CONFIRMED > FLOOD_REVIEW > PII_INVESTIGATION > CONTEXT_REVIEW. 태그는 전부 남긴다
		FilterDecision link = classify("https://evil.test 010-1234-5678");
		assertEquals(HandlingRoute.RULE_CONFIRMED, link.handlingRoute());
		assertTrue(link.matchingTags().contains(MatchingTag.PII_PHONE.name()));

		assertEquals(HandlingRoute.FLOOD_REVIEW, classify("010-1234-5678", 3).handlingRoute());

		FilterDecision context = classify("꺼져 010-1234-5678");
		assertEquals(HandlingRoute.PII_INVESTIGATION, context.handlingRoute());
		assertEquals(List.of(MessageFilter.ATTACK_TERM, MatchingTag.PII_PHONE.name()), context.matchingTags());
	}

	@Test
	void numbersInsideALinkAreNotPiiCandidates() {
		assertEquals(List.of(MessageFilter.LINK), classify("https://evil.test/order/010-1234-5678").matchingTags());
	}

	@Test
	void floodStartsAtTheThirdSameTextWithinWindow() {
		assertEquals(HandlingRoute.PASS, classify("같은 말", 1).handlingRoute());
		assertEquals(HandlingRoute.PASS, classify("같은 말", 2).handlingRoute());
		FilterDecision third = classify("같은 말", 3);
		assertEquals(HandlingRoute.FLOOD_REVIEW, third.handlingRoute());
		assertEquals(FilterResult.FLOOD, third.result());
		assertEquals(Visibility.HELD, third.visibility());
		assertNull(third.ruleId());
		assertEquals(List.of(MessageFilter.REPEATED_TEXT), third.matchingTags());
	}

	@Test
	void precedenceIsRuleThenFloodThenContext() {
		FilterDecision ruleAndFlood = classify("공식 로그인 http://evil.test 꺼져", 5);
		assertEquals(HandlingRoute.RULE_CONFIRMED, ruleAndFlood.handlingRoute());
		assertTrue(ruleAndFlood.matchingTags().containsAll(List.of(MessageFilter.ATTACK_TERM,
				MessageFilter.REPEATED_TEXT, MessageFilter.LINK)));

		assertEquals(HandlingRoute.FLOOD_REVIEW, classify("꺼져", 3).handlingRoute());
		assertEquals(HandlingRoute.CONTEXT_REVIEW, classify("꺼져", 2).handlingRoute());
	}

	@Test
	void moderationReasonCodeFollowsRouteAndRule() {
		assertEquals(ModerationReasonCode.PROFANITY_INCLUDED, classify("씨발").moderationReasonCode());
		assertEquals(ModerationReasonCode.LINK_NOT_ALLOWED, classify("https://a.test").moderationReasonCode());
		// 욕설과 링크가 같이 걸리면 욕설 규칙이 근거다
		assertEquals(ModerationReasonCode.PROFANITY_INCLUDED, classify("씨발 https://a.test").moderationReasonCode());
		assertEquals(ModerationReasonCode.FLOOD_REPEATED, classify("같은 말", 3).moderationReasonCode());
		assertEquals(ModerationReasonCode.CONTEXT_PENDING, classify("꺼져").moderationReasonCode());
		assertEquals(ModerationReasonCode.CONTEXT_PENDING, classify("공식 로그인").moderationReasonCode());
		assertNull(classify("오늘 무대 좋았어요").moderationReasonCode());
	}

	@Test
	void filterNeverEmitsReview() {
		for (String text : List.of("꺼져", "씨발", "https://a.test", "공식 로그인", "같은 말", "010-1234-5678", "안녕")) {
			assertTrue(classify(text, 3).result() != FilterResult.REVIEW, text);
			assertTrue(classify(text, 1).result() != FilterResult.REVIEW, text);
		}
	}

	@Test
	void ruleIdIsRequiredExactlyForRuleConfirmed() {
		assertThrows(InvalidContractException.class,
				() -> new FilterDecision(HandlingRoute.RULE_CONFIRMED, null, List.of()));
		assertThrows(InvalidContractException.class,
				() -> new FilterDecision(HandlingRoute.CONTEXT_REVIEW, "RULE_LINK_BLOCKED", List.of()));
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
	void certainProfanityIsRuleConfirmedAndHidden(String text) {
		FilterDecision decision = classify(text);

		assertEquals(HandlingRoute.RULE_CONFIRMED, decision.handlingRoute(), text);
		assertEquals(MessageFilter.RULE_CERTAIN_PROFANITY, decision.ruleId());
		assertEquals(FilterResult.HOLD, decision.result(), text);
		assertEquals(Visibility.HIDDEN, decision.visibility());
		assertEquals(List.of(MessageFilter.CERTAIN_PROFANITY), decision.matchingTags());
	}

	@ParameterizedTest
	@ValueSource(strings = { "시발점에서 출발합니다", "다음 역은 시발역입니다", "병신년 새해", "이시 발표 시작", "시바견 귀엽다", "존나 좋다" })
	void benignWordsOrAmbiguousSlangAreNotCertainProfanity(String text) {
		assertEquals(FilterResult.PASS, classify(text).result(), text);
	}

	@Test
	void ssibaIsAContextCandidateNotCertainProfanity() {
		// A-30: 욕설 변형 '씨바'는 확정하지 않고 HELD 뒤 AI 조사와 운영자 결정에 맡긴다.
		// 부분 일치라 붙여 쓴 일상 표현도 걸리지만 숨김 확정·제재 없이 운영자가 정상으로 풀 수 있다
		for (String text : List.of("씨바 뭐하냐", "아 씨바", "날씨바뀌었네", "아저씨바쁘세요?")) {
			FilterDecision decision = classify(text);
			assertEquals(HandlingRoute.CONTEXT_REVIEW, decision.handlingRoute(), text);
			assertEquals(Visibility.HELD, decision.visibility(), text);
			assertNull(decision.ruleId(), text);
		}
	}

	@Test
	void exceptionWordDoesNotShieldRealProfanityInTheSameMessage() {
		FilterDecision decision = classify("시발점 가는 버스 씨발");

		assertEquals(HandlingRoute.RULE_CONFIRMED, decision.handlingRoute());
		assertEquals(List.of(MessageFilter.CERTAIN_PROFANITY), decision.matchingTags());
	}

	@Test
	void certainProfanityAndAmbiguousAttackKeepBothTags() {
		FilterDecision decision = classify("씨발 꺼져");

		assertEquals(HandlingRoute.RULE_CONFIRMED, decision.handlingRoute());
		assertEquals(List.of(MessageFilter.ATTACK_TERM, MessageFilter.CERTAIN_PROFANITY), decision.matchingTags());
	}

	@Test
	void certainProfanityAndLinkAreOneRuleWithBothTagsAndProfanityFirst() {
		FilterDecision decision = classify("공식 이벤트 씨발 evil.test 에서 로그인하세요");

		assertEquals(HandlingRoute.RULE_CONFIRMED, decision.handlingRoute());
		assertEquals(MessageFilter.RULE_CERTAIN_PROFANITY, decision.ruleId());
		assertEquals(List.of(MessageFilter.CERTAIN_PROFANITY, MessageFilter.OFFICIAL_CLAIM,
				MessageFilter.CREDENTIAL_REQUEST, MessageFilter.LINK), decision.matchingTags());
	}

	@Test
	void certainProfanityBeatsFloodAndKeepsTheRepeatedTag() {
		FilterDecision decision = classify("씨발", 3);

		assertEquals(HandlingRoute.RULE_CONFIRMED, decision.handlingRoute());
		assertEquals(List.of(MessageFilter.CERTAIN_PROFANITY, MessageFilter.REPEATED_TEXT), decision.matchingTags());
		assertEquals(HandlingRoute.RULE_CONFIRMED, classify("씨발", 1).handlingRoute());
	}

	@Test
	void linkRuleCarriesNoProfanityTag() {
		FilterDecision decision = classify("공식 이벤트 evil.test 에서 로그인하세요");

		assertEquals(MessageFilter.RULE_LINK_BLOCKED, decision.ruleId());
		assertFalse(decision.matchingTags().contains(MessageFilter.CERTAIN_PROFANITY));
		assertTrue(decision.matchingTags().contains(MessageFilter.LINK));
	}

	@Test
	void versionCombinesNormalizationDictionaryAndRulesVersions() {
		assertEquals(TextNormalizer.VERSION + "+" + FilterDictionary.VERSION + "+" + MessageFilter.RULES_VERSION,
				MessageFilter.version());
		// 사전 버전을 올리면(dict-v3, A-30) 이전 기록(norm-v1+dict-v2+rules-v2, #48 이전은 norm-v1+dict-v2)과 구분된다
		assertEquals("norm-v1+dict-v3+rules-v2", MessageFilter.version());
	}

	@ParameterizedTest
	@ValueSource(strings = { "first.last@example.com", "a.b.c+tag@sub.example.co.kr", "user@예시.com", "홍길동@example.com" })
	void emailIsNotALinkEvenWithDottedLocalPart(String text) {
		FilterDecision decision = classify(text);

		// 이메일은 링크가 아니라 개인정보 후보다(TBD-01). 숨김 확정이 아니라 조사용 HELD
		assertEquals(HandlingRoute.PII_INVESTIGATION, decision.handlingRoute(), text);
		assertEquals(Visibility.HELD, decision.visibility(), text);
		assertNull(decision.ruleId(), text);
		assertEquals(List.of(MatchingTag.PII_EMAIL.name()), decision.matchingTags(), text);
	}

	@Test
	void plainChatIsPass() {
		FilterDecision decision = classify("오늘 무대 좋았어요");

		assertEquals(HandlingRoute.PASS, decision.handlingRoute());
		assertEquals(FilterResult.PASS, decision.result());
		assertEquals(Visibility.PUBLIC, decision.visibility());
		assertNull(decision.ruleId());
		assertEquals(List.of(), decision.matchingTags());
	}

	private FilterDecision classify(String text) {
		return classify(text, 1);
	}

	private FilterDecision classify(String text, int sameTextCount) {
		return filter.classify(new FilterInput(text, TextNormalizer.normalize(text), sameTextCount));
	}

}
