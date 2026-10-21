package com.lifeguards.liveguard.filter;

/**
 * @param text 원문 (URL 파싱용)
 * @param normalizedText norm-v1 결과 (사전 비교용)
 * @param sameTextCountInWindow 같은 사용자·방송에서 최근 10초 동안 같은 정규화 문장 수 (이번 메시지 포함)
 */
public record FilterInput(String text, String normalizedText, int sameTextCountInWindow, boolean floodRecurrence) {
	public FilterInput(String text, String normalizedText, int sameTextCountInWindow) {
		this(text, normalizedText, sameTextCountInWindow, false);
	}
}
