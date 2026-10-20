package com.lifeguards.liveguard.platform.port;

import java.util.UUID;

/**
 * 1차 필터가 PASS가 아닌 메시지를 사건(case)으로 넘기는 포트. platform이 정의하고 guard(B2)가 구현한다.
 * B2 구현이 없으면 {@link InMemoryCandidateSink}가 쓰인다. B2는 구현을 {@code @Component}/{@code @Service}로
 * 등록하면 되고, 임시 구현은 {@code @ConditionalOnMissingBean}이라 별도 설정 없이 빠진다.
 *
 * <p>
 * 호출 규약
 * <ul>
 * <li>메시지 저장과 같은 트랜잭션 안에서 호출된다. 구현은 그 트랜잭션에 참여(REQUIRED)해야 하며, 예외를 던지면
 * 메시지 저장이 롤백되고 작성자는 503 TEMPORARY_UNAVAILABLE을 받는다(같은 clientMessageId로 재시도해도 안전).</li>
 * <li>같은 사용자·채널의 호출은 순서대로 들어온다(platform이 사용자·채널 단위로 직렬화한다).</li>
 * <li>같은 clientMessageId 재전송에서는 다시 호출되지 않는다.</li>
 * <li>반환한 caseId는 platform이 {@code messages.case_id}에 기록한다. FLOOD이면 {@code relatedMessageIds}
 * 메시지 중 {@code case_id}가 아직 비어 있는 것에도 기록한다(이미 다른 사건에 연결된 메시지와 visibility는
 * 바꾸지 않는다).</li>
 * <li>메시지 원문은 넘기지 않는다. 필요하면 내부 context API로 읽는다.</li>
 * </ul>
 *
 * <p>
 * <b>사건 묶음 규칙은 B2 {@code open()}의 몫이다.</b> 예를 들어 FLOOD의 네 번째·다섯 번째 메시지를 앞서 만든
 * 도배 사건에 묶을지 새 사건으로 만들지는 구현이 정한다(묶으면 같은 caseId, 아니면 새 caseId를 돌려준다). platform은
 * 돌려받은 값을 그대로 기록할 뿐이며, 네 번째 이후에는 {@code relatedMessageIds}에 이미 사건에 연결된 메시지가
 * 포함될 수 있다(그 연결은 바뀌지 않는다).
 */
public interface CandidateSink {

	/** @return 이 메시지가 속한 사건의 id */
	UUID open(CandidateRequest request);

}
