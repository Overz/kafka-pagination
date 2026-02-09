package com.github.overz.serdes;

import com.github.overz.Mappers;
import com.github.overz.dtos.PageMetadata;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PageMetadataDeserializer extends BaseDeserializer<PageMetadata> {
	@Override
	protected PageMetadata doDeserialize(final String s, final byte[] o) throws Exception {
		return Mappers.json().readValue(o, PageMetadata.class);
	}
}
