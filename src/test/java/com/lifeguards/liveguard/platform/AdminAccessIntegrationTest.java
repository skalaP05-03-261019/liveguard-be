package com.lifeguards.liveguard.platform;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.request.SendMessageRequest;
import com.lifeguards.liveguard.contracts.enums.Role;
import com.lifeguards.liveguard.guard.LiveModerationService;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AdminAccessIntegrationTest {
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate db;
	@Autowired LiveService lives;
	@Autowired LiveModerationService guard;
	UUID channel, live, caseId, message;
	User admin, moderator, viewer, outsider;

	record User(UUID userId, String nickname, Role role) implements AuthenticatedUser {}

	@BeforeEach
	void setup() {
		channel = UUID.randomUUID();
		db.update("INSERT INTO platform.channels(id,name) VALUES(?,'admin monitor test')", channel);
		admin = create(Role.PLATFORM_ADMIN, null);
		moderator = create(Role.MODERATOR, "MODERATOR");
		viewer = create(Role.VIEWER, "VIEWER");
		outsider = create(Role.VIEWER, null);
		live = lives.start(channel, moderator).liveId();
		var sent = lives.send(live, viewer, new SendMessageRequest(UUID.randomUUID(), "시발 010-1234-5678"));
		message = (UUID) sent.get("messageId");
		caseId = db.queryForObject("SELECT case_id FROM platform.messages WHERE id=?", UUID.class, message);
	}

	User create(Role role, String membership) {
		UUID id = UUID.randomUUID();
		db.update("INSERT INTO platform.users(id,username,password_hash,nickname,role) VALUES(?,?,'unused','test',?)",
				id,id.toString(),role.name());
		if(membership != null) db.update("INSERT INTO platform.channel_members(channel_id,user_id,member_role) VALUES(?,?,?)",
				channel,id,membership);
		return new User(id,"test",role);
	}

	RequestPostProcessor as(User user) {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(user,"unused",List.of()));
	}

	@Test
	void nonMemberAdminCanReadChannelsCurrentCasesReviewsAndMaskedHistory() throws Exception {
		mvc.perform(get("/api/channels").with(as(admin))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data[?(@.channelId=='"+channel+"')].name").value("admin monitor test"));
		mvc.perform(get("/api/channels/"+channel+"/lives/current").with(as(admin))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.liveId").value(live.toString()));
		mvc.perform(get("/api/lives/"+live+"/messages").with(as(admin))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].text").value("시발 [PHONE]"))
				.andExpect(jsonPath("$.data.items[0].visibility").value("HIDDEN"));
		mvc.perform(get("/api/cases?liveId="+live).with(as(admin))).andExpect(status().isOk());
		mvc.perform(get("/api/cases/"+caseId).with(as(admin))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.messages[0].text").value("시발 [PHONE]"));
		mvc.perform(get("/api/reviews?liveId="+live+"&kind=KICK").with(as(admin))).andExpect(status().isOk());
		mvc.perform(get("/api/cases/"+caseId).with(as(outsider))).andExpect(status().isForbidden());
		mvc.perform(get("/api/lives/"+live+"/messages").with(as(viewer))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].text").value("시발 010-1234-5678"));
	}

	@Test
	void administratorCannotSendManageOrDecideEvenWithModeratorMembership() throws Exception {
		db.update("INSERT INTO platform.channel_members(channel_id,user_id,member_role) VALUES(?,?,'MODERATOR')",
				channel,admin.userId());
		for(String path:List.of("/api/lives/"+live+"/messages","/api/channels/"+channel+"/messages"))
			mvc.perform(post(path).with(as(admin)).with(csrf()).contentType("application/json")
					.content("{\"clientMessageId\":\""+UUID.randomUUID()+"\",\"text\":\"hello\"}"))
					.andExpect(status().isForbidden());
		mvc.perform(post("/api/channels/"+channel+"/lives").with(as(admin)).with(csrf()))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/lives/"+live+"/end").with(as(admin)).with(csrf()))
				.andExpect(status().isForbidden());
		assertThrows(com.lifeguards.liveguard.common.ApiException.class,
				()->lives.authorize(live,admin.userId(),com.lifeguards.liveguard.platform.port.LiveAccess.DECIDE));
		UUID review = contextReview();
		mvc.perform(post("/api/context-reviews/"+review+"/decision").with(as(admin)).with(csrf())
				.contentType("application/json").content("{\"clientRequestId\":\""+UUID.randomUUID()
						+"\",\"expectedReviewVersion\":1,\"decision\":\"CONFIRM_NORMAL\"}"))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/channels/"+channel+"/messages").with(as(admin))).andExpect(status().isForbidden());
		mvc.perform(get("/api/guard/events?channelId="+channel).with(as(admin))).andExpect(status().isForbidden());
		mvc.perform(get("/api/channels/"+channel+"/events").with(as(admin))).andExpect(status().isForbidden());
		mvc.perform(get("/internal/cases/"+caseId+"/evidence").with(as(admin))).andExpect(status().isForbidden());
	}

	@Test
	void channelDiscoveryPreservesMembershipAndReturnsNullWithoutActiveLive() throws Exception {
		mvc.perform(get("/api/channels").with(as(outsider))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(0));
		mvc.perform(get("/api/channels").with(as(viewer))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].live.liveId").value(live.toString()));
		lives.end(live,moderator);
		mvc.perform(get("/api/channels").with(as(viewer))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].live").value(org.hamcrest.Matchers.nullValue()));
	}

	UUID contextReview() {
		var sent = lives.send(live, viewer, new SendMessageRequest(UUID.randomUUID(), "너 미쳤냐"));
		UUID context = db.queryForObject("SELECT case_id FROM platform.messages WHERE id=?",UUID.class,sent.get("messageId"));
		var snapshot = guard.snapshot(context);
		Map<String,Object> request = new java.util.LinkedHashMap<>();
		for(String key:List.of("caseId","liveId","handlingRoute","snapshotId","evidenceVersion","policyVersion"))
			request.put(key,snapshot.get(key));
		request.put("messageFindings",List.of(Map.of("messageId",sent.get("messageId"),"verdict","NO_VIOLATION",
				"categories",List.of(),"policyIds",List.of(),"reason","normal","missingFacts",List.of())));
		return UUID.fromString(guard.judgment(context,request).get("reviewId").toString());
	}

	@Test
	void adminCanRevokeWithActorAuditAndIdempotentReplayButViewerCannot() throws Exception {
		UUID finding = db.queryForObject("SELECT id FROM guard.findings WHERE case_id=? AND source='BACKEND'",
				UUID.class,caseId);
		int version = db.queryForObject("SELECT version FROM guard.cases WHERE id=?",Integer.class,caseId);
		String body = "{\"clientRequestId\":\""+UUID.randomUUID()+"\",\"expectedCaseVersion\":"+version+",\"reason\":\"오판\"}";
		mvc.perform(post("/api/findings/"+finding+"/revoke").with(as(viewer)).with(csrf())
				.contentType("application/json").content(body)).andExpect(status().isForbidden());
		for(int i=0;i<2;i++) mvc.perform(post("/api/findings/"+finding+"/revoke").with(as(admin)).with(csrf())
				.contentType("application/json").content(body)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.revoked").value(true));
		assertEquals(admin.userId(),db.queryForObject("SELECT operator_id FROM guard.finding_revocations WHERE finding_id=?",
				UUID.class,finding));
	}
}
