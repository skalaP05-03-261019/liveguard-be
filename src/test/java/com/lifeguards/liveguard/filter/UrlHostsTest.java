package com.lifeguards.liveguard.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.IDN;
import java.util.Set;

import org.junit.jupiter.api.Test;

class UrlHostsTest {

	@Test
	void extractsHostsWithAndWithoutScheme() {
		assertEquals(Set.of("a.example.com", "evil.test", "news.example.org"), UrlHosts.extract(
				"https://a.example.com/path?q=1 그리고 evil.test/login, 또 HTTP://News.Example.ORG"));
	}

	@Test
	void stripsUserInfoPortAndTrailingDot() {
		assertEquals(Set.of("evil.test"), UrlHosts.extract("https://me:pw@evil.test.:8443/a"));
	}

	@Test
	void convertsIdnToPunycode() {
		assertEquals(Set.of(IDN.toASCII("공식.example.com")), UrlHosts.extract("https://공식.example.com"));
		assertEquals(true, UrlHosts.extract("https://공식.example.com").iterator().next().startsWith("xn--"));
	}

	@Test
	void ignoresEmailDomainsAndPlainKoreanSentences() {
		assertEquals(Set.of(), UrlHosts.extract("메일은 me@evil.test 로"));
		assertEquals(Set.of(), UrlHosts.extract("좋아요.감사합니다 3.5점"));
	}

	@Test
	void ignoresEmailsWithDottedLocalPart() {
		assertEquals(Set.of(), UrlHosts.extract("first.last@example.com"));
		assertEquals(Set.of(), UrlHosts.extract("문의는 a.b.c+tag@sub.example.co.kr 로 주세요"));
	}

	@Test
	void keepsLinksNextToEmails() {
		assertEquals(Set.of("evil.test"), UrlHosts.extract("first.last@example.com evil.test/login"));
		assertEquals(Set.of("evil.test"), UrlHosts.extract("https://first.last@evil.test/login"));
	}

	@Test
	void keepsRepeatedCharactersInHost() {
		assertEquals(Set.of("wwwww.example.com"), UrlHosts.extract("wwwww.example.com"));
	}

}
