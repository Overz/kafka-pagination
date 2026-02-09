package com.github.overz.serdes;

import com.github.overz.Mappers;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

@Slf4j
public class HashSetSerializer extends BaseSerializer<Set<String>> {
	@Override
	protected byte[] doDeserialize(String s, Set<String> o) throws Exception {
		return Mappers.json().writeValueAsBytes(o);
	}
}
