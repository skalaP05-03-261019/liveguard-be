package com.lifeguards.liveguard.internal;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import com.lifeguards.liveguard.common.ErrorResponses;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * /internal/** 전용 체인 (Agent·business-mcp가 호출). 세션·CSRF 없이 X-Service-Token만 본다.
 * 브라우저 세션 쿠키는 읽지 않으므로 로그인한 사용자도 토큰 없이는 403이다.
 */
@Configuration(proxyBeanMethods = false)
class InternalSecurityConfig {

	@Bean
	@Order(1)
	SecurityFilterChain internalSecurityFilterChain(HttpSecurity http,
			@Value("${liveguard.internal.service-token:}") String serviceToken, JsonMapper jsonMapper) throws Exception {
		http
				.securityMatcher("/internal/**")
				.authorizeHttpRequests(auth -> auth
						.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
						.anyRequest().hasRole("SERVICE"))
				.addFilterAfter(new ServiceTokenFilter(serviceToken), SecurityContextHolderFilter.class)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
				.csrf(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				// 토큰이 없거나 틀리면 모두 403 (b1-tasks 10번 완료 기준)
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint((request, response, ex) -> forbidden(response, jsonMapper))
						.accessDeniedHandler((request, response, ex) -> forbidden(response, jsonMapper)));
		return http.build();
	}

	private static void forbidden(HttpServletResponse response, JsonMapper jsonMapper) throws IOException {
		response.setStatus(HttpStatus.FORBIDDEN.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		jsonMapper.writeValue(response.getOutputStream(),
				ErrorResponses.of(ErrorCode.FORBIDDEN, "Valid service token required"));
	}

}
