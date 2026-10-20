package com.lifeguards.liveguard.platform.port;

import java.util.UUID;

/**
 * 관제 SSE(/api/guard/events, 11번)로 이벤트를 보내는 포트. B1이 제공한다.
 * Agent 진행 이벤트(TOOL_START·TOOL_END·TASK_FAILED)는 내부 API가, 사건 이벤트(CASE_CREATED 등)는 guard(B2)가
 * 호출한다. 받는 쪽은 해당 채널의 담당 운영자다. 11번 구현 전에는 {@link InMemoryGuardEventPublisher}가 쓰인다.
 *
 * @param event 계약 x-sse-events의 이벤트 이름 (예: TOOL_START)
 * @param data 계약 스키마에 맞는 DTO (예: ToolEvent). 원문·도구 인자를 넣지 않는다
 */
public interface GuardEventPublisher {

	void publish(UUID channelId, String event, Object data);

}
