package com.github.overz.serdes;

import lombok.Getter;
import org.apache.kafka.common.serialization.Serializer;

import java.util.Map;

@Getter
public abstract class BaseSerializer<T> implements Serializer<T> {
	private Map<String, ?> configs;
	private boolean isKey;

	@Override
	public void configure(final Map<String, ?> configs, final boolean isKey) {
		this.configs = configs;
		this.isKey = isKey;
	}

	@Override
	public byte[] serialize(String s, T o) {
		try {
			return this.doDeserialize(s, o);
		} catch (Exception e) {
			throw new RuntimeException("Error serializing '" + o + "' to topic '" + s + "'", e);
		}
	}

	protected abstract byte[] doDeserialize(String s, T o) throws Exception;
}
