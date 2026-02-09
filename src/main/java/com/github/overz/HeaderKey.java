package com.github.overz;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HeaderKey {
	public static final String ORIGIN = "ORIGIN";
	public static final String PAGINATION_ID = "PAGINATION_ID";
	public static final String MESSAGE_ID = "MESSAGE_ID";
	public static final String COMPOSITE_KEY = "COMPOSITE_KEY";
	public static final String TOTAL_ELEMENTS = "TOTAL_ELEMENTS";
	public static final String PAGE_SIZE = "PAGE_SIZE";
	public static final String PAGE_NUMBER = "PAGE_NUMBER";
	public static final String TOPIC = "TOPIC";
	public static final String OFFSET = "OFFSET";
	public static final String PARTITION = "PARTITION";
	public static final String MESSAGE_TIME = "MESSAGE_TIME";
	public static final String PAGE_KEY_SIZE = "KEY_SIZE";
	public static final String PAGE_VALUE_SIZE = "VALUE_SIZE";
	public static final String ORIGINAL_KEY_SIZE = "ORIGINAL_KEY_SIZE";
	public static final String ORIGINAL_VALUE_SIZE = "ORIGINAL_VALUE_SIZE";

	public static byte[] bytes(final int v) {
		return String.valueOf(v).getBytes(StandardCharsets.UTF_8);
	}

	public static byte[] bytes(final long v) {
		return String.valueOf(v).getBytes(StandardCharsets.UTF_8);
	}

	public static String string(final Headers headers, final String key) {
		final var header = headers.lastHeader(key);
		return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
	}

	public static int integer(final Headers headers, final String key) {
		final var val = string(headers, key);
		return val != null ? Integer.parseInt(val) : -1;
	}

	public static Instant instant(final Headers headers, final String key) {
		final var val = string(headers, key);
		return val != null ? Instant.parse(val) : null;
	}
}
