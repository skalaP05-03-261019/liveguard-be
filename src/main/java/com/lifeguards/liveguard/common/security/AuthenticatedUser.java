package com.lifeguards.liveguard.common.security;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.enums.Role;

/** 로그인한 사용자. 다른 패키지가 auth 구현에 의존하지 않고 현재 사용자를 받기 위한 인터페이스. */
public interface AuthenticatedUser {

	UUID userId();

	String nickname();

	Role role();

}
