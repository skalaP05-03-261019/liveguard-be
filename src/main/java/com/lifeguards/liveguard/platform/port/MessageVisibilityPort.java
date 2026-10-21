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
 * 주의: 사건에 연결된 메시지는 전부 대상이다. 도배(FLOOD) 사건은 앞의 두 메시지(증거로 연결된 PUBLIC 메시지)도
 * 사건 메시지이므로 {@link #hideCaseMessages}가 함께 가린다.
 */
public interface MessageVisibilityPort {

	/** PUBLIC·HELD 메시지를 HIDDEN으로 바꾼다. 이미 HIDDEN인 메시지는 건너뛴다. */
	int hideCaseMessages(UUID caseId);

	/** HELD·HIDDEN 메시지를 PUBLIC으로 되돌린다. 이미 PUBLIC인 메시지는 건너뛴다. */
	int restoreCaseMessages(UUID caseId);

}
