package com.lifeguards.liveguard.contracts.dto;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public final class DtoValidation {
	private DtoValidation() {
	}

	public static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new InvalidContractException(name + " must not be blank");
		}
		return value;
	}

	public static void requirePositive(int value, String name) {
		if (value <= 0) {
			throw new InvalidContractException(name + " must be positive");
		}
	}

	public static void requireNonNegative(int value, String name) {
		if (value < 0) {
			throw new InvalidContractException(name + " must be non-negative");
		}
	}

	public static <T> List<T> immutableList(List<T> values, String name) {
		Objects.requireNonNull(values, name);
		return List.copyOf(values);
	}

	public static List<String> immutableTextList(List<String> values, String name) {
		List<String> copy = immutableList(values, name);
		copy.forEach(value -> requireText(value, name + " item"));
		return copy;
	}

	public static <K, V> Map<K, V> immutableMap(Map<K, V> values, String name) {
		Objects.requireNonNull(values, name);
		return Map.copyOf(values);
	}
}
