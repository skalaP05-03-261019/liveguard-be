package com.lifeguards.liveguard.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.lifeguards.liveguard.TestcontainersConfiguration;

/** 토큰이 설정되지 않은 환경에서는 어떤 값을 보내도 거부한다 (빈 값끼리 일치하지 않는다). */
@SpringBootTest(properties = "liveguard.internal.service-token=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InternalTokenNotConfiguredTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void everyRequestIsRejected() throws Exception {
		for (String token : new String[] { "", "test-service-token-for-tests-only", "anything" }) {
			mockMvc.perform(get("/internal/cases/" + UUID.randomUUID() + "/context")
					.header(ServiceTokenFilter.HEADER, token))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		}
	}

}
