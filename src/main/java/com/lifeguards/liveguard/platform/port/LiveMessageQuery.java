package com.lifeguards.liveguard.platform.port;

import java.util.List;
import java.util.UUID;

/**
 * 사건·문맥 증거가 가리키는 메시지를 방송 범위 안에서 읽는 포트(이슈 #25 §4). platform이 정의하고 구현하며
 * guard(B2)가 쓴다. guard는 {@code platform.messages}를 직접 읽지 않고 이 포트를 쓴다.
 *
 * <p>
 * 호출 규약
 * <ul>
 * <li>결과는 요청한 id 순서다. 같은 id가 여러 번 있으면 한 번만 돌려준다. 빈 목록은 빈 결과다.</li>
 * <li>요청한 id를 조용히 빼지 않는다. 하나라도 못 돌려주면 전체가 실패한다.</li>
 * <li>본문은 마스킹한 값만 돌려준다. HELD·HIDDEN 메시지도 같다. 호출자는 이 값을 작성자·담당 운영자 외의 브라우저
 * 응답에 내보내지 않는다. 정규화 문장도 원문과 같으므로 해시({@code normalizedTextHash})로만 준다.</li>
 * <li>후보 검증(채널·필터 결과·같은 문장)에 필요한 {@code channelId}·{@code filterResult}·{@code normalizedTextHash}를
 * 함께 준다.</li>
 * <li>{@code visibility}·{@code version}은 지금의 값이다. 사건이 만들어질 때의 값(snapshot)은 B2가 따로 저장한다.
 * 기대한 버전·가시성과 비교해 STALE_VERSION을 내는 것은 {@code LivePlatformPort.verifyTargets}의 몫이다.</li>
 * </ul>
 *
 * <p>
 * 실패
 * <ul>
 * <li>{@code VALIDATION_FAILED}: 요청한 id 중에 다른 방송의 메시지(방송 도입 전 메시지 포함)가 있다.</li>
 * <li>{@code NOT_FOUND}: 존재하지 않는 id가 있다. 두 경우가 함께 있으면 VALIDATION_FAILED다.</li>
 * </ul>
 */
public interface LiveMessageQuery {

	List<MessageView> messages(UUID liveId, List<UUID> messageIds);

}
