package com.lifeguards.liveguard.common;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청마다 traceId를 정해 MDC와 응답 헤더 {@code X-Trace-Id}에 싣는다.
 * 들어온 헤더가 UUID 형식이면 이어받고, 아니면 새로 만든다 (로그 주입 방지).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Trace-Id";
	public static final String MDC_KEY = "traceId";

	private static final Pattern UUID_FORMAT = Pattern
			.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	/** 현재 요청 스레드의 traceId. 필터 밖에서 호출되면 null. */
	public static String current() {
		return MDC.get(MDC_KEY);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String traceId = resolve(request.getHeader(HEADER));
		MDC.put(MDC_KEY, traceId);
		response.setHeader(HEADER, traceId);
		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove(MDC_KEY);
		}
	}

	private static String resolve(String inbound) {
		if (inbound != null && UUID_FORMAT.matcher(inbound).matches()) {
			return inbound.toLowerCase();
		}
		return UUID.randomUUID().toString();
	}

}
