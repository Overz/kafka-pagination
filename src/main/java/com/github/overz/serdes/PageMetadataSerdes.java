package com.github.overz.serdes;

import com.github.overz.dtos.PageMetadata;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

public record PageMetadataSerdes(
	Serializer<PageMetadata> serializer,
	Deserializer<PageMetadata> deserializer
) implements Serde<PageMetadata> {
}
