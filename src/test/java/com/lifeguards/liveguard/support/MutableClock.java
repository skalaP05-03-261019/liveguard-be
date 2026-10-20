package com.lifeguards.liveguard.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 강퇴 만료 등 시간 의존 동작을 시험하기 위한 조절 가능한 Clock. */
public class MutableClock extends Clock {

	private volatile Instant now;

	public MutableClock(Instant start) {
		this.now = start;
	}

	public void advance(Duration duration) {
		now = now.plus(duration);
	}

	public void setNow(Instant instant) {
		now = instant;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return now;
	}

}
