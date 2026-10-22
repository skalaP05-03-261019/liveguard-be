package com.lifeguards.liveguard.guard;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.contracts.enums.*;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import com.lifeguards.liveguard.guard.FindingService.FindingInput;
import com.lifeguards.liveguard.platform.port.*;

@SpringBootTest(properties="liveguard.internal.service-token=query-test-token")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class,CaseQueryTest.FixedClock.class})
@Transactional
class CaseQueryTest {
	static final Instant NOW=Instant.parse("2026-10-21T02:00:00Z");
	static final UUID CHANNEL=UUID.fromString("20000000-0000-4000-8000-000000000001");
	static final UUID OTHER_CHANNEL=UUID.fromString("20000000-0000-4000-8000-000000000002");
	static final UUID USER=UUID.fromString("10000000-0000-4000-8000-000000000001");
	static final UUID OTHER_USER=UUID.fromString("10000000-0000-4000-8000-000000000002");
	@TestConfiguration static class FixedClock {
		@Bean @Primary Clock queryClock(){return Clock.fixed(NOW,ZoneOffset.UTC);}
	}
	@Autowired JdbcTemplate jdbc;
	@Autowired CaseQueryService queries;
	@Autowired LiveModerationService moderation;
	@Autowired FindingService findings;
	@Autowired CandidateSink sink;
	@Autowired MockMvc mvc;
	UUID live,current;
	@BeforeEach void setup(){
		live=broadcast(CHANNEL);
		current=create(live,USER,NOW);
	}
	UUID broadcast(UUID channel){
		jdbc.update("UPDATE platform.lives SET ended_at=? WHERE channel_id=? AND ended_at IS NULL",Timestamp.from(NOW),channel);
		UUID id=UUID.randomUUID();
		jdbc.update("INSERT INTO platform.lives(id,channel_id,started_at) VALUES(?,?,?)",id,channel,Timestamp.from(NOW.minusSeconds(7*86400)));
		return id;
	}
	UUID create(UUID live,UUID user,Instant at){
		UUID channel=jdbc.queryForObject("SELECT channel_id FROM platform.lives WHERE id=?",UUID.class,live);
		UUID message=UUID.randomUUID();
		jdbc.update("INSERT INTO platform.messages(id,channel_id,live_id,author_id,client_message_id,text,normalized_text,filter_result,visibility,sent_at) VALUES(?,?,?,?,?,'꺼져 010-1234-5678 person@example.com','test','HOLD','HELD',?)",
			message,channel,live,user,UUID.randomUUID(),Timestamp.from(at));
		return sink.open(new CandidateRequest(channel,live,user,message,FilterResult.HOLD,HandlingRoute.CONTEXT_REVIEW,null,
			List.of("ATTACK_TERM"),"norm-test+dict-test","v3.1",at,List.of()));
	}
	List<UUID> confirm(UUID c,Category... categories){
		return findings.confirm(new FindingInput(c,List.of(categories),Verdict.VIOLATION,"v3.1","evidence-reference",1,"decision-reference","Validated fixture authority"));
	}
	@Test void readsPersistedEvidenceTargetsFindingsAndAudit(){
		confirm(current,Category.PROFANITY,Category.HARASSMENT);
		var details=queries.details(current);
		assertEquals(live,details.metadata().liveId());
		assertEquals("v3.1",details.metadata().policyVersion());
		assertEquals(HandlingRoute.CONTEXT_REVIEW,details.metadata().handlingRoute());
		assertEquals("norm-test+dict-test",details.candidates().getFirst().filterVersion());
		assertTrue(details.messages().getFirst().target());
		assertEquals(2,details.findings().size());
		assertEquals(2,details.audit().size());
	}
	@Test void immutableMaskedSnapshotAndCurrentTargetVersionsAreSeparate() throws Exception {
		var initial=queries.moderationCase(current);
		UUID target=initial.targetMessageIds().getFirst();
		jdbc.update("UPDATE platform.messages SET text='changed original',visibility='HIDDEN',version=3 WHERE id=?",target);
		var later=queries.moderationCase(current);
		assertEquals(initial.messages(),later.messages());
		assertEquals(initial.snapshotId(),later.snapshotId());
		assertFalse(later.messages().toString().contains("010-1234-5678"));
		assertFalse(later.messages().toString().contains("person@example.com"));
		assertEquals(3,later.targets().getFirst().version());
		assertEquals(Visibility.HIDDEN,later.targets().getFirst().visibility());
		mvc.perform(get("/internal/cases/{id}",current).header("X-Service-Token","query-test-token"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.targetVersions['"+target+"']").value(3));
		mvc.perform(get("/internal/cases/{id}/context",current).param("before","0").param("after","0").header("X-Service-Token","query-test-token"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.snapshotId").value(initial.snapshotId().toString()))
			.andExpect(jsonPath("$.data.evidenceVersion").value(initial.evidenceVersion()))
			.andExpect(jsonPath("$.data.messages.length()").value(1));
	}
	@Test void legacyReadinessIsExplicitAndNeverSynthesizesBroadcastOrSnapshot() throws Exception {
		UUID legacy=UUID.randomUUID();
		com.lifeguards.liveguard.support.CaseFixtures.insertCase(jdbc,legacy,CHANNEL,USER);
		mvc.perform(get("/internal/cases/{id}",legacy).header("X-Service-Token","query-test-token"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CASE_NOT_READY"));
		mvc.perform(get("/internal/cases/{id}/context",legacy).header("X-Service-Token","query-test-token"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CASE_NOT_READY"));
		assertEquals(ErrorCode.CASE_NOT_READY,assertThrows(ApiException.class,()->queries.userHistory(legacy,null)).code());
		mvc.perform(get("/internal/cases/{id}",UUID.randomUUID()).header("X-Service-Token","query-test-token"))
			.andExpect(status().isNotFound());
	}
	@Test void missingCurrentEvidenceOrTargetsCannotProduceSuccess(){
		jdbc.update("UPDATE guard.cases SET snapshot_id=? WHERE id=?",UUID.randomUUID(),current);
		assertEquals(ErrorCode.CASE_NOT_READY,assertThrows(ApiException.class,()->queries.moderationCase(current)).code());
		UUID noTargets=create(live,USER,NOW);
		jdbc.update("UPDATE guard.case_messages SET is_target=false WHERE case_id=?",noTargets);
		assertEquals(ErrorCode.CASE_NOT_READY,assertThrows(ApiException.class,()->queries.moderationCase(noTargets)).code());
	}
	@Test void historyUsesWholeLiveConfirmedTimeAndExcludesCurrentAndOtherUsers(){
		UUID old=create(live,USER,NOW.minusSeconds(6*86400));
		confirm(old,Category.values());
		confirm(current,Category.FLOOD);
		confirm(create(live,OTHER_USER,NOW),Category.HARASSMENT);
		UUID otherLive=broadcast(OTHER_CHANNEL);
		confirm(create(otherLive,USER,NOW),Category.FLOOD);
		var history=queries.userHistory(current,null);
		assertEquals(1,history.violationCount());
		assertEquals(5,history.recentCases().size());
		assertTrue(history.recentCases().stream().allMatch(r->r.caseId().equals(old)&&r.confirmedAt().equals(NOW)));
		assertEquals(history,moderation.userHistory(current));
	}
	@Test void proposalNormalReviewAndPartialRevocationDoNotFabricateHistory(){
		UUID past=create(live,USER,NOW.minusSeconds(20));
		var ids=confirm(past,Category.PROFANITY,Category.HARASSMENT);
		findings.revoke(ids.getFirst(),"revoke","Incorrect finding");
		assertEquals(List.of(Category.HARASSMENT),queries.userHistory(current,null).recentCases().stream().map(r->r.category()).toList());
		assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM guard.findings WHERE id=?",Timestamp.class,ids.getFirst()));
		findings.revoke(ids.getLast(),"revoke-last","Incorrect finding");
		findings.propose(new FindingInput(current,List.of(Category.FLOOD),Verdict.VIOLATION,"v3.1","evidence",1,"proposal","AI proposal only"));
		assertEquals(0,queries.userHistory(current,null).violationCount());
	}
	@Test void kickWindowUsesExclusiveConfirmedLowerBoundNotOccurrence(){
		UUID boundary=create(live,USER,NOW.minusSeconds(1));
		UUID oldOccurrence=create(live,USER,NOW.minusSeconds(6*86400));
		UUID futureConfirmation=create(live,USER,NOW);
		for(UUID c:List.of(boundary,oldOccurrence,futureConfirmation,current))confirm(c,Category.FLOOD);
		jdbc.update("UPDATE guard.findings SET confirmed_at=? WHERE case_id=?",Timestamp.from(NOW.minusSeconds(86400)),boundary);
		jdbc.update("UPDATE guard.findings SET confirmed_at=? WHERE case_id=?",Timestamp.from(NOW.plusNanos(1000)),futureConfirmation);
		var eligible=queries.kickEligibility(current);
		assertEquals(2,eligible.count());
		assertTrue(eligible.eligibleCaseIds().containsAll(List.of(current,oldOccurrence)));
	}
	@Test void reservationsAndConsumptionExcludeNewEligibilityButNotTimeoutHistory(){
		UUID past=create(live,USER,NOW.minusSeconds(10));
		confirm(past,Category.values());
		UUID review=UUID.randomUUID();
		jdbc.update("INSERT INTO guard.reviews(id,case_id,kind,basis_case_ids,basis_snapshot_id,created_at) VALUES(?,?,'KICK',ARRAY[?]::uuid[],?,?)",
			review,current,past,UUID.randomUUID(),Timestamp.from(NOW));
		for(String state:List.of("RESERVED","CONSUMED")){
			jdbc.update("INSERT INTO guard.live_reservations(case_id,review_id,state) VALUES(?,?,?) ON CONFLICT(case_id) DO UPDATE SET state=EXCLUDED.state",past,review,state);
			assertEquals(0,queries.kickEligibility(current).count());
			assertEquals(1,queries.userHistory(current,null).violationCount());
		}
		jdbc.update("UPDATE guard.live_reservations SET state='RELEASED' WHERE case_id=?",past);
		assertEquals(1,queries.kickEligibility(current).count());
	}
	@Test void daysIsRejectedAndReadOnlyEligibilityIsImplemented() throws Exception {
		mvc.perform(get("/internal/cases/{id}/user-history",current).header("X-Service-Token","query-test-token"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.windowHours").doesNotExist());
		for(String days:List.of("1","31","0","-1","2147483647","bad"))
			mvc.perform(get("/internal/cases/{id}/user-history",current).param("days",days).header("X-Service-Token","query-test-token"))
				.andExpect(status().isBadRequest());
		// 레거시 별칭 /history는 삭제했다(A-12)
		mvc.perform(get("/internal/cases/{id}/history",current).header("X-Service-Token","query-test-token"))
			.andExpect(status().isNotFound());
		mvc.perform(get("/internal/cases/{id}/kick-eligibility",current).header("X-Service-Token","query-test-token"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(0));
		assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM guard.live_reservations",Integer.class));
		assertThrows(InvalidContractException.class,()->queries.timeoutHistory(current,NOW,NOW));
	}
}
