package com.lifeguards.liveguard.internal;

final class BusinessToolNotImplementedException extends RuntimeException {
	BusinessToolNotImplementedException() {
		super("Business tool backend operation is not implemented");
	}
}
