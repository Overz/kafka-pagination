package com.github.overz.serdes;

import com.github.overz.dtos.PaginationSummary;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

public record PaginationSummarySerdes(
	Serializer<PaginationSummary> serializer,
	Deserializer<PaginationSummary> deserializer
) implements Serde<PaginationSummary> {
}
