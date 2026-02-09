package com.github.overz.serdes;

import com.github.overz.Mappers;
import com.github.overz.dtos.PaginationSummary;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PaginationSummaryDeserializer extends BaseDeserializer<PaginationSummary> {
	@Override
	protected PaginationSummary doDeserialize(final String s, final byte[] o) throws Exception {
		return Mappers.json().readValue(o, PaginationSummary.class);
	}
}
