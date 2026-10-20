package com.lifeguards.liveguard.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * X-Service-Token이 설정된 토큰과 같으면 ROLE_SERVICE로 인증한다. 두 값을 SHA-256으로 같은 길이로 만든 뒤
 * {@link MessageDigest#isEqual}로 상수 시간 비교한다. 토큰이 설정되지 않았으면 아무 요청도 인증하지 않는다.
 * 토큰 값은 로그에 남기지 않는다.
 */
class ServiceTokenFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Service-Token";

	private static final Logger log = LoggerFactory.getLogger(ServiceTokenFilter.class);

	private final byte[] expectedDigest;
	private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

	ServiceTokenFilter(String serviceToken) {
		if (serviceToken == null || serviceToken.isBlank()) {
			log.warn("liveguard.internal.service-token is not set; all /internal/** requests will be rejected");
			this.expectedDigest = null;
		} else {
			this.expectedDigest = sha256(serviceToken);
		}
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String presented = request.getHeader(HEADER);
		if (presented != null && expectedDigest != null && MessageDigest.isEqual(expectedDigest, sha256(presented))) {
			SecurityContext context = contextHolder.createEmptyContext();
			context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("agent", null,
					List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));
			contextHolder.setContext(context);
		} else if (presented != null) {
			log.warn("Rejected service token for {} {} from {}", request.getMethod(), request.getRequestURI(),
					request.getRemoteAddr());
		}
		chain.doFilter(request, response);
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
