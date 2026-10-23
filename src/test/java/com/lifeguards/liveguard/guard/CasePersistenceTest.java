package com.lifeguards.liveguard.guard;

import static com.lifeguards.liveguard.support.LoginSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.contracts.enums.*;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import com.lifeguards.liveguard.guard.FindingService.FindingInput;
import com.lifeguards.liveguard.platform.port.*;
import com.lifeguards.liveguard.support.CaseFixtures;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CasePersistenceTest {
	static final UUID CHANNEL=UUID.fromString("20000000-0000-4000-8000-000000000001");
	static final UUID USER=UUID.fromString("10000000-0000-4000-8000-000000000001");
	static final Instant NOW=Instant.parse("2026-10-21T00:00:00Z");
	@Autowired JdbcTemplate jdbc;
	@Autowired CandidateSink sink;
	@Autowired FindingService findings;
	@Autowired TransactionTemplate tx;
	@Autowired MockMvc mvc;
	@Autowired Clock clock;
	@Autowired LiveModerationService moderation;
	UUID live;
	@BeforeEach void setup(){
		live=UUID.randomUUID();
		jdbc.update("UPDATE platform.lives SET ended_at=? WHERE channel_id=? AND ended_at IS NULL",Timestamp.from(clock.instant()),CHANNEL);
		jdbc.update("INSERT INTO platform.lives(id,channel_id,started_at) VALUES(?,?,?)",live,CHANNEL,Timestamp.from(NOW.minusSeconds(86400)));
	}
	@AfterEach void cleanup(){
		CaseFixtures.deleteCases(jdbc);
		jdbc.update("DELETE FROM platform.messages");
		jdbc.update("DELETE FROM platform.lives");
	}
	UUID message(FilterResult result){
		UUID id=UUID.randomUUID();
		jdbc.update("INSERT INTO platform.messages(id,channel_id,live_id,author_id,client_message_id,text,normalized_text,filter_result,visibility,sent_at) VALUES(?,?,?,?,?,'test','test',?,'HELD',?)",
			id,CHANNEL,live,USER,UUID.randomUUID(),result.name(),Timestamp.from(NOW));
		return id;
	}
	CandidateRequest request(UUID message,FilterResult result,List<UUID> related){
		return new CandidateRequest(CHANNEL,live,USER,message,result,
			result==FilterResult.FLOOD?HandlingRoute.FLOOD_REVIEW:HandlingRoute.CONTEXT_REVIEW,null,
			List.of(result==FilterResult.FLOOD?"REPEATED_TEXT":"ATTACK_TERM"),"norm-test+dict-test","v3.2",NOW,related);
	}
	UUID context(){return sink.open(request(message(FilterResult.HOLD),FilterResult.HOLD,List.of()));}
	FindingInput input(UUID c,List<Category> categories,int version){
		return new FindingInput(c,categories,Verdict.VIOLATION,"v3.2","evidence-fixture",version,"decision-fixture","Validated fixture authority");
	}
	int count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
	@Test void bothHttpEntryPointsUseDurableProvenanceAndReplayOneIncident() throws Exception {
		assertInstanceOf(JdbcCandidateSink.class,sink);
		var viewer=login(mvc,"viewer");
		UUID client=UUID.randomUUID();
		for(String path:List.of("/api/channels/"+CHANNEL+"/messages","/api/lives/"+live+"/messages"))
			mvc.perform(withXsrf(mvc,post(path)).session(viewer).contentType(MediaType.APPLICATION_JSON)
				.content("{\"clientMessageId\":\""+client+"\",\"text\":\"너 꺼져\"}"))
				.andExpect(status().isCreated());
		assertEquals(1,count("guard.cases"));
		assertEquals(1,count("guard.candidates"));
		assertEquals(1,count("guard.live_case_state"));
		assertEquals(live,jdbc.queryForObject("SELECT live_id FROM guard.cases",UUID.class));
		assertEquals("v3.2",jdbc.queryForObject("SELECT policy_version FROM guard.candidates",String.class));
		assertEquals("CONTEXT_REVIEW",jdbc.queryForObject("SELECT handling_route FROM guard.candidates",String.class));
		assertEquals(0,count("guard.findings"));
		assertEquals(1,count("guard.agent_task_outbox"));
	}
	@Test void confirmedFloodBurstReusesOneCaseAndKeepsFirstTwoEvidenceOnly(){
		UUID first=message(FilterResult.PASS),second=message(FilterResult.PASS),third=message(FilterResult.FLOOD);
		jdbc.update("UPDATE platform.messages SET visibility='PUBLIC' WHERE id IN (?,?)",first,second);
		var initial=request(third,FilterResult.FLOOD,List.of(first,second));
		UUID c=sink.open(initial),snapshot=jdbc.queryForObject("SELECT snapshot_id FROM guard.cases",UUID.class);
		UUID fourth=message(FilterResult.FLOOD);
		assertEquals(c,sink.open(request(fourth,FilterResult.FLOOD,List.of(first,second,third))));
		UUID fifth=message(FilterResult.FLOOD);
		assertEquals(c,sink.open(request(fifth,FilterResult.FLOOD,List.of(first,second,third,fourth))));
		assertEquals(1,count("guard.cases"));
		assertEquals(1,count("guard.findings"));
		assertEquals(5,count("guard.case_messages"));
		assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM guard.case_messages WHERE owned AND is_target",Integer.class));
		assertEquals(3,jdbc.queryForObject("SELECT evidence_version FROM guard.cases",Integer.class));
		assertEquals(3,count("guard.case_snapshots"));
		assertNotEquals(snapshot,jdbc.queryForObject("SELECT snapshot_id FROM guard.cases",UUID.class));
		assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM platform.messages WHERE visibility='PUBLIC'",Integer.class));
		assertEquals(c,sink.open(initial));
		assertEquals(3,count("guard.case_snapshots"));
	}
	@Test void actualFloodSendingConfirmsOnlyThirdAndLaterTargets() throws Exception {
		var viewer=login(mvc,"viewer");
		for(int i=0;i<5;i++)
			mvc.perform(withXsrf(mvc,post("/api/lives/"+live+"/messages")).session(viewer).contentType(MediaType.APPLICATION_JSON)
				.content("{\"clientMessageId\":\""+UUID.randomUUID()+"\",\"text\":\"flood test\"}"))
				.andExpect(status().isCreated());
		assertEquals(1,count("guard.cases"));
		assertEquals(1,count("guard.findings"));
		assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM platform.messages WHERE visibility='HIDDEN'",Integer.class));
		assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM platform.messages WHERE visibility='PUBLIC' AND case_id IS NULL",Integer.class));
	}
	@Test void floodSnapshotKeepsRelatedEvidenceAcrossBusyChat() {
		UUID first=message(FilterResult.PASS),second=message(FilterResult.PASS);
		jdbc.update("UPDATE platform.messages SET sent_at=?,visibility='PUBLIC' WHERE id IN (?,?)",
			Timestamp.from(NOW.minusSeconds(2)),first,second);
		for(int i=0;i<15;i++){
			UUID unrelated=message(FilterResult.PASS);
			jdbc.update("UPDATE platform.messages SET sent_at=?,normalized_text='unrelated',visibility='PUBLIC' WHERE id=?",
				Timestamp.from(NOW.minusSeconds(1)),unrelated);
		}
		UUID third=message(FilterResult.FLOOD);
		UUID c=sink.open(request(third,FilterResult.FLOOD,List.of(first,second)));
		var snapshot=moderation.internalCase(c);
		assertTrue(snapshot.messages().stream().map(m->m.messageId()).toList().containsAll(List.of(first,second,third)));
		assertEquals(List.of(third),snapshot.targetMessageIds());
		var context=moderation.internalContext(c,0,0);
		assertTrue(context.messages().stream().map(m->m.messageId()).toList().containsAll(List.of(first,second,third)));
		assertTrue(context.messages().size()<=20);
	}
	@Test void replayRejectsAnyChangedProvenanceWithoutChangingEvidence(){
		UUID m=message(FilterResult.HOLD);
		var original=request(m,FilterResult.HOLD,List.of());
		UUID c=sink.open(original);
		for(var changed:List.of(
			new CandidateRequest(CHANNEL,live,USER,m,FilterResult.HOLD,HandlingRoute.CONTEXT_REVIEW,null,List.of("LINK"),"norm-test+dict-test","v3.2",NOW,List.of()),
			new CandidateRequest(CHANNEL,live,USER,m,FilterResult.HOLD,HandlingRoute.CONTEXT_REVIEW,null,List.of("ATTACK_TERM"),"other-filter","v3.2",NOW,List.of()),
			new CandidateRequest(CHANNEL,live,USER,m,FilterResult.HOLD,HandlingRoute.CONTEXT_REVIEW,null,List.of("ATTACK_TERM"),"norm-test+dict-test","other-policy",NOW,List.of()),
			new CandidateRequest(CHANNEL,live,USER,m,FilterResult.HOLD,HandlingRoute.PII_INVESTIGATION,null,List.of("ATTACK_TERM"),"norm-test+dict-test","v3.2",NOW,List.of()),
			new CandidateRequest(CHANNEL,live,USER,m,FilterResult.HOLD,HandlingRoute.CONTEXT_REVIEW,null,List.of("ATTACK_TERM"),"norm-test+dict-test","v3.2",NOW.plusSeconds(1),List.of()))){
			assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT,assertThrows(ApiException.class,()->sink.open(changed)).code());
		}
		assertEquals(c,sink.open(original));
		assertEquals(1,count("guard.candidates"));
		assertEquals(1,count("guard.case_snapshots"));
	}
	@Test void relatedMessagesRequireSameLiveUserTextAndWindowAndRollbackTheMessage(){
		assertThrows(InvalidContractException.class,()->tx.executeWithoutResult(status->{
			UUID m=message(FilterResult.FLOOD);
			sink.open(request(m,FilterResult.FLOOD,List.of(UUID.randomUUID())));
		}));
		assertEquals(0,count("platform.messages"));
		assertEquals(0,count("guard.cases"));
		assertEquals(0,count("guard.agent_task_outbox"));
		UUID other=message(FilterResult.PASS);
		jdbc.update("UPDATE platform.messages SET sent_at=? WHERE id=?",Timestamp.from(NOW.minusSeconds(10)),other);
		UUID target=message(FilterResult.FLOOD);
		assertThrows(InvalidContractException.class,()->sink.open(request(target,FilterResult.FLOOD,List.of(other))));
	}
	@Test void conflictingRelatedEvidenceCannotCrossLiveAndSameChannelUserDoesNotReuseOldLive(){
		UUID old=message(FilterResult.FLOOD);
		UUID first=sink.open(request(old,FilterResult.FLOOD,List.of()));
		jdbc.update("UPDATE platform.lives SET ended_at=? WHERE id=?",Timestamp.from(NOW),live);
		live=UUID.randomUUID();
		jdbc.update("INSERT INTO platform.lives(id,channel_id,started_at) VALUES(?,?,?)",live,CHANNEL,Timestamp.from(NOW));
		UUID next=message(FilterResult.FLOOD);
		assertThrows(InvalidContractException.class,()->sink.open(request(next,FilterResult.FLOOD,List.of(old))));
		assertNotEquals(first,sink.open(request(next,FilterResult.FLOOD,List.of())));
	}
	@Test void proposalsAndConfirmedCategoriesShareOnePersistenceModel(){
		UUID c=context();
		findings.propose(input(c,List.of(Category.PROFANITY,Category.HARASSMENT),1));
		assertTrue(history().isEmpty());
		findings.confirm(input(c,List.of(Category.PROFANITY,Category.HARASSMENT),1));
		assertEquals(List.of(c),history());
		assertEquals(4,count("guard.findings"));
		assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM guard.findings WHERE confirmed_at IS NOT NULL",Integer.class));
	}
	@Test void revocationRetainsEvidenceAndOnlyRemovesItsOwnFinding(){
		UUID c=context();
		var ids=findings.confirm(input(c,List.of(Category.PROFANITY,Category.HARASSMENT),1));
		findings.revoke(ids.getFirst(),"revoke-one","Incorrect finding");
		assertEquals(List.of(c),history());
		findings.revoke(ids.getLast(),"revoke-two","Incorrect finding");
		assertTrue(history().isEmpty());
		assertEquals(4,count("guard.finding_events"));
		assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM guard.findings WHERE revoked_at IS NOT NULL",Integer.class));
	}
	@Test void staleEvidenceAndBatchConflictsAreAtomic(){
		UUID c=context();
		assertThrows(InvalidContractException.class,()->findings.confirm(input(c,List.of(Category.FLOOD),2)));
		findings.confirm(input(c,List.of(Category.PROFANITY),1));
		assertThrows(DataIntegrityViolationException.class,()->findings.confirm(input(c,List.of(Category.HARASSMENT,Category.PROFANITY),1)));
		assertEquals(1,count("guard.findings"));
		assertEquals(1,count("guard.finding_events"));
	}
	@Test void unknownCaseAndInvalidTargetsAreRejected(){
		UUID m=message(FilterResult.FLOOD);
		assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE platform.messages SET case_id=? WHERE id=?",UUID.randomUUID(),m));
		assertThrows(InvalidContractException.class,()->sink.open(request(m,FilterResult.FLOOD,List.of(m))));
		UUID related=message(FilterResult.PASS);
		assertThrows(InvalidContractException.class,()->sink.open(request(m,FilterResult.FLOOD,List.of(related,related))));
		assertEquals(0,count("guard.cases"));
	}
	List<UUID> history(){return findings.confirmedCaseIds(CHANNEL,USER,clock.instant().minusSeconds(86400),clock.instant().plusSeconds(1));}
}
