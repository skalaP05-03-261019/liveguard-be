package com.lifeguards.liveguard.auth;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.common.TraceIdFilter;
import com.lifeguards.liveguard.contracts.dto.request.LoginRequest;
import com.lifeguards.liveguard.contracts.dto.response.ApiResponse;
import com.lifeguards.liveguard.contracts.dto.response.Me;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.platform.ChannelMembershipQuery;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 로그아웃(POST /api/auth/logout)은 SecurityConfig의 LogoutFilter가 처리한다. */
@RestController
@RequestMapping("/api/auth")
class AuthController {

	private final AuthenticationManager authenticationManager;
	private final SessionAuthenticationStrategy loginSessionStrategy;
	private final SecurityContextRepository securityContextRepository;
	private final ChannelMembershipQuery channelMemberships;
	private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

	AuthController(AuthenticationManager authenticationManager, SessionAuthenticationStrategy loginSessionStrategy,
			SecurityContextRepository securityContextRepository, ChannelMembershipQuery channelMemberships) {
		this.authenticationManager = authenticationManager;
		this.loginSessionStrategy = loginSessionStrategy;
		this.securityContextRepository = securityContextRepository;
		this.channelMemberships = channelMemberships;
	}

	@PostMapping("/login")
	ApiResponse<Me> login(@RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
		Authentication authentication;
		try {
			authentication = authenticationManager.authenticate(
					UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
		} catch (AuthenticationException ex) {
			// 계정 존재 여부가 드러나지 않도록 실패 사유를 구분하지 않는다
			throw new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid username or password");
		}
		loginSessionStrategy.onAuthentication(authentication, request, response);
		// 회전된 CSRF 토큰은 지연 생성되므로 여기서 읽어 새 XSRF-TOKEN 쿠키를 로그인 응답에 싣는다.
		// 그러지 않으면 로그인 직후 다른 GET 없이 보낸 첫 POST가 403이 된다
		CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
		if (csrfToken != null) {
			csrfToken.getToken();
		}
		SecurityContext context = contextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		contextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
		return ok((AuthUser) authentication.getPrincipal());
	}

	@GetMapping("/me")
	ApiResponse<Me> me(@AuthenticationPrincipal AuthUser user) {
		return ok(user);
	}

	private ApiResponse<Me> ok(AuthUser user) {
		Me me = new Me(user.userId(), user.nickname(), user.role(), channelMemberships.channelIdsOf(user.userId()));
		return new ApiResponse<>(me, TraceIdFilter.current());
	}

}
