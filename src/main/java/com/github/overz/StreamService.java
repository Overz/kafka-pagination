package com.github.overz;

import com.github.overz.dtos.*;
import com.github.overz.processors.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Repartitioned;
import org.apache.kafka.streams.state.Stores;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class StreamService implements InitializingBean, DisposableBean {
	private static final String PAGE_STORE_NAME = "pagination-page-store";
	private static final String METADATA_STORE_NAME = "pagination-metadata-store";
	private static final String SUMMARY_STORE_NAME = "pagination-summary-store";
	private static final String REGISTRATION_STORE_NAME = "pagination-registrations-store";
	private static final String ACK_STORE_NAME = "pagination-acks-store";

	private final String consumersTopic;
	private final String ackTopic;
	private final StreamsBuilder builder;
	private final List<Queue> queues;
	private final Serde<PageData> pageDataSerdes;
	private final Serde<PageMetadata> pageMetadataSerdes;
	private final Serde<PaginationSummary> paginationSummarySerdes;
	private final Serde<Set<String>> hashSetSerde;

	@Override
	public void afterPropertiesSet() throws Exception {
		buildStream();
	}

	@Override
	public void destroy() throws Exception {
		try {
			pageDataSerdes.close();
			pageMetadataSerdes.close();
			paginationSummarySerdes.close();
		} catch (Exception _) {
			// ignored
		}
	}

	public void buildStream() {
		final var stringSerdes = Serdes.String();
		final var genericSerdes = Serdes.ByteArray();

		final var pageStorage = Stores.keyValueStoreBuilder(
			Stores.persistentKeyValueStore(PAGE_STORE_NAME), stringSerdes, pageDataSerdes
		);
		final var metadataStorage = Stores.keyValueStoreBuilder(
			Stores.persistentKeyValueStore(METADATA_STORE_NAME), stringSerdes, pageMetadataSerdes
		);
		final var summaryStorage = Stores.keyValueStoreBuilder(
			Stores.persistentKeyValueStore(SUMMARY_STORE_NAME), stringSerdes, paginationSummarySerdes
		);
		final var registrationStorage = Stores.keyValueStoreBuilder(
			Stores.persistentKeyValueStore(REGISTRATION_STORE_NAME), stringSerdes, hashSetSerde
		);
		final var ackStorage = Stores.keyValueStoreBuilder(
			Stores.persistentKeyValueStore(ACK_STORE_NAME), stringSerdes, hashSetSerde
		);

		builder
			.addStateStore(pageStorage)
			.addStateStore(metadataStorage)
			.addStateStore(summaryStorage)
			.addStateStore(registrationStorage)
			.addStateStore(ackStorage);

		final var maxMessageSize = Optional.ofNullable(System.getenv("MAX_MESSAGE_SIZE"))
			.map(Integer::parseInt)
			.orElse(900 * 1024);

		for (final var q : queues) {
			final var repartitionName = q.input() + "-pagination-repartition";

			final var repartitioned = Repartitioned.<String, PageData>numberOfPartitions(q.repartitions())
				.withName(repartitionName)
				.withKeySerde(stringSerdes)
				.withValueSerde(pageDataSerdes);

			builder.stream(q.input(), Consumed.with(genericSerdes, genericSerdes))
				.process(() -> new MessageValidatorProcessor(maxMessageSize))
				.process(() -> new ExtractDataProcessor(pageDataSerdes.serializer()))
				.selectKey((k, v) -> k)
				.repartition(repartitioned)
				.process(() -> new PageDataProcessor(PAGE_STORE_NAME), PAGE_STORE_NAME)
				.process(() -> new PageMetadataProcessor(METADATA_STORE_NAME, pageMetadataSerdes.serializer()), METADATA_STORE_NAME)
				.process(() -> new PaginationSummaryProcessor(SUMMARY_STORE_NAME), SUMMARY_STORE_NAME)
				.filter((key, value) -> value != null && value.status() == PaginationStatus.COMPLETED)
				.to(q.output(), Produced.with(stringSerdes, paginationSummarySerdes));
		}

		builder.stream(consumersTopic, Consumed.with(stringSerdes, stringSerdes))
			.filter((key, value) -> key != null && value != null)
			.peek((key, value) -> log.info(
				"Registering interest for pagination-id'{}' from consumer '{}'", key, value
			))
			.process(() -> new RegistrationProcessor(REGISTRATION_STORE_NAME));

		builder.stream(ackTopic, Consumed.with(stringSerdes, stringSerdes))
			.filter((key, value) -> key != null && value != null)
			.peek((key, value) -> log.info(
				"Received ack confirmation for pagination-id '{}' from consumer '{}'", key, value
			))
			.process(() -> new AckProcessor(
				PAGE_STORE_NAME,
				METADATA_STORE_NAME,
				SUMMARY_STORE_NAME,
				REGISTRATION_STORE_NAME,
				ACK_STORE_NAME
			));
	}
}
