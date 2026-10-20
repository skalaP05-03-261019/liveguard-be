package com.lifeguards.liveguard.contracts.exception;

public class InvalidContractException extends IllegalArgumentException {
	private static final long serialVersionUID = 1L;

	public InvalidContractException(String message) {
		super(message);
	}
}
