package com.github.overz.serdes;

import com.github.overz.Mappers;
import com.github.overz.dtos.PageData;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PageDataDeserializer extends BaseDeserializer<PageData> {
	@Override
	protected PageData doDeserialize(final String s, final byte[] o) throws Exception {
		return Mappers.json().readValue(o, PageData.class);
	}
}
