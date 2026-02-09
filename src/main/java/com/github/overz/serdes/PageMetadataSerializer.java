package com.github.overz.serdes;

import com.github.overz.Mappers;
import com.github.overz.dtos.PageMetadata;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PageMetadataSerializer extends BaseSerializer<PageMetadata> {
	@Override
	protected byte[] doDeserialize(String s, PageMetadata o) throws Exception {
		return Mappers.json().writeValueAsBytes(o);
	}
}
