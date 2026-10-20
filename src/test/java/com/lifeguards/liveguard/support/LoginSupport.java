package com.lifeguards.liveguard.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import jakarta.servlet.http.Cookie;

/** 시드 계정으로 실제 로그인 흐름(XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더)을 거치는 테스트 도우미. */
public final class LoginSupport {

	public static final String PASSWORD = "liveguard-local";

	private LoginSupport() {
	}

	public static MockHttpSession login(MockMvc mockMvc, String username) throws Exception {
		MvcResult result = mockMvc.perform(withXsrf(mockMvc, post("/api/auth/login"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk())
				.andReturn();
		return (MockHttpSession) result.getRequest().getSession(false);
	}

	/** GET으로 받은 XSRF-TOKEN 쿠키를 요청에 쿠키와 헤더로 실어 준다. */
	public static MockHttpServletRequestBuilder withXsrf(MockMvc mockMvc, MockHttpServletRequestBuilder request)
			throws Exception {
		Cookie xsrf = mockMvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return request.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
	}

}
