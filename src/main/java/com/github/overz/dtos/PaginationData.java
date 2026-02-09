package com.github.overz.dtos;

import lombok.Builder;
import lombok.With;
import lombok.extern.jackson.Jacksonized;

import java.io.Serializable;

@With
@Builder
@Jacksonized
public record PaginationData(
	PageData page,
	PageMetadata metadata
) implements Serializable {
}
