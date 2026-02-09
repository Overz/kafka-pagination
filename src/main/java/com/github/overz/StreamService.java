package com.github.overz;

import com.github.overz.dtos.*;
import com.github.overz.processors.*;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Repartitioned;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

@RequiredArgsConstructor
public class StreamService implements InitializingBean, DisposableBean {
	private final StreamsBuilder builder;
	private final List<Queue> queues;
	private final Serde<PageData> pageDataSerdes;
	private final Serde<PageMetadata> pageMetadataSerdes;
	private final Serde<PaginationSummary> paginationSummarySerdes;

	@Override
	public void afterPropertiesSet() throws Exception {
		buildStream();
	}

	@Override
	public void destroy() throws Exception {
		try {
			pageDataSerdes.close();
		} catch (Exception _) {
			// ignored
		}
	}

	private String concat(final String v, final String... parts) {
		final var joiner = new StringJoiner("-").add(v);
		Arrays.stream(parts).forEach(joiner::add);
		return joiner.toString();
	}

	public void buildStream() {
		final var stringSerdes = Serdes.String();
		final var genericSerdes = Serdes.ByteArray();

		for (final var q : queues) {
			final var summary = concat(q.input(), "summary");
			final var page = concat(q.input(), "page");
			final var metadata = concat(q.input(), "metadata");
			final var repartition = concat(q.input(), "repartition");

			final var pageStorage = Stores.windowStoreBuilder(
				Stores.persistentWindowStore(page, q.retentionTime(), q.windowTime(), q.retainDuplicates()),
				stringSerdes,
				pageDataSerdes
			);

			final var metadataStorage = Stores.keyValueStoreBuilder(
				Stores.persistentKeyValueStore(metadata),
				stringSerdes,
				pageMetadataSerdes
			);

			final var summaryStorage = Stores.keyValueStoreBuilder(
				Stores.persistentKeyValueStore(summary),
				stringSerdes,
				paginationSummarySerdes
			);

			final var repartitioned = Repartitioned.<String, PageData>numberOfPartitions(q.repartitions())
				.withName(repartition)
				.withKeySerde(stringSerdes)
				.withValueSerde(pageDataSerdes);

			builder
				.addStateStore(pageStorage)
				.addStateStore(metadataStorage)
				.addStateStore(summaryStorage)
				.stream(q.input(), Consumed.with(genericSerdes, genericSerdes))
				// Validate message size to ensure it fits within Kafka limits
				.process(() -> new MessageValidatorProcessor(
					Optional.ofNullable(System.getenv("MAX_MESSAGE_SIZE")).map(Integer::parseInt).orElse(9 * 1024 * 1024)
				))
				// Extract headers and body to a DTO to unify processing logic
				.process(() -> new ExtractDataProcessor(pageDataSerdes.serializer()))
				// Use pagination_id as key to group related messages
				.selectKey((k, v) -> k)
				// Repartition to ensure all pages of the same group are in the same partition
				.repartition(repartitioned)
				// Persist page content for later retrieval
				.process(() -> new PageDataProcessor(page), page)
				// Persist page metadata to track individual message details
				.process(() -> new PageMetadataProcessor(metadata, pageMetadataSerdes.serializer()), metadata)
				// Aggregate completion status to check if all pages arrived
				.process(() -> new PaginationSummaryProcessor(summary), summary)
				// Only downstream the event if all pages are collected
				.filter((key, value) -> value != null && value.status() == PaginationStatus.COMPLETED)
				.to(q.output(), Produced.with(stringSerdes, paginationSummarySerdes))
			;
		}
	}
}
