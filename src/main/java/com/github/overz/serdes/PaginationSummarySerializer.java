package com.github.overz.serdes;

import com.github.overz.Mappers;
import com.github.overz.dtos.PaginationSummary;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PaginationSummarySerializer extends BaseSerializer<PaginationSummary> {
	@Override
	protected byte[] doDeserialize(String s, PaginationSummary o) throws Exception {
		return Mappers.json().writeValueAsBytes(o);
	}
}
