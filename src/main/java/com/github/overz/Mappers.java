package com.github.overz;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

public final class Mappers {
	private static JsonMapper instance;

	public static JsonMapper json() {
		if (instance == null) {
			instance = JsonMapper.builder()
				.findAndAddModules()
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
				.build()
			;
		}

		return instance;
	}
}
