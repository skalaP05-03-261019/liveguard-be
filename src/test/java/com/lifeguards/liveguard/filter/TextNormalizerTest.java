package com.lifeguards.liveguard.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TextNormalizerTest {

	@Test
	void versionIsNormV1() {
		assertEquals("norm-v1", TextNormalizer.VERSION);
	}

	@Test
	void appliesNfkcFullwidthToHalfwidthAndHalfwidthKatakanaToFullwidth() {
		assertEquals("abc123!", TextNormalizer.normalize("ＡＢＣ１２３！"));
		assertEquals("カタカナ", TextNormalizer.normalize("ｶﾀｶﾅ"));
		assertEquals("kg", TextNormalizer.normalize("㎏"));
	}

	@Test
	void composesHangulJamo() {
		assertEquals("가나", TextNormalizer.normalize("가나"));
	}

	@Test
	void lowercases() {
		assertEquals("hello world", TextNormalizer.normalize("HeLLo WoRLD"));
	}

	@Test
	void collapsesWhitespaceAndTrims() {
		assertEquals("a b c", TextNormalizer.normalize("  a \t b\n\n c 　 "));
	}

	@ParameterizedTest
	@CsvSource({
			"!!!!!!, !!",
			"aaa, aa",
			"aa, aa",
			"a, a",
			"abab, abab",
			"10000원, 100원",
			"wwwww.example.com, ww.example.com",
	})
	void shrinksThreeOrMoreSameCharactersToTwo(String input, String expected) {
		assertEquals(expected, TextNormalizer.normalize(input));
	}

	@Test
	void nfkcTurnsCompatibilityJamoIntoConjoiningJamoBeforeShrinking() {
		// ㅋ(U+314B)은 NFKC에서 초성 ᄏ(U+110F)로 바뀐다. 사전 항목도 같은 함수로 정규화해야 비교가 맞는다
		assertEquals("\u110f\u110f", TextNormalizer.normalize("ㅋㅋㅋㅋ"));
		assertEquals(TextNormalizer.normalize("ㅋㅋ"), TextNormalizer.normalize("ㅋㅋㅋㅋㅋㅋ"));
	}

	@Test
	void shrinksRepeatedSupplementaryCodePointsWithoutSplittingSurrogatePairs() {
		assertEquals("😀😀", TextNormalizer.normalize("😀😀😀😀😀"));
		assertEquals("😀😁😀", TextNormalizer.normalize("😀😁😀"));
	}

	@Test
	void appliesStepsInOrder() {
		// NFKC 뒤 소문자: 전각 대문자 반복이 같은 문자로 합쳐져 축소된다
		assertEquals("aa", TextNormalizer.normalize("ＡａＡａ".substring(0, 3)));
		// 공백 축약 뒤 반복 축소: 공백이 먼저 한 칸이 되므로 공백 3개가 2개가 아니라 1개가 된다
		assertEquals("a b", TextNormalizer.normalize("a   b"));
	}

	@Test
	void blankInputBecomesEmpty() {
		assertEquals("", TextNormalizer.normalize(" \t\n "));
	}

	@ParameterizedTest
	@ValueSource(strings = { "ＨＥＬＬＯ   wwwwww!!!", "시발   ㅋㅋㅋㅋㅋ", "😀😀😀 a  b", "İstanbul", "ǅ" })
	void isIdempotent(String input) {
		String once = TextNormalizer.normalize(input);
		assertEquals(once, TextNormalizer.normalize(once));
	}

}
