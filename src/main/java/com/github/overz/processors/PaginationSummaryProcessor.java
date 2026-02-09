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
		final var key = headers.paginationId();

		// Retrieve existing summary or create a new one if it's the first page
		var summary = Optional.ofNullable(storage.get(key))
			.orElseGet(() -> {
				isNew.set(true);
				return PaginationSummary.newSummary(headers);
			});

		if (isNew.get()) {
			// Even if new, we check status (case of single page pagination)
			summary = updateStatus(summary);
			storage.put(key, summary);
			ctx.forward(data.withValue(summary));
			return;
		}

		summary.references().add(headers.compositeKey());

		// If this message has the total count (is the last page), update the summary totals
		if (headers.totalElements() > 0) {
			summary = summary.withTotalElements(headers.totalElements())
				.withTotalPages(headers.pageNumber());
		}

		// Update summary with the new page reference and check for completion
		final var updatedSummary = updateStatus(summary);
		storage.put(key, updatedSummary);

		// forward the summary
		ctx.forward(data.withValue(updatedSummary));

		// remove when completed
		if (summary.status() == PaginationStatus.COMPLETED) {
			storage.delete(key);
		}
	}

	private PaginationSummary updateStatus(final PaginationSummary summary) {
		// We can only be completed if we know the total pages (totalPages != -1)
		// AND we have collected exactly that many pages.
		final boolean isTotalKnown = summary.totalPages() != -1;
		final boolean allPagesReceived = isTotalKnown && summary.references().size() == summary.totalPages();

		final var status = allPagesReceived ?
			PaginationStatus.COMPLETED :
			PaginationStatus.OPEN;

		return summary.withStatus(status);
	}
}
