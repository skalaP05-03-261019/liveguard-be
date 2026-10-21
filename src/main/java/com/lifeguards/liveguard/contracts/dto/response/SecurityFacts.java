package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record SecurityFacts(List<Fact> facts) {
	public SecurityFacts {
		facts = DtoValidation.immutableList(facts, "facts");
	}

	public enum Value {
		TRUE, FALSE, UNKNOWN
	}

	public record Fact(String key, Value value, List<String> sourceRefs) {
		public Fact {
			key = DtoValidation.requireText(key, "key");
			DtoValidation.requireNonNull(value, "value");
			sourceRefs = DtoValidation.immutableList(sourceRefs, "sourceRefs");
			for (String source : sourceRefs) {
				DtoValidation.requireText(source, "sourceRef");
			}
			if (value != Value.UNKNOWN) {
				DtoValidation.requireNotEmpty(sourceRefs, "sourceRefs");
			}
		}
	}
}
