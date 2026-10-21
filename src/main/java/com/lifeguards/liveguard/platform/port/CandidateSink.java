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
 * <b>FLOOD 사건 묶기 규칙 (확정, BE1 이슈 #15)</b>
 * <ul>
 * <li>{@code relatedMessageIds} 중 <b>판정 전(ANALYZING) 사건</b>에 연결된 메시지가 있으면 그 caseId를 재사용하고,
 * 없으면 새 사건을 만든다. 따라서 도배 3→4→5번째는 같은 사건이고 누적 위반은 1회다(메시지가 아니라 사건 단위,
 * docs/erd.md 6절 1번). 연결 여부는 B2가 자기 사건↔메시지 연결로 판단한다(platform의 messages는 직접 읽지
 * 않는다). 여러 사건에 걸쳐 있으면 가장 최근 메시지에 연결된 사건을 쓴다. 연결된 사건이 이미 판정됐으면 새 사건이다.</li>
 * <li>{@code relatedMessageIds}에는 이미 사건에 연결된 메시지가 있을 수 있다. platform은 돌려받은 caseId를 현재
 * 메시지와, {@code case_id IS NULL}인 관련 메시지에만 기록한다(이미 연결된 것은 바꾸지 않는다).</li>
 * <li>10초 창은 같은 채널·같은 작성자·같은 정규화 문장({@code normalized_text}) 메시지 중
 * {@code sent_at > (현재 sentAt - 10초)}인 것이다. <b>하한은 제외(exclusive)</b>라 정확히 10초 전 메시지는
 * 들어가지 않고 10초에서 1마이크로초 모자란 것은 들어간다. 정밀도는 마이크로초다. visibility와 filter_result는
 * 가리지 않고, 아직 저장 전인 현재 메시지는 제외한다. 현재 메시지를 포함해 3개 이상이면 FLOOD다. 현재 쿼리에는
 * 상한이 없어서 같은 사용자의 동시 요청에서 {@code sentAt} 순서와 락 순서가 뒤집히면 더 늦은 메시지가 들어올 수
 * 있다(락 뒤에서 {@code sentAt}을 정하도록 제출 후 개선 예정).</li>
 * <li>재전송(같은 clientMessageId)은 이 메서드를 부르지 않고 도배 횟수에도 들어가지 않는다.</li>
 * <li>확실한 욕설·피싱 HOLD는 FLOOD보다 우선하므로 같은 욕설을 반복해도 FLOOD가 아니라 HOLD가 여러 건이고
 * {@code relatedMessageIds}는 빈 목록이다. 이 사건들을 묶을지는 구현이 정한다.</li>
 * </ul>
 */
public interface CandidateSink {

	/** @return 이 메시지가 속한 사건의 id */
	UUID open(CandidateRequest request);

}
