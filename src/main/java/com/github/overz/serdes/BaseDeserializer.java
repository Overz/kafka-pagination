package com.github.overz.serdes;

import lombok.Getter;
import org.apache.kafka.common.serialization.Deserializer;

import java.util.Map;

@Getter
public abstract class BaseDeserializer<T> implements Deserializer<T> {
	private Map<String, ?> configs;
	private boolean isKey;

	@Override
	public void configure(final Map<String, ?> configs, final boolean isKey) {
		this.configs = configs;
		this.isKey = isKey;
	}

	@Override
	public T deserialize(String s, byte[] bytes) {
		try {
			return this.doDeserialize(s, bytes);
		} catch (Exception e) {
			throw new RuntimeException("Error deserializing content from to topic '" + s + "'", e);
		}
	}

	protected abstract T doDeserialize(String s, byte[] o) throws Exception;
}
