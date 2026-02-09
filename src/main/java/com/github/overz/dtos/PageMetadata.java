package com.github.overz.dtos;

import lombok.Builder;
import lombok.With;
import lombok.extern.jackson.Jacksonized;

import java.io.Serializable;

@With
@Builder
@Jacksonized
public record PageMetadata(
	String topic,
	String messageId,
	Integer pageNumber,
	Integer offset,
	Integer partition,
	Integer keySize,
	Integer valueSize
) implements Serializable {

	public PageMetadata {
		if (topic == null || topic.isEmpty()) {
			throw new IllegalArgumentException("topic cannot be null or empty");
		}
		if (messageId == null || messageId.isEmpty()) {
			throw new IllegalArgumentException("messageId cannot be null or empty");
		}
		if (pageNumber == null || pageNumber <= -1) {
			throw new IllegalArgumentException("pageNumber cannot be null or empty");
		}
		if (offset == null || offset <= -1) {
			throw new IllegalArgumentException("offset cannot be null or empty");
		}
		if (partition == null || partition <= -1) {
			throw new IllegalArgumentException("partition cannot be null or empty");
		}
		if (keySize == null || keySize <= -1) {
			throw new IllegalArgumentException("keySize cannot be null or empty");
		}
		if (valueSize == null || valueSize <= -1) {
			throw new IllegalArgumentException("valueSize cannot be null or empty");
		}
	}
}
