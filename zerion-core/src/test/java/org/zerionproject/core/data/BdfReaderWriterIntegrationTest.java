package org.zerionproject.core.data;

import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.test.BrambleTestCase;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import static org.zerionproject.core.api.data.BdfReader.DEFAULT_MAX_BUFFER_SIZE;
import static org.zerionproject.core.api.data.BdfReader.DEFAULT_NESTED_LIMIT;
import static org.zerionproject.core.util.StringUtils.fromHexString;
import static org.zerionproject.core.util.StringUtils.toHexString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BdfReaderWriterIntegrationTest extends BrambleTestCase {

	@Test
	public void testConvertStringToCanonicalForm() throws Exception {

		String hexIn = "42" + "0003" + "666F6F";
		InputStream in = new ByteArrayInputStream(fromHexString(hexIn));
		BdfReader r = new BdfReaderImpl(in, DEFAULT_NESTED_LIMIT,
				DEFAULT_MAX_BUFFER_SIZE, false);
		String s = r.readString();
		assertEquals("foo", s);
		assertTrue(r.eof());

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = new BdfWriterImpl(out);
		w.writeString(s);
		w.flush();
		String hexOut = toHexString(out.toByteArray());

		assertEquals("41" + "03" + "666F6F", hexOut);
	}

	@Test
	public void testConvertDictionaryToCanonicalForm() throws Exception {

		String hexIn = "70" + "41" + "03" + "666F6F" + "21" + "01"
				+ "41" + "03" + "626172" + "21" + "02" + "80";
		InputStream in = new ByteArrayInputStream(fromHexString(hexIn));
		BdfReader r = new BdfReaderImpl(in, DEFAULT_NESTED_LIMIT,
				DEFAULT_MAX_BUFFER_SIZE, false);
		BdfDictionary d = r.readDictionary();
		assertEquals(2, d.size());
		assertTrue(r.eof());

		Iterator<Entry<String, Object>> it = d.entrySet().iterator();
		Entry<String, Object> first = it.next();
		assertEquals("bar", first.getKey());
		assertEquals(2L, first.getValue());
		Entry<String, Object> second = it.next();
		assertEquals("foo", second.getKey());
		assertEquals(1L, second.getValue());

		Map<String, Object> m = new LinkedHashMap<>();
		m.put("foo", 1);
		m.put("bar", 2);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = new BdfWriterImpl(out);
		w.writeDictionary(m);
		w.flush();
		String hexOut = toHexString(out.toByteArray());

		assertEquals("70" + "41" + "03" + "626172" + "21" + "02"
				+ "41" + "03" + "666F6F" + "21" + "01" + "80", hexOut);
	}
}
