package com.github.overz.processors;

import com.github.overz.dtos.PageData;
import com.github.overz.dtos.PageMetadata;
import com.github.overz.dtos.PaginationSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class AckProcessor implements Processor<String, String, Void, Void> {
	private final String pageStorageName;
	private final String metadataStorageName;
	private final String summaryStorageName;
	private final String consumersStorageName;
	private final String ackStorageName;

	private KeyValueStore<String, PageData> pageStorage;
	private KeyValueStore<String, PageMetadata> metadataStorage;
	private KeyValueStore<String, PaginationSummary> summaryStorage;
	private KeyValueStore<String, Set<String>> ackStorage;
	private KeyValueStore<String, Set<String>> consumersStorage;

	@Override
	public void init(final ProcessorContext<Void, Void> context) {
		this.pageStorage = Objects.requireNonNull(context.getStateStore(pageStorageName), "pageStorage");
		this.metadataStorage = Objects.requireNonNull(context.getStateStore(metadataStorageName), "metadataStorage");
		this.summaryStorage = Objects.requireNonNull(context.getStateStore(summaryStorageName), "summaryStorage");
		this.consumersStorage = Objects.requireNonNull(context.getStateStore(consumersStorageName), "consumersStorage");
		this.ackStorage = Objects.requireNonNull(context.getStateStore(ackStorageName), "ackStorage");
	}

	@Override
	public void process(final Record<String, String> data) {
		final var acks = Optional.ofNullable(ackStorage.get(data.key())).orElse(new HashSet<>());
		acks.add(data.value());
		ackStorage.put(data.key(), acks);

		final var consumers = consumersStorage.get(data.key());

		if (consumers == null || consumers.isEmpty()) {
			log.warn("Received an ack for paginationId='{}' but no consumers are registered for it. Ignoring.", data.key());
			return;
		}

		if (consumers.equals(acks)) {
			log.info("All registered consumers have sent an ack for paginationId='{}'. Initiating cleanup.", data.key());
			cleanup(data.key());
		} else {
			log.debug("Still waiting for acks for paginationId='{}'. Received: {}, Expected: {}", data.key(), acks, consumers);
		}
	}

	private void cleanup(String paginationId) {
		final var summary = summaryStorage.get(paginationId);
		if (summary == null) {
			log.warn("Could not find summary for paginationId='{}' during cleanup. Maybe already cleaned up?", paginationId);
			ackStorage.delete(paginationId);
			consumersStorage.delete(paginationId);
			return;
		}

		for (final var reference : summary.references()) {
			metadataStorage.delete(reference);
			pageStorage.delete(reference);
		}
		log.info("Cleaned up {} pages and metadata entries for paginationId='{}'", summary.references().size(), paginationId);

		summaryStorage.delete(paginationId);
		log.info("Cleaned up summary for paginationId='{}'", paginationId);

		ackStorage.delete(paginationId);
		log.info("Cleaned up ack entry for paginationId='{}'", paginationId);

		consumersStorage.delete(paginationId);
		log.info("Cleaned up registration entry for paginationId='{}'", paginationId);
	}
}
