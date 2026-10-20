package com.lifeguards.liveguard.filter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 내부 API(Agent·모델 입력)로 보내는 본문의 개인정보·링크를 마스킹한다 (계약 MaskedMessage.text).
 * 순서: scheme 있는 URL → 이메일 → scheme 없는 도메인 → 전화번호 → 계좌·카드 번호. 원문 text에 적용한다.
 * (scheme URL을 먼저 처리해야 userinfo의 @가 이메일로 잡히지 않고, 이메일을 도메인보다 먼저 처리해야
 * me.name@… 의 앞부분이 도메인으로 잡히지 않는다)
 */
public final class PiiMasker {

	private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
	private static final Pattern URL_WITH_SCHEME = Pattern.compile("(?i)\\bhttps?://([^\\s/?#]+)\\S*");
	private static final Pattern BARE_DOMAIN = Pattern.compile(
			"(?<![\\p{L}\\p{N}@.:\\-])((?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}\\-]*[\\p{L}\\p{N}])?\\.)+[A-Za-z]{2,63})"
					+ "(?![\\p{L}\\p{N}\\-])(?:[/?#]\\S*)?");
	// 휴대전화(010…), 지역번호(02, 031…), 국제 형식(+82 10…)
	private static final Pattern PHONE = Pattern.compile(
			"(?<![\\d-])(?:\\+82[-.\\s]?|0)(?:1[016789]|2|[3-6]\\d)[-.\\s]?\\d{3,4}[-.\\s]?\\d{4}(?![\\d-])");
	private static final Pattern NUMBER_GROUPS = Pattern.compile("(?<![\\d-])\\d{2,6}(?:-\\d{2,7}){2,3}(?![\\d-])");
	private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d{10,19}(?!\\d)");
	private static final int MIN_ACCOUNT_DIGITS = 10;

	private PiiMasker() {
	}

	public static String mask(String text) {
		String masked = replaceWithHost(URL_WITH_SCHEME, text);
		masked = EMAIL.matcher(masked).replaceAll("[EMAIL]");
		masked = replaceWithHost(BARE_DOMAIN, masked);
		masked = PHONE.matcher(masked).replaceAll("[PHONE]");
		// 하이픈 묶음은 숫자가 10자리 이상일 때만 (날짜 2026-10-20 등 제외)
		masked = NUMBER_GROUPS.matcher(masked).replaceAll(match -> match.group().replace("-", "").length()
				>= MIN_ACCOUNT_DIGITS ? "[ACCOUNT]" : Matcher.quoteReplacement(match.group()));
		return LONG_NUMBER.matcher(masked).replaceAll("[ACCOUNT]");
	}

	private static String replaceWithHost(Pattern pattern, String text) {
		return pattern.matcher(text).replaceAll(match -> Matcher.quoteReplacement(
				"[URL:" + UrlHosts.normalizeHost(hostOf(match.group(1))) + "]"));
	}

	private static String hostOf(String authority) {
		String host = authority.substring(authority.lastIndexOf('@') + 1);
		int port = host.lastIndexOf(':');
		return port >= 0 ? host.substring(0, port) : host;
	}

}
