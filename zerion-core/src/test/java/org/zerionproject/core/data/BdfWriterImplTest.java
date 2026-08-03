package org.zerionproject.core.data;

import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.util.StringUtils;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.zerionproject.core.api.data.BdfDictionary.NULL_VALUE;
import static org.junit.Assert.assertArrayEquals;

public class BdfWriterImplTest extends BrambleTestCase {

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();
	private final BdfWriterImpl w = new BdfWriterImpl(out);

	@Test
	public void testWriteNull() throws IOException {
		w.writeNull();
		checkContents("00");
	}

	@Test
	public void testWriteBoolean() throws IOException {
		w.writeBoolean(true);
		w.writeBoolean(false);

		checkContents("11" + "10");
	}

	@Test
	public void testWriteLong() throws IOException {
		w.writeLong(0);
		w.writeLong(-1);
		w.writeLong(Byte.MAX_VALUE);
		w.writeLong(Byte.MIN_VALUE);
		w.writeLong(Short.MAX_VALUE);
		w.writeLong(Short.MIN_VALUE);
		w.writeLong(Integer.MAX_VALUE);
		w.writeLong(Integer.MIN_VALUE);
		w.writeLong(Long.MAX_VALUE);
		w.writeLong(Long.MIN_VALUE);

		checkContents("21" + "00" + "21" + "FF" +
				"21" + "7F" + "21" + "80" +
				"22" + "7FFF" + "22" + "8000" +
				"24" + "7FFFFFFF" + "24" + "80000000" +
				"28" + "7FFFFFFFFFFFFFFF" + "28" + "8000000000000000");
	}

	@Test
	public void testWriteDouble() throws IOException {

		w.writeDouble(0.0);
		w.writeDouble(1.0);
		w.writeDouble(2.0);
		w.writeDouble(-1.0);
		w.writeDouble(-0.0);
		w.writeDouble(Double.NEGATIVE_INFINITY);
		w.writeDouble(Double.POSITIVE_INFINITY);
		w.writeDouble(Double.NaN);
		checkContents("38" + "0000000000000000" + "38" + "3FF0000000000000"
				+ "38" + "4000000000000000" + "38" + "BFF0000000000000"
				+ "38" + "8000000000000000" + "38" + "FFF0000000000000"
				+ "38" + "7FF0000000000000" + "38" + "7FF8000000000000");
	}

	@Test
	public void testWriteString8() throws IOException {
		String longest = StringUtils.getRandomString(Byte.MAX_VALUE);
		String longHex = StringUtils.toHexString(longest.getBytes("UTF-8"));
		w.writeString("foo bar baz bam ");
		w.writeString(longest);

		checkContents("41" + "10" + "666F6F206261722062617A2062616D20" +
				"41" + "7F" + longHex);
	}

	@Test
	public void testWriteString16() throws IOException {
		String shortest = StringUtils.getRandomString(Byte.MAX_VALUE + 1);
		String shortHex = StringUtils.toHexString(shortest.getBytes("UTF-8"));
		String longest = StringUtils.getRandomString(Short.MAX_VALUE);
		String longHex = StringUtils.toHexString(longest.getBytes("UTF-8"));
		w.writeString(shortest);
		w.writeString(longest);

		checkContents("42" + "0080" + shortHex + "42" + "7FFF" + longHex);
	}

	@Test
	public void testWriteString32() throws IOException {
		String shortest = StringUtils.getRandomString(Short.MAX_VALUE + 1);
		String shortHex = StringUtils.toHexString(shortest.getBytes("UTF-8"));
		w.writeString(shortest);

		checkContents("44" + "00008000" + shortHex);
	}

	@Test
	public void testWriteUtf8String() throws IOException {
		String unicode = "\uFDD0\uFDD1\uFDD2\uFDD3";
		String hex = StringUtils.toHexString(unicode.getBytes("UTF-8"));
		w.writeString(unicode);

		checkContents("41" + "0C" + hex);
	}

	@Test
	public void testWriteRaw8() throws IOException {
		byte[] longest = new byte[Byte.MAX_VALUE];
		String longHex = StringUtils.toHexString(longest);
		w.writeRaw(new byte[] {1, 2, 3});
		w.writeRaw(longest);

		checkContents("51" + "03" + "010203" + "51" + "7F" + longHex);
	}

	@Test
	public void testWriteRaw16() throws IOException {
		byte[] shortest = new byte[Byte.MAX_VALUE + 1];
		String shortHex = StringUtils.toHexString(shortest);
		byte[] longest = new byte[Short.MAX_VALUE];
		String longHex = StringUtils.toHexString(longest);
		w.writeRaw(shortest);
		w.writeRaw(longest);

		checkContents("52" + "0080" + shortHex + "52" + "7FFF" + longHex);
	}

	@Test
	public void testWriteRaw32() throws IOException {
		byte[] shortest = new byte[Short.MAX_VALUE + 1];
		String shortHex = StringUtils.toHexString(shortest);
		w.writeRaw(shortest);

		checkContents("54" + "00008000" + shortHex);
	}

	@Test
	public void testWriteList() throws IOException {
		List<Object> l = new ArrayList<>();
		for (int i = 0; i < 3; i++) l.add(i);
		w.writeList(l);

		checkContents("60" + "21" + "00" + "21" + "01" + "21" + "02" + "80");
	}

	@Test
	public void testListCanContainNull() throws IOException {
		List<Object> l = new ArrayList<>();
		l.add(1);
		l.add(null);
		l.add(NULL_VALUE);
		l.add(2);
		w.writeList(l);

		checkContents("60" + "21" + "01" + "00" + "00" + "21" + "02" + "80");
	}

	@Test
	public void testWriteDictionary() throws IOException {

		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 3; i >= 0; i--) m.put(String.valueOf(i), i);
		w.writeDictionary(m);

		checkContents("70" + "41" + "01" + "30" + "21" + "00" +
				"41" + "01" + "31" + "21" + "01" +
				"41" + "01" + "32" + "21" + "02" +
				"41" + "01" + "33" + "21" + "03" + "80");
	}

	@Test
	public void testWriteBdfDictionary() throws IOException {

		BdfDictionary d = new BdfDictionary();
		for (int i = 3; i >= 0; i--) d.put(String.valueOf(i), i);
		w.writeDictionary(d);

		checkContents("70" + "41" + "01" + "30" + "21" + "00" +
				"41" + "01" + "31" + "21" + "01" +
				"41" + "01" + "32" + "21" + "02" +
				"41" + "01" + "33" + "21" + "03" + "80");
	}

	@Test
	public void testWriteNestedDictionariesAndLists() throws IOException {
		Map<String, Object> inner = new LinkedHashMap<>();
		inner.put("bar", new byte[0]);
		List<Object> list = new ArrayList<>();
		list.add(1);
		list.add(inner);
		Map<String, Object> outer = new LinkedHashMap<>();
		outer.put("foo", list);
		w.writeDictionary(outer);

		checkContents("70" + "41" + "03" + "666F6F" + "60" +
				"21" + "01" + "70" + "41" + "03" + "626172" + "51" + "00" +
				"80" + "80" + "80");
	}

	private void checkContents(String hex) throws IOException {
		out.flush();
		out.close();
		byte[] expected = StringUtils.fromHexString(hex);
		assertArrayEquals(StringUtils.toHexString(out.toByteArray()),
				expected, out.toByteArray());
	}
}
