package com.lifeguards.liveguard.platform.port;

import java.util.UUID;

/**
 * 사건에 연결된 메시지(messages.case_id)의 공개 범위를 바꾸는 포트. platform이 정의하고 guard(B2)가 부른다.
 * messages 테이블은 platform 소유라 B2는 이 포트로만 바꾼다.
 *
 * <p>
 * 호출 규약
 * <ul>
 * <li>호출자(판정 처리) 트랜잭션에 참여한다(REQUIRED). 같은 트랜잭션에서 사건 상태와 함께 커밋·롤백된다.</li>
 * <li>바뀐 메시지마다 {@code version}을 1 올리고, 채팅 SSE의 MESSAGE_VISIBILITY_CHANGED를 <b>커밋 후에</b>
 * 보낸다(롤백되면 보내지 않는다). 누구에게 무엇을 보낼지는 platform이 정한다(원문은 PUBLIC 복원 때만).</li>
 * <li>반환값은 실제로 바뀐 메시지 수다. 이미 그 상태인 메시지는 건너뛰고 세지 않으며, 메시지가 없는 caseId는
 * 0이다(오류 아님).</li>
 * <li>해당 메시지 행을 잠그고 바꾸므로 같은 사건에 대한 동시 호출은 순서대로 처리된다.</li>
 * </ul>
 *
 * <p>
 * 도배(FLOOD) 사건: 앞의 두 메시지는 1차 필터를 통과(filter_result=PASS)한 채 증거로만 사건에 연결된다.
 * 정책상 도배 조치는 세 번째 이후 메시지가 대상이므로 {@link #hideCaseMessages}는 PASS 메시지를 가리지 않고
 * (공개 유지, 사건 연결도 유지) 세 번째 이후(FLOOD, HELD)만 가린다. {@link #restoreCaseMessages}는
 * filter_result와 상관없이 HELD·HIDDEN을 되돌린다.
 *
 * @deprecated 판정 뒤 공개 범위 변경은 {@link LivePlatformPort#visibility}를 쓴다. 그쪽은 {@code message_hold_reasons}를
 *             사건별로 해제해서, 다른 원인이 남은 메시지는 PUBLIC으로 되돌리지 않는다. 이 포트의
 *             {@link #restoreCaseMessages}는 원인을 보지 않고 HELD·HIDDEN을 모두 PUBLIC으로 바꾸므로 RULE_CONFIRMED
 *             (HIDDEN) 사건에는 쓰면 안 된다. 호출처가 없고 제출 후 삭제한다.
 */
@Deprecated
public interface MessageVisibilityPort {

	/** PUBLIC·HELD 메시지를 HIDDEN으로 바꾼다. 이미 HIDDEN이거나 filter_result가 PASS인 메시지는 건너뛴다. */
	int hideCaseMessages(UUID caseId);

	/** HELD·HIDDEN 메시지를 PUBLIC으로 되돌린다. 이미 PUBLIC인 메시지는 건너뛴다. */
	int restoreCaseMessages(UUID caseId);

}
