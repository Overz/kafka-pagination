package com.github.overz.serdes;

import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.util.Set;

public record HashSetSerdes(
	Serializer<Set<String>> serializer,
	Deserializer<Set<String>> deserializer
) implements Serde<Set<String>> {
}
