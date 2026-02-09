package com.github.overz.configs;

import com.github.overz.StreamService;
import com.github.overz.dtos.Queue;
import com.github.overz.serdes.*;
import org.apache.kafka.streams.StreamsBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;

import java.util.List;

@Configuration
@EnableKafkaStreams
public class ApplicationConfig {

	@Bean
	public StreamService streamService(
		final StreamsBuilder streamsBuilder
	) {
		return new StreamService(
			"pagination-consumers",
			"pagination-ack",
			streamsBuilder,
			List.of(
				new Queue("a", "b", 1),
				new Queue("a1", "b1", 1),
				new Queue("a2", "b2", 1)
			),
			new PageDataSerdes(new PageDataSerializer(), new PageDataDeserializer()),
			new PageMetadataSerdes(new PageMetadataSerializer(), new PageMetadataDeserializer()),
			new PaginationSummarySerdes(new PaginationSummarySerializer(), new PaginationSummaryDeserializer()),
			new HashSetSerdes(new HashSetSerializer(), new HashSetDeserializer())
		);
	}
}
