package com.lifeguards.liveguard.guard;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.request.RevokeFindingRequest;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.platform.port.LivePlatformPort;

/** A human objection revokes one finding; it never deletes audit or consumed kick history. */
@Service
@Transactional
public class LiveFindingRecovery {
 private final JdbcClient db; private final LiveModerationService cases; private final LivePlatformPort platform;
 private final Clock clock; private final JsonMapper json;
 public LiveFindingRecovery(JdbcClient db,LiveModerationService cases,LivePlatformPort platform,Clock clock,JsonMapper json){
  this.db=db;this.cases=cases;this.platform=platform;this.clock=clock;this.json=json;
 }
 private UUID uuid(Object value){return UUID.fromString(value.toString());}
 private Timestamp now(){return Timestamp.from(clock.instant());}
 private ApiException error(ErrorCode code,String text){return new ApiException(code,text);}
 public Map<String,Object> revoke(UUID finding,RevokeFindingRequest input,AuthenticatedUser operator){
  UUID owner=db.sql("SELECT case_id FROM guard.findings WHERE id=?").param(finding).query(UUID.class).optional()
   .orElseThrow(()->error(ErrorCode.NOT_FOUND,"Finding not found"));
  var c=cases.row(owner,true); UUID live=uuid(c.get("live_id")),user=uuid(c.get("user_id"));
  platform.authorize(live,operator.userId(),true);
  var prior=db.sql("SELECT finding_id,operator_id,request::text,result::text FROM guard.finding_revocations WHERE client_request_id=?")
   .param(input.clientRequestId()).query(new org.springframework.jdbc.core.ColumnMapRowMapper()).optional();
  String request=json.writeValueAsString(input);
  if(prior.isPresent()){
   var old=prior.get();
   if(!finding.equals(old.get("finding_id"))||!operator.userId().equals(old.get("operator_id"))||!json.readTree(request).equals(json.readTree(old.get("request").toString())))
    throw error(ErrorCode.IDEMPOTENCY_CONFLICT,"Revocation request differs");
   return json.readValue(old.get("result").toString(),Map.class);
  }
  if(((Number)c.get("version")).intValue()!=input.expectedCaseVersion())throw error(ErrorCode.STALE_VERSION,"Case version changed");
  int changed=db.sql("UPDATE guard.findings SET status='REVOKED',revoked_at=? WHERE id=? AND source='BACKEND' AND status='CONFIRMED'")
   .params(now(),finding).update();
  if(changed!=1)throw error(ErrorCode.IDEMPOTENCY_CONFLICT,"Only confirmed findings can be revoked");
  db.sql("INSERT INTO guard.finding_events(id,finding_id,event_type,decision_reference,reason,created_at) VALUES(?,?,'REVOKED',?,?,?)")
   .params(UUID.randomUUID(),finding,"operator:"+operator.userId()+":"+input.clientRequestId(),input.reason(),now()).update();
  boolean cancelled=db.sql("SELECT count(*) FROM guard.findings WHERE case_id=? AND source='BACKEND' AND status='CONFIRMED' AND verdict='VIOLATION'")
   .param(owner).query(Integer.class).single()==0;
  db.sql("UPDATE guard.cases SET version=version+1 WHERE id=?").param(owner).update();
  if(cancelled){
   db.sql("UPDATE guard.live_case_state SET confirmation_status='CANCELLED' WHERE case_id=?").param(owner).update();
   db.sql("UPDATE guard.cases SET status='NO_ACTION' WHERE id=?").param(owner).update();
   platform.visibility(owner,"PUBLIC");
   var reviews=db.sql("SELECT id FROM guard.reviews WHERE kind='KICK' AND ?=ANY(basis_case_ids) AND status<>'INVALIDATED'")
    .param(owner).query(UUID.class).list();
   for(UUID review:reviews){
    db.sql("UPDATE guard.reviews SET status='INVALIDATED',version=version+1 WHERE id=?").param(review).update();
    db.sql("UPDATE guard.live_reservations SET state='RELEASED' WHERE review_id=? AND state='RESERVED'").param(review).update();
    db.sql("DELETE FROM guard.live_kicks WHERE review_id=?").param(review).update();
   }
  }
  recomputeTimeouts(live,user);
  cases.bumpTarget(live,user);
  Map<String,Object> result=new LinkedHashMap<>();
  result.put("findingId",finding);result.put("caseId",owner);result.put("liveId",live);result.put("revoked",true);
  result.put("caseVersion",((Number)c.get("version")).intValue()+1);result.put("confirmationStatus",cancelled?"CANCELLED":"CONFIRMED_VIOLATION");
  db.sql("INSERT INTO guard.finding_revocations(client_request_id,finding_id,operator_id,request,result,created_at) VALUES(?,?,?,?::jsonb,?::jsonb,?)")
   .params(input.clientRequestId(),finding,operator.userId(),request,json.writeValueAsString(result),now()).update();
  platform.emit(live,"CASE_UPDATED",cases.caseEvent(owner),null,true);
  return json.readValue(json.writeValueAsString(result),Map.class);
 }
 private void recomputeTimeouts(UUID live,UUID user){
  Map<String,Integer> previous=new HashMap<>();
  var order=db.sql("SELECT id FROM guard.cases WHERE live_id=? AND user_id=? AND judged_at IS NOT NULL ORDER BY judged_at,id")
   .params(live,user).query(UUID.class).list();
  for(UUID c:order){
   var categories=db.sql("SELECT DISTINCT category FROM guard.findings WHERE case_id=? AND source='BACKEND' AND status='CONFIRMED' AND verdict='VIOLATION'")
    .param(c).query(String.class).list();
   int duration=0;
   for(String category:categories){duration=Math.max(duration,TimeoutPolicy.seconds(category,previous.getOrDefault(category,0)));previous.merge(category,1,Integer::sum);}
   if(duration==0)db.sql("UPDATE guard.live_timeouts SET revoked_at=COALESCE(revoked_at,?) WHERE case_id=?").params(now(),c).update();
   else db.sql("UPDATE guard.live_timeouts t SET expires_at=LEAST(t.expires_at,a.created_at+make_interval(secs=>?)) FROM guard.live_actions a WHERE t.action_id=a.id AND t.case_id=? AND t.revoked_at IS NULL")
    .params(duration,c).update();
  }
 }
}
