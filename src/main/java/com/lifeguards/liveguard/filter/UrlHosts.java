package com.lifeguards.liveguard.filter;

import java.net.IDN;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 메시지 원문에서 URL 호스트를 뽑는다. normalized_text는 반복 문자를 줄여 호스트가 바뀌므로 쓰지 않는다
 * (예: wwwww.a.com → ww.a.com). 전각 문자 우회를 막기 위해 NFKC만 적용한 원문에서 찾는다.
 */
public final class UrlHosts {

	private static final Pattern WITH_SCHEME = Pattern.compile("(?i)\\bhttps?://([^\\s/?#]+)");
	// scheme 없는 도메인: 라벨은 유니코드 허용, 최상위 도메인은 영문 2자 이상 (예: evil.test/login)
	private static final Pattern BARE_DOMAIN = Pattern.compile(
			"(?<![\\p{L}\\p{N}@.\\-])((?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}\\-]*[\\p{L}\\p{N}])?\\.)+[A-Za-z]{2,63})(?![\\p{L}\\p{N}\\-])");

	private UrlHosts() {
	}

	public static Set<String> extract(String rawText) {
		String text = Normalizer.normalize(rawText, Normalizer.Form.NFKC);
		Set<String> hosts = new LinkedHashSet<>();
		Matcher withScheme = WITH_SCHEME.matcher(text);
		StringBuilder rest = new StringBuilder();
		while (withScheme.find()) {
			hosts.add(normalizeHost(stripUserInfoAndPort(withScheme.group(1))));
			withScheme.appendReplacement(rest, " ");
		}
		withScheme.appendTail(rest);

		Matcher bare = BARE_DOMAIN.matcher(rest);
		while (bare.find()) {
			hosts.add(normalizeHost(bare.group(1)));
		}
		hosts.remove("");
		return hosts;
	}

	/** 소문자 → 끝의 점 제거 → IDN(punycode). 공식 호스트와 정확히 비교하기 위한 형태. */
	public static String normalizeHost(String host) {
		String normalized = host.toLowerCase(Locale.ROOT);
		while (normalized.endsWith(".")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		try {
			return IDN.toASCII(normalized, IDN.ALLOW_UNASSIGNED);
		} catch (IllegalArgumentException ex) {
			return normalized;
		}
	}

	private static String stripUserInfoAndPort(String authority) {
		String host = authority.substring(authority.lastIndexOf('@') + 1);
		int port = host.lastIndexOf(':');
		return port >= 0 ? host.substring(0, port) : host;
	}

}
