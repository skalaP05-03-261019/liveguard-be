package com.lifeguards.liveguard.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.IDN;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PiiMaskerTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"연락처 010-1234-5678 로 주세요 | 연락처 [PHONE] 로 주세요",
			"01012345678 | [PHONE]",
			"+82 10-1234-5678 | [PHONE]",
			"사무실 02-123-4567 | 사무실 [PHONE]",
			"지역 031 1234 5678 | 지역 [PHONE]",
			"메일 me.name+tag@example.co.kr 로 | 메일 [EMAIL] 로",
			"국민 123-45-678901 입금 | 국민 [ACCOUNT] 입금",
			"계좌 1234567890123 | 계좌 [ACCOUNT]",
			"카드 1234-5678-9012-3456 | 카드 [ACCOUNT]",
			"https://evil.test/login?token=SECRET 접속 | [URL:evil.test] 접속",
			"HTTP://User:pw@Evil.Test:8443/a#x | [URL:evil.test]",
			"evil.test/login?id=1 로 가세요 | [URL:evil.test] 로 가세요",
			"연락 ０１０-１２３４-５６７８ | 연락 [PHONE]",
			"메일 user@예시.com 로 | 메일 [EMAIL] 로",
			"메일 홍길동@example.com 로 | 메일 [EMAIL] 로",
	})
	void masksPersonalDataAndLinks(String input, String expected) {
		assertEquals(expected, PiiMasker.mask(input));
	}

	@Test
	void urlHostIsConvertedToPunycode() {
		assertEquals("[URL:" + IDN.toASCII("공식.example.com") + "]", PiiMasker.mask("https://공식.example.com/a"));
	}

	@Test
	void emailLocalPartIsNotMistakenForADomain() {
		assertEquals("메일 [EMAIL] 로", PiiMasker.mask("메일 me.name+tag@example.co.kr 로"));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"2026-10-20 공연 | 2026-10-20 공연",
			"3.5점 좋아요.감사 | 3.5점 좋아요.감사",
			"오늘 무대 최고 | 오늘 무대 최고",
			"티켓 2매 12000원 | 티켓 2매 12000원",
	})
	void leavesOrdinaryTextAlone(String input, String expected) {
		assertEquals(expected, PiiMasker.mask(input));
	}

}
