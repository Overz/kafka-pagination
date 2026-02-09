package com.github.overz.dtos;

import lombok.Builder;
import lombok.With;
import lombok.extern.jackson.Jacksonized;
import org.apache.kafka.common.header.Headers;

import java.io.Serializable;
import java.time.Instant;

import static com.github.overz.HeaderKey.*;

@With
@Builder
@Jacksonized
public record MessageHeaders(
	String origin,
	String paginationId,
	String messageId,
	String compositeKey,
	String topic,
	int offset,
	int partition,
	Instant messageTime,
	int totalElements,
	int pageSize,
	int pageNumber,
	int keySize,
	int valueSize,
	int originalKeySize,
	int originalValueSize
) implements Serializable {

	@SuppressWarnings({ "java:S3776" })
	public MessageHeaders {
		if (origin == null || origin.isEmpty()) {
			throw new IllegalArgumentException("origin cannot be null or empty");
		}
		if (paginationId == null || paginationId.isEmpty()) {
			throw new IllegalArgumentException("paginationId cannot be null or empty");
		}
		if (messageId == null || messageId.isEmpty()) {
			throw new IllegalArgumentException("messageId cannot be null or empty");
		}
		if (compositeKey == null || compositeKey.isEmpty()) {
			throw new IllegalArgumentException("compositeKey cannot be null or empty");
		}
		if (topic == null || topic.isEmpty()) {
			throw new IllegalArgumentException("topic cannot be null or empty");
		}
		if (offset <= -1) {
			throw new IllegalArgumentException("offset must be greater than -1");
		}
		if (partition <= -1) {
			throw new IllegalArgumentException("partition must be greater than -1");
		}
		if (messageTime == null) {
			throw new IllegalArgumentException("messageTime cannot be null");
		}
		if (totalElements <= -1) {
			throw new IllegalArgumentException("totalElements must be greater than -1");
		}
		if (pageSize <= -1) {
			throw new IllegalArgumentException("pageSize must be greater than -1");
		}
		if (pageNumber <= -1) {
			throw new IllegalArgumentException("pageNumber must be greater than -1");
		}
		if (keySize <= -1) {
			throw new IllegalArgumentException("keySize must be greater than -1");
		}
		if (valueSize <= -1) {
			throw new IllegalArgumentException("valueSize must be greater than -1");
		}
		if (originalKeySize <= -1) {
			throw new IllegalArgumentException("originalKeySize must be greater than -1");
		}
		if (originalValueSize <= -1) {
			throw new IllegalArgumentException("originalValueSize must be greater than -1");
		}
	}

	public static MessageHeaders fromHeaders(final Headers headers) {
		return MessageHeaders.builder()
			.origin(string(headers, ORIGIN))
			.paginationId(string(headers, PAGINATION_ID))
			.messageId(string(headers, MESSAGE_ID))
			.compositeKey(string(headers, COMPOSITE_KEY))
			.topic(string(headers, TOPIC))
			.offset(integer(headers, OFFSET))
			.partition(integer(headers, PARTITION))
			.messageTime(instant(headers, MESSAGE_TIME))
			.totalElements(integer(headers, TOTAL_ELEMENTS))
			.pageSize(integer(headers, PAGE_SIZE))
			.pageNumber(integer(headers, PAGE_NUMBER))
			.keySize(integer(headers, PAGE_KEY_SIZE))
			.valueSize(integer(headers, PAGE_VALUE_SIZE))
			.originalKeySize(integer(headers, ORIGINAL_KEY_SIZE))
			.originalValueSize(integer(headers, ORIGINAL_VALUE_SIZE))
			.build();
	}
}
