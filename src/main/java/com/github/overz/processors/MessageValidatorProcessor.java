package com.github.overz.processors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;

import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class MessageValidatorProcessor implements Processor<byte[], byte[], byte[], byte[]> {
	// limit to allow for headers and serialization overhead within the 1MB Kafka limit
	private final int maxMessageSize;

	private ProcessorContext<byte[], byte[]> ctx;

	@Override
	public void init(final ProcessorContext<byte[], byte[]> context) {
		this.ctx = Objects.requireNonNull(context, "context");
	}

	@Override
	public void process(final Record<byte[], byte[]> data) {
		final int keySize = data.key() != null ? data.key().length : 0;
		final int valueSize = data.value() != null ? data.value().length : 0;
		final int totalSize = keySize + valueSize;

		if (totalSize > maxMessageSize) {
			log.error("Message validation failed: Total size {} bytes exceeds limit of {} bytes. Key size: {}, Value size: {}. Dropping message.",
				totalSize, maxMessageSize, keySize, valueSize);
			// In a production environment, this should probably go to a DLQ.
			// For now, we drop it to protect the stream and changelog.
			return;
		}

		ctx.forward(data);
	}
}
