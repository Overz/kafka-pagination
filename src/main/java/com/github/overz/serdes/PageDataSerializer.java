package com.github.overz.serdes;

import com.github.overz.Mappers;
import com.github.overz.dtos.PageData;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PageDataSerializer extends BaseSerializer<PageData> {
	@Override
	protected byte[] doDeserialize(String s, PageData o) throws Exception {
		return Mappers.json().writeValueAsBytes(o);
	}
}
