package com.lifeguards.liveguard.contracts.dto;

import java.util.List;

import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public final class DtoValidation {
	private DtoValidation() {
	}

	public static <T> T requireNonNull(T value, String name) {
		if (value == null) {
			throw new InvalidContractException(name + " is required");
		}
		return value;
	}

	public static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new InvalidContractException(name + " must not be blank");
		}
		return value;
	}

	/** 공백만 있는 문자열은 거부하고, 길이는 JSON Schema maxLength처럼 코드포인트 수로 센다. */
	public static String requireBoundedText(String value, String name, int maxLength) {
		requireText(value, name);
		if (value.codePointCount(0, value.length()) > maxLength) {
			throw new InvalidContractException(name + " must be at most " + maxLength + " characters");
		}
		return value;
	}

	public static <T> List<T> immutableList(List<T> values, String name) {
		requireNonNull(values, name);
		return List.copyOf(values);
	}

	public static <T> List<T> immutableListOrEmpty(List<T> values) {
		return values == null ? List.of() : List.copyOf(values);
	}

	public static void requireNotEmpty(List<?> values, String name) {
		if (values.isEmpty()) {
			throw new InvalidContractException(name + " must contain at least one item");
		}
	}

	public static void requireMaxSize(List<?> values, int max, String name) {
		if (values.size() > max) {
			throw new InvalidContractException(name + " cannot exceed " + max + " items");
		}
	}
}
