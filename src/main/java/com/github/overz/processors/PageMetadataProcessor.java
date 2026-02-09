package com.github.overz.processors;

import com.github.overz.dtos.MessageHeaders;
import com.github.overz.dtos.PageData;
import com.github.overz.dtos.PageMetadata;
import com.github.overz.dtos.PaginationData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.util.ArrayList;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class PageMetadataProcessor implements Processor<String, PageData, String, PaginationData> {
	private final String storageName;
	private final Serializer<PageMetadata> serializer;

	private ProcessorContext<String, PaginationData> ctx;
	private KeyValueStore<String, PageMetadata> storage;

	@Override
	public void init(ProcessorContext<String, PaginationData> context) {
		this.ctx = Objects.requireNonNull(context, "context");
		this.storage = Objects.requireNonNull(context.getStateStore(storageName), "storage");
	}

	@Override
	public void process(final Record<String, PageData> data) {
		final var headers = MessageHeaders.fromHeaders(data.headers());

		final var metadata = new PageMetadata(
			headers.topic(),
			headers.messageId(),
			headers.pageNumber(),
			headers.offset(),
			headers.partition(),
			headers.keySize(),
			headers.valueSize()
		);

		// Persist metadata for the specific page
		storage.put(headers.compositeKey(), metadata);

		// Combine page data and metadata into a single object for downstream aggregation
		final var pagination = new PaginationData(data.value(), metadata);
		ctx.forward(data.withValue(pagination));
	}
}
