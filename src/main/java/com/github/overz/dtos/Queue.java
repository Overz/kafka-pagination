package com.github.overz.dtos;

import lombok.Builder;
import lombok.With;
import lombok.extern.jackson.Jacksonized;

@With
@Builder
@Jacksonized
public record Queue(
	String input,
	String output,
	Integer repartitions
) {

	public Queue {
		if (input == null || input.isBlank()) {
			throw new IllegalArgumentException("input cannot be null or blank");
		}
		if (output == null || output.isBlank()) {
			throw new IllegalArgumentException("output cannot be null or blank");
		}
		if (repartitions == null || repartitions <= 0) {
			throw new IllegalArgumentException("repartitions must be greater than 0");
		}
	}
}
