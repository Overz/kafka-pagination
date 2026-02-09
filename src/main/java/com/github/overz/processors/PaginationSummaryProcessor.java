package com.github.overz.processors;

import com.github.overz.dtos.MessageHeaders;
import com.github.overz.dtos.PaginationData;
import com.github.overz.dtos.PaginationStatus;
import com.github.overz.dtos.PaginationSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RequiredArgsConstructor
public class PaginationSummaryProcessor implements Processor<String, PaginationData, String, PaginationSummary> {
	private final String storageName;
	private ProcessorContext<String, PaginationSummary> ctx;
	private KeyValueStore<String, PaginationSummary> storage;

	@Override
	public void init(final ProcessorContext<String, PaginationSummary> context) {
		this.ctx = Objects.requireNonNull(context, "context");
		this.storage = Objects.requireNonNull(context.getStateStore(storageName));
	}

	@Override
	public void process(final Record<String, PaginationData> data) {
		final var isNew = new AtomicBoolean(false);
		final var headers = MessageHeaders.fromHeaders(data.headers());
		// Retrieve existing summary or create a new one if it's the first page
		final var summary = Optional.ofNullable(storage.get(data.key()))
			.orElseGet(() -> {
				isNew.set(true);
				return PaginationSummary.newSummary(headers);
			});

		if (isNew.get()) {
			// Ensure new summary is saved
			storage.put(data.key(), summary);
			ctx.forward(data.withValue(summary));
			return;
		}

		// Update summary with the new page reference and check for completion
		summary.references().add(headers.compositeKey());
		storage.put(data.key(), update(summary, headers));
		ctx.forward(data.withValue(summary));
	}

	private PaginationSummary update(
		final PaginationSummary summary,
		final MessageHeaders headers
	) {
		final var paginationEnded = summary.totalElements() == headers.totalElements() && headers.totalElements() != 0;
		final var status = paginationEnded ? PaginationStatus.COMPLETED : PaginationStatus.OPEN;
		return summary.withStatus(status);
	}
}
