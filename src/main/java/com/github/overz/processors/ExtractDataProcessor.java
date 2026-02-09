package com.github.overz.processors;

import com.github.overz.HeaderKey;
import com.github.overz.dtos.PageData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.api.RecordMetadata;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static com.github.overz.HeaderKey.bytes;

@Slf4j
@RequiredArgsConstructor
public class ExtractDataProcessor implements Processor<byte[], byte[], String, PageData> {
	private final Serializer<PageData> serializer;

	private ProcessorContext<String, PageData> ctx;
	private RecordMetadata metadata;

	@Override
	public void init(final ProcessorContext<String, PageData> context) {
		this.ctx = Objects.requireNonNull(context, "context");
		this.metadata = context.recordMetadata()
			.orElseThrow(() -> new RuntimeException("Missing context metadata"));
	}

	@Override
	public void process(final Record<byte[], byte[]> data) {
		final var topic = metadata.topic();
		final var headers = new RecordHeaders(data.headers());

		// Extract pagination and message IDs from headers
		final var pid = headers.lastHeader(HeaderKey.PAGINATION_ID).value();
		final var mid = headers.lastHeader(HeaderKey.MESSAGE_ID).value();
		final var page = new PageData(data.key(), data.value());
		final var serialized = serializer.serialize(
			topic,
			headers,
			page
		);

		// Enrich headers with metadata for downstream processing
		headers.add(HeaderKey.TOPIC, topic.getBytes());
		headers.add(HeaderKey.PARTITION, bytes(metadata.partition()));
		headers.add(HeaderKey.OFFSET, bytes(metadata.offset()));
		headers.add(HeaderKey.MESSAGE_TIME, bytes(data.timestamp()));
		headers.add(HeaderKey.ORIGINAL_KEY_SIZE, bytes(data.key() != null ? data.key().length : -1));
		headers.add(HeaderKey.ORIGINAL_VALUE_SIZE, bytes(data.value() != null ? data.value().length : -1));
		headers.add(HeaderKey.PAGE_KEY_SIZE, bytes(pid.length));
		headers.add(HeaderKey.PAGE_VALUE_SIZE, bytes(serialized.length));

		// Create a composite key to uniquely identify the message page
		final var composeKey = String.format(
			"%s@%s",
			new String(pid, StandardCharsets.UTF_8),
			new String(mid, StandardCharsets.UTF_8)
		);
		headers.add(HeaderKey.COMPOSITE_KEY, composeKey.getBytes(StandardCharsets.UTF_8));

		// Forward the record with the pagination ID as the key
		ctx.forward(new Record<>(
			new String(pid, StandardCharsets.UTF_8),
			page,
			data.timestamp(),
			headers
		));
	}
}
