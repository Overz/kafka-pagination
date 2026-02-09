package com.github.overz.processors;

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
public class RegistrationProcessor implements Processor<String, String, Void, Void> {

	private final String registrationStorageName;
	private KeyValueStore<String, Set<String>> storage;

	@Override
	public void init(final ProcessorContext<Void, Void> context) {
		this.storage = Objects.requireNonNull(context.getStateStore(registrationStorageName), "storage");
	}

	@Override
	public void process(final Record<String, String> data) {
		final var consumers = Optional.ofNullable(storage.get(data.key())).orElse(new HashSet<>());
		consumers.add(data.value());
		storage.put(data.key(), consumers);
	}
}
