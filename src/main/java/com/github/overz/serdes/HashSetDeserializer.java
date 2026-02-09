package com.github.overz.serdes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.overz.Mappers;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

@Slf4j
public class HashSetDeserializer extends BaseDeserializer<Set<String>> {
	private final TypeReference<Set<String>> ref = new TypeReference<>() {
	};

	@Override
	protected Set<String> doDeserialize(String s, byte[] o) throws Exception {
		return Mappers.json().readValue(o, ref);
	}
}
