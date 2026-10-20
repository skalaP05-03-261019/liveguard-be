package com.lifeguards.liveguard.filter;

import java.util.Set;

/**
 * @param text 원문 (URL 호스트 파싱용)
 * @param normalizedText norm-v1 결과 (사전 비교용)
 * @param officialHosts 채널의 공식 호스트
 * @param sameTextCountInWindow 같은 사용자·채널에서 최근 10초 동안 같은 정규화 문장 수 (이번 메시지 포함)
 */
public record FilterInput(String text, String normalizedText, Set<String> officialHosts, int sameTextCountInWindow) {
}
