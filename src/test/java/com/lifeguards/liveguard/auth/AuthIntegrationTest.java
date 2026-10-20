package com.lifeguards.liveguard.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.common.TraceIdFilter;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** test 프로파일 시드 계정(viewer, moderator, admin / liveguard-local)으로 세션 로그인을 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthIntegrationTest {

	private static final String PASSWORD = "liveguard-local";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void meWithoutLoginIs401AndIssuesXsrfCookie() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
				.andExpect(jsonPath("$.error.retryable").value(false))
				.andReturn();

		Cookie xsrf = result.getResponse().getCookie("XSRF-TOKEN");
		assertNotNull(xsrf, "401 응답에 XSRF-TOKEN Set-Cookie가 있어야 한다");
		assertFalse(xsrf.getValue().isBlank());
		assertFalse(xsrf.isHttpOnly(), "FE가 읽을 수 있어야 한다");
		String bodyTraceId = JsonPath.read(result.getResponse().getContentAsString(), "$.traceId");
		assertEquals(result.getResponse().getHeader(TraceIdFilter.HEADER), bodyTraceId);
	}

	@Test
	void xsrfCookieFromMeLetsLoginSucceedAndSessionRestoresMe() throws Exception {
		Cookie xsrf = mockMvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");

		MvcResult login = mockMvc.perform(post("/api/auth/login")
				.cookie(xsrf)
				.header("X-XSRF-TOKEN", xsrf.getValue())
				.contentType(MediaType.APPLICATION_JSON)
				.content(credentials("viewer", PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.userId").value("10000000-0000-4000-8000-000000000001"))
				.andExpect(jsonPath("$.data.nickname").value("시청자"))
				.andExpect(jsonPath("$.data.role").value("VIEWER"))
				.andExpect(jsonPath("$.data.channelIds.length()").value(2))
				.andExpect(jsonPath("$.traceId").isNotEmpty())
				.andReturn();

		// 기존 토큰 삭제 쿠키(Max-Age=0)와 별개로, 값이 있는 새 XSRF-TOKEN이 로그인 응답에 있어야 한다
		Cookie rotated = Arrays.stream(login.getResponse().getCookies())
				.filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
				.filter(cookie -> cookie.getMaxAge() != 0 && !cookie.getValue().isEmpty())
				.reduce((first, last) -> last)
				.orElse(null);
		assertNotNull(rotated, "로그인 후 새 CSRF 토큰을 응답에 싣는다");
		assertNotEquals(xsrf.getValue(), rotated.getValue());

		MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
		mockMvc.perform(get("/api/auth/me").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.role").value("VIEWER"));

		// 로그인 응답의 새 토큰만으로(추가 GET 없이) 변경 요청이 통과한다
		mockMvc.perform(post("/api/auth/logout").session(session)
				.cookie(rotated).header("X-XSRF-TOKEN", rotated.getValue()))
				.andExpect(status().isNoContent());
	}

	@Test
	void loginRotatesExistingSessionId() throws Exception {
		MockHttpSession session = new MockHttpSession();
		String before = session.getId();

		mockMvc.perform(login("moderator", PASSWORD).session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.role").value("MODERATOR"));

		assertNotEquals(before, session.getId());
	}

	@Test
	void platformAdminHasNoChannelMemberships() throws Exception {
		mockMvc.perform(login("admin", PASSWORD))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.role").value("PLATFORM_ADMIN"))
				.andExpect(jsonPath("$.data.channelIds.length()").value(0));
	}

	@Test
	void wrongPasswordAndUnknownUserGetTheSame401() throws Exception {
		String wrongPassword = mockMvc.perform(login("viewer", "wrong"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
				.andReturn().getResponse().getContentAsString();
		String unknownUser = mockMvc.perform(login("nobody", PASSWORD))
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();

		String wrongPasswordMessage = JsonPath.read(wrongPassword, "$.error.message");
		String unknownUserMessage = JsonPath.read(unknownUser, "$.error.message");
		assertEquals(wrongPasswordMessage, unknownUserMessage);
	}

	@Test
	void loginWithoutCsrfTokenIs403() throws Exception {
		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(credentials("viewer", PASSWORD)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
	}

	@Test
	void loginBodyWithUnknownFieldIs400() throws Exception {
		mockMvc.perform(withXsrf(post("/api/auth/login"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"viewer\",\"password\":\"x\",\"role\":\"PLATFORM_ADMIN\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void logoutEndsTheSession() throws Exception {
		MockHttpSession session = loggedIn("viewer");

		mockMvc.perform(withXsrf(post("/api/auth/logout")).session(session))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/auth/me").session(session))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void logoutWithoutSessionIsStill204() throws Exception {
		mockMvc.perform(withXsrf(post("/api/auth/logout")))
				.andExpect(status().isNoContent());
	}

	@Test
	void internalApiIsDeniedForBrowsers() throws Exception {
		mockMvc.perform(get("/internal/cases/11111111-1111-4111-8111-111111111111/context"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
		mockMvc.perform(get("/internal/cases/11111111-1111-4111-8111-111111111111/context")
				.session(loggedIn("moderator")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	// /api·인증·Swagger 밖의 경로는 denyAll. 예전 Spring AI MCP 서버 경로(/sse, /mcp)로 확인한다
	@Test
	void pathsOutsideApiAreDenied() throws Exception {
		MockHttpSession session = loggedIn("moderator");
		for (String path : new String[] { "/sse", "/mcp", "/anything" }) {
			mockMvc.perform(get(path).accept(MediaType.TEXT_EVENT_STREAM))
					.andExpect(status().isUnauthorized());
			mockMvc.perform(get(path).accept(MediaType.TEXT_EVENT_STREAM).session(session))
					.andExpect(status().isForbidden());
		}
		mockMvc.perform(withXsrf(post("/mcp/message")).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(withXsrf(post("/mcp/message")).session(session)
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void unknownApiPathIs404ForLoggedInUser() throws Exception {
		mockMvc.perform(get("/api/nothing-here").session(loggedIn("viewer")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	private MockHttpSession loggedIn(String username) throws Exception {
		MvcResult result = mockMvc.perform(login(username, PASSWORD)).andExpect(status().isOk()).andReturn();
		return (MockHttpSession) result.getRequest().getSession(false);
	}

	private MockHttpServletRequestBuilder login(String username, String password) throws Exception {
		return withXsrf(post("/api/auth/login"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(credentials(username, password));
	}

	// spring-security-test의 csrf()는 컨텍스트의 CSRF 저장소를 테스트용으로 바꿔 끼워 이후 요청의 쿠키 발급을 막는다.
	// 실제 FE 흐름대로 GET으로 받은 XSRF-TOKEN 쿠키를 X-XSRF-TOKEN 헤더로 보낸다.
	private MockHttpServletRequestBuilder withXsrf(MockHttpServletRequestBuilder request) throws Exception {
		Cookie xsrf = mockMvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return request.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
	}

	private static String credentials(String username, String password) {
		return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
	}

}
