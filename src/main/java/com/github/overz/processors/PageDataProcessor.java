package com.github.overz.processors;

import com.github.overz.dtos.MessageHeaders;
import com.github.overz.dtos.PageData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.WindowStore;

import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class PageDataProcessor implements Processor<String, PageData, String, PageData> {
	private final String storageName;

	private ProcessorContext<String, PageData> ctx;
	private WindowStore<String, PageData> storage;

	@Override
	public void init(final ProcessorContext<String, PageData> context) {
		this.ctx = Objects.requireNonNull(context, "context");
		this.storage = Objects.requireNonNull(context.getStateStore(storageName), "storage");
	}

	@Override
	public void process(final Record<String, PageData> data) {
		final var headers = MessageHeaders.fromHeaders(data.headers());
		// Store the page data using the composite key for later retrieval
		storage.put(headers.compositeKey(), data.value(), System.currentTimeMillis());
		// Forward the record with the composite key to the next processor
		ctx.forward(data.withKey(headers.compositeKey()));
	}
}
