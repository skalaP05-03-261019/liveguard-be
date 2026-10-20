package com.lifeguards.liveguard.platform;

import java.util.List;
import java.util.UUID;

/** 다른 패키지(auth 등)가 channel_members를 직접 조회하지 않도록 platform이 제공하는 조회 포트. */
public interface ChannelMembershipQuery {

	List<UUID> channelIdsOf(UUID userId);

}
