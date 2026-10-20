package com.lifeguards.liveguard.auth;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import jakarta.servlet.DispatcherType;

/**
 * 세션 쿠키 + CookieCsrfTokenRepository(XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더).
 * FE는 Vite 프록시로 같은 출처에서 붙으므로 CORS는 설정하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityErrorHandler errorHandler,
			CsrfTokenRepository csrfTokenRepository, SecurityContextRepository securityContextRepository)
			throws Exception {
		http
				.authorizeHttpRequests(auth -> auth
						// SSE 완료·오류 시 비동기 디스패치는 최초 요청에서 이미 인가됐다
						.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/logout").permitAll()
						.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
						// 10번에서 서비스 토큰 필터로 교체한다
						.requestMatchers("/internal/**").denyAll()
						.requestMatchers("/api/**").authenticated()
						// 그 외(Spring AI MCP 서버의 /sse, /mcp/message 포함)는 막는다
						.anyRequest().denyAll())
				.csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
				.securityContext(context -> context.securityContextRepository(securityContextRepository))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(errorHandler)
						.accessDeniedHandler(errorHandler))
				.logout(logout -> logout
						.logoutUrl("/api/auth/logout")
						.deleteCookies("JSESSIONID")
						.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
				// 401마다 세션을 만들지 않도록 요청 캐시를 끈다
				.requestCache(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable);
		return http.build();
	}

	@Bean
	CsrfTokenRepository csrfTokenRepository() {
		return CookieCsrfTokenRepository.withHttpOnlyFalse();
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	/** 로그인 성공 시 세션 ID를 회전하고 CSRF 토큰을 새로 발급한다. */
	@Bean
	SessionAuthenticationStrategy loginSessionStrategy(CsrfTokenRepository csrfTokenRepository) {
		return new CompositeSessionAuthenticationStrategy(List.of(
				new ChangeSessionIdAuthenticationStrategy(),
				new CsrfAuthenticationStrategy(csrfTokenRepository)));
	}

	/** 로그아웃·만료로 세션이 사라지면 HttpSessionDestroyedEvent를 발행한다 (채널 SSE 종료에 사용). */
	@Bean
	HttpSessionEventPublisher httpSessionEventPublisher() {
		return new HttpSessionEventPublisher();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
		provider.setPasswordEncoder(passwordEncoder);
		return new ProviderManager(provider);
	}

}
