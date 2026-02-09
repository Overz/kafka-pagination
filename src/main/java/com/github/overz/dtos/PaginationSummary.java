package com.github.overz.dtos;

import lombok.Builder;
import lombok.With;
import lombok.extern.jackson.Jacksonized;

import java.io.Serializable;
import java.util.ArrayList;
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
		// Removed strict validation for totalPages/totalElements as they might be unknown (-1) initially
		if (totalSize <= -1) {
			throw new IllegalArgumentException("totalSize must be positive");
		}

		if (references.isEmpty()) {
			throw new IllegalArgumentException("pages cannot be empty");
		}
	}

	public static PaginationSummary newSummary(final MessageHeaders headers) {
		final var refs = new ArrayList<String>();
		refs.add(headers.compositeKey());
		
		// If this first message is the last page (has totalElements), we know the totals.
		// Otherwise, we initialize with -1 (unknown).
		final boolean isLastPage = headers.totalElements() > 0;
		final int totalPages = isLastPage ? headers.pageNumber() : -1;
		final int totalElements = isLastPage ? headers.totalElements() : -1;

		return PaginationSummary.builder()
			.totalPages(totalPages)
			.totalElements(totalElements)
			.totalSize(headers.keySize() + headers.valueSize())
			.status(PaginationStatus.OPEN)
			.references(refs)
			.build();
	}
}
