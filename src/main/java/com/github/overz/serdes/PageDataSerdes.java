package com.github.overz.serdes;

import com.github.overz.dtos.PageData;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

public record PageDataSerdes(
	Serializer<PageData> serializer,
	Deserializer<PageData> deserializer
) implements Serde<PageData> {
}
