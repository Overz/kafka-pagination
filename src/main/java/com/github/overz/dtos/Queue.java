package com.github.overz.dtos;

import lombok.Builder;
import lombok.With;
import lombok.extern.jackson.Jacksonized;

import java.time.Duration;
import java.util.Objects;

@With
@Builder
@Jacksonized
public record Queue(
	String input,
	String output,
	Integer repartitions,
	Duration retentionTime,
	Duration windowTime,
	Boolean retainDuplicates
) {

	public Queue {
		if (input == null || input.isBlank()) {
			throw new IllegalArgumentException("input cannot be null or blank");
		}

		if (output == null || output.isBlank()) {
			throw new IllegalArgumentException("output cannot be null or blank");
		}
	}

	public Duration retentionTime() {
		return Objects.requireNonNull(retentionTime, "retentionTime");
	}

	public Duration windowTime() {
		return Objects.requireNonNull(windowTime, "windowTime");
	}

	public Boolean retainDuplicates() {
		return Objects.requireNonNull(retainDuplicates, "retainDuplicates");
	}
}
