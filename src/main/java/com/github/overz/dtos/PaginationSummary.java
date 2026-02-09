package com.github.overz.dtos;

import lombok.Builder;
import lombok.With;
import lombok.extern.jackson.Jacksonized;

import java.io.Serializable;
import java.util.List;

@With
@Builder
@Jacksonized
public record PaginationSummary(
	int totalPages,
	int totalElements,
	int totalSize,
	PaginationStatus status,
	List<String> references
) implements Serializable {

	public PaginationSummary {
		if (totalPages <= -1) {
			throw new IllegalArgumentException("totalPages must be positive");
		}

		if (totalElements <= -1) {
			throw new IllegalArgumentException("totalElements must be positive");
		}

		if (totalSize <= -1) {
			throw new IllegalArgumentException("totalSize must be positive");
		}

		if (references.isEmpty()) {
			throw new IllegalArgumentException("pages cannot be empty");
		}
	}

	public static PaginationSummary newSummary(final MessageHeaders headers) {
		return PaginationSummary.builder()
			.totalPages(headers.pageSize())
			.totalElements(headers.totalElements())
			.totalSize(headers.keySize() + headers.valueSize())
			.status(headers.totalElements() != 0 ? PaginationStatus.COMPLETED : PaginationStatus.OPEN)
			.references(List.of(headers.compositeKey()))
			.build();
	}
}
