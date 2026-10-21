package com.lifeguards.liveguard.filter;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.lifeguards.liveguard.contracts.enums.MatchingTag;

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

	/**
	 * 개인정보 후보 신호(TBD-02 3순위). 전화번호·계좌·이메일 "형식"만 본다. 하이픈 없이 이어진 숫자 나열은 마스킹은 하지만
	 * 후보로 보지 않는다(AC-06). 마스킹과 같은 순서·패턴을 쓰고, 전각 숫자 우회를 막으려 NFKC만 적용한 원문에서 찾는다.
	 * scheme 있는 URL 안의 문자열은 링크로 따로 다루므로 보지 않는다. 결과는 PII_PHONE, PII_ACCOUNT, PII_EMAIL 순서다.
	 */
	public static List<MatchingTag> candidates(String text) {
		String rest = URL_WITH_SCHEME.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll(" ");
		boolean email = EMAIL.matcher(rest).find();
		rest = EMAIL.matcher(rest).replaceAll(" ");
		boolean phone = PHONE.matcher(rest).find();
		rest = PHONE.matcher(rest).replaceAll(" ");
		boolean account = NUMBER_GROUPS.matcher(rest).results()
				.anyMatch(match -> match.group().replace("-", "").length() >= MIN_ACCOUNT_DIGITS);

		List<MatchingTag> tags = new ArrayList<>(3);
		if (phone) {
			tags.add(MatchingTag.PII_PHONE);
		}
		if (account) {
			tags.add(MatchingTag.PII_ACCOUNT);
		}
		if (email) {
			tags.add(MatchingTag.PII_EMAIL);
		}
		return List.copyOf(tags);
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
