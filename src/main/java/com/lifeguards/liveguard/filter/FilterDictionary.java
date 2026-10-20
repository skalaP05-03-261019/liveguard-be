package com.lifeguards.liveguard.filter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 1차 필터 사전 (dict-v1). 항목은 로드할 때 {@link TextNormalizer}로 정규화한다.
 * NFKC가 호환 자모(ㅅ, ㅂ…)를 초성 자모로 바꾸므로, 정규화된 메시지와 같은 형태로 맞춰야 비교가 된다.
 */
public record FilterDictionary(List<String> attackTerms, List<String> officialClaimTerms,
		List<String> credentialTerms) {

	public static final String VERSION = "dict-v1";

	public FilterDictionary {
		attackTerms = normalizeAll(attackTerms);
		officialClaimTerms = normalizeAll(officialClaimTerms);
		credentialTerms = normalizeAll(credentialTerms);
	}

	public static FilterDictionary loadDefault() {
		return new FilterDictionary(read("filter/attack-terms.txt"), read("filter/official-claim-terms.txt"),
				read("filter/credential-terms.txt"));
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
