package com.lifeguards.liveguard.filter;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * norm-v1: NFKC → 소문자 → 공백 축약(앞뒤 제거) → 같은 문자 3개 이상은 2개로.
 * 필터 비교용이며 원문은 그대로 보관한다. 숫자·URL에도 적용되므로 URL 호스트는 원문에서 파싱한다.
 */
public final class TextNormalizer {

	public static final String VERSION = "norm-v1";

	private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
	private static final Pattern REPEATED_CHARACTER = Pattern.compile("(.)\\1{2,}", Pattern.DOTALL);

	private TextNormalizer() {
	}

	public static String normalize(String text) {
		String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
		normalized = WHITESPACE.matcher(normalized).replaceAll(" ").strip();
		return REPEATED_CHARACTER.matcher(normalized).replaceAll("$1$1");
	}

}
