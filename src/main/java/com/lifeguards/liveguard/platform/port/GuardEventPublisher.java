package com.lifeguards.liveguard.platform.port;

import java.util.UUID;

/**
 * 관제 SSE(/api/guard/events, 11번)로 이벤트를 보내는 포트. B1이 제공한다.
 * Agent 진행 이벤트(TOOL_START·TOOL_END·TASK_FAILED)는 내부 API가, 사건 이벤트(CASE_CREATED 등)는 guard(B2)가
 * 호출한다. 받는 쪽은 해당 채널의 담당 운영자다(구현: platform.GuardEventHub).
 *
 * <p>
 * 트랜잭션 안에서 호출하면 <b>커밋 후에</b> 보내고, 롤백되면 보내지 않는다. 트랜잭션 밖이면 바로 보낸다.
 * 이벤트 이름은 계약의 7종(CASE_CREATED, CASE_UPDATED, TOOL_START, TOOL_END, KICK_REVIEW_REQUIRED,
 * KICK_APPLIED, TASK_FAILED)만 허용하며 그 외는 IllegalArgumentException이다. 전송은 별도 스레드에서 하므로
 * 호출이 SSE 전송을 기다리지 않는다.
 *
 * @param event 계약 x-sse-events의 이벤트 이름 (예: TOOL_START)
 * @param data 계약 스키마에 맞는 DTO (예: ToolEvent). 원문·도구 인자를 넣지 않는다
 */
public interface GuardEventPublisher {

	void publish(UUID channelId, String event, Object data);

}
