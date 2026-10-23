package com.lifeguards.liveguard.filter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 1차 필터 사전 (dict-v3). 항목은 로드할 때 {@link TextNormalizer}로 정규화한다.
 * dict-v3은 공격 사전(문맥 후보)에 욕설 변형 '씨바'를 더했다(A-30). 이전 기록은 dict-v2다.
 * NFKC가 호환 자모(ㅅ, ㅂ…)를 초성 자모로 바꾸므로, 정규화된 메시지와 같은 형태로 맞춰야 비교가 된다.
 */
public record FilterDictionary(List<String> attackTerms, List<String> officialClaimTerms,
		List<String> credentialTerms, List<String> certainProfanityTerms, List<String> profanityExceptions) {

	public static final String VERSION = "dict-v3";

	public FilterDictionary {
		attackTerms = normalizeAll(attackTerms);
		officialClaimTerms = normalizeAll(officialClaimTerms);
		credentialTerms = normalizeAll(credentialTerms);
		certainProfanityTerms = normalizeAll(certainProfanityTerms);
		profanityExceptions = normalizeAll(profanityExceptions);
	}

	public static FilterDictionary loadDefault() {
		return new FilterDictionary(read("filter/attack-terms.txt"), read("filter/official-claim-terms.txt"),
				read("filter/credential-terms.txt"), read("filter/profanity-certain.txt"),
				read("filter/profanity-exceptions.txt"));
	}

	private static List<String> normalizeAll(List<String> terms) {
		return terms.stream().map(TextNormalizer::normalize).filter(term -> !term.isEmpty()).distinct().toList();
	}

	private static List<String> read(String resource) {
		try (InputStream in = FilterDictionary.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("Missing filter dictionary: " + resource);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
					.map(String::strip)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.toList();
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
