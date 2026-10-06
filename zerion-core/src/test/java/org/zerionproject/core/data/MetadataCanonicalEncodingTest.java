package org.zerionproject.core.data;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.test.BrambleTestCase;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class MetadataCanonicalEncodingTest extends BrambleTestCase {

	private final MetadataEncoder encoder =
			new MetadataEncoderImpl(new BdfWriterFactoryImpl());
	private final MetadataParser parser =
			new MetadataParserImpl(new BdfReaderFactoryImpl());

	private static List<Object> corpus() {
		List<Object> values = new ArrayList<>();
		values.add(Boolean.TRUE);
		values.add(Boolean.FALSE);
		values.add(0L);
		values.add(1L);
		values.add(-1L);
		values.add(127L);
		values.add(128L);
		values.add(256L);
		values.add(65536L);
		values.add(1L << 32);
		values.add(Long.MIN_VALUE);
		values.add(Long.MAX_VALUE);
		values.add(0.0);
		values.add(-0.0);
		values.add(1.5);
		values.add("");
		values.add("type");
		values.add("type ");
		values.add("type\0");
		values.add("\0");
		values.add(new byte[0]);
		values.add(new byte[] {0});
		values.add(new byte[] {0, 0, 0, 0});
		values.add(new byte[] {0x21, 0x00});
		values.add(new byte[] {0x21, 0x20});
		values.add(new byte[256]);
		values.add(new byte[] {1, 2, 3, 0});
		values.add(BdfList.of());
		values.add(BdfList.of(0L));
		values.add(BdfList.of("a", new byte[] {0}));
		values.add(new BdfDictionary());
		values.add(BdfDictionary.of(new BdfEntry("k", new byte[] {0})));
		return values;
	}

	@Test
	public void appendingZeroBytesToAnyCanonicalValueMakesItUnparseable()
			throws Exception {
		for (Object value : corpus()) {
			byte[] encoded = encode(value);
			assertArrayEquals(encoded, encode(value));
			for (int zeros = 1; zeros <= 4; zeros++) {
				byte[] padded = Arrays.copyOf(encoded, encoded.length + zeros);
				Metadata m = new Metadata();
				m.put("type", padded);
				try {
					parser.parse(m);
					fail("a value padded with " + zeros
							+ " zero bytes parsed: " + describe(value));
				} catch (FormatException expected) {
				}
			}
		}
	}

	@Test
	public void noCanonicalValueIsAnotherOneWithZeroBytesAppended()
			throws Exception {
		List<Object> values = corpus();
		List<byte[]> encodings = new ArrayList<>();
		for (Object v : values) encodings.add(encode(v));
		for (int i = 0; i < encodings.size(); i++) {
			for (int j = 0; j < encodings.size(); j++) {
				if (i == j) continue;
				byte[] shorter = encodings.get(i);
				byte[] longer = encodings.get(j);
				if (longer.length <= shorter.length) continue;
				if (!Arrays.equals(shorter,
						Arrays.copyOf(longer, shorter.length))) {
					continue;
				}
				boolean onlyZeros = true;
				for (int k = shorter.length; k < longer.length; k++) {
					if (longer[k] != 0) onlyZeros = false;
				}
				assertFalse(describe(values.get(j)) + " is "
						+ describe(values.get(i)) + " with zero bytes appended",
						onlyZeros);
			}
		}
	}

	@Test
	public void aStringValueWithTrailingSpaceOrNulIsADifferentValue()
			throws Exception {
		byte[] plain = encode("type");
		byte[] space = encode("type ");
		byte[] nul = encode("type\0");
		assertFalse(Arrays.equals(plain, space));
		assertFalse(Arrays.equals(plain, nul));
		assertFalse(Arrays.equals(space, nul));
		assertEquals(plain.length + 1, space.length);
		assertEquals(plain.length + 1, nul.length);
		assertFalse(plain[0] == space[0] && plain[1] == space[1]
				&& Arrays.equals(plain, Arrays.copyOf(space, plain.length)));
	}

	@Test
	public void metadataKeyConstantsCarryNoPadding() throws Exception {
		String[] holders = {
				"org.zerionproject.core.api.client.ContactGroupConstants",
				"org.zerionproject.core.api.properties.TransportPropertyConstants",
				"org.zerionproject.core.versioning.ClientVersioningConstants",
				"org.zerionproject.core.rendezvous.RendezvousConstants",
				"org.zerionproject.core.transport.agreement.TransportKeyAgreementConstants",
				"org.zerionproject.core.db.DatabaseConstants"};
		int checked = 0;
		for (String name : holders) {
			Class<?> c = Class.forName(name);
			for (Field f : c.getDeclaredFields()) {
				if (f.getType() != String.class) continue;
				if (!Modifier.isStatic(f.getModifiers())) continue;
				if (!f.getName().contains("KEY")) continue;
				f.setAccessible(true);
				String key = (String) f.get(null);
				assertEquals(name + "." + f.getName(), key.trim(), key);
				for (char ch : key.toCharArray()) {
					assertFalse(name + "." + f.getName()
							+ " holds a control character",
							Character.isISOControl(ch));
				}
				checked++;
			}
		}
		assertFalse("no key constants were found", checked == 0);
	}

	private byte[] encode(Object value) throws FormatException {
		BdfDictionary d = new BdfDictionary();
		d.put("type", value);
		Metadata m = encoder.encode(d);
		byte[] encoded = m.get("type");
		if (encoded == null) throw new AssertionError(describe(value));
		return encoded;
	}

	private static String describe(Object value) {
		if (value instanceof byte[]) return Arrays.toString((byte[]) value);
		return String.valueOf(value);
	}
}
