package com.professor.zerion.android.vault.keepass;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import static com.professor.zerion.android.vault.keepass.KdbxFixtures.UNTITLED;
import static com.professor.zerion.android.vault.keepass.KdbxFixtures.fixHeaderHash;
import static com.professor.zerion.android.vault.keepass.KdbxFixtures.indexOf;
import static com.professor.zerion.android.vault.keepass.KdbxFixtures.load;
import static com.professor.zerion.android.vault.keepass.KdbxFixtures.password;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KdbxReaderTest {

	private static final String DB01_NOTES = "Folder: Work / Servers\n"
			+ "Postgres\n"
			+ "Port: 5432\n"
			+ "API Key: secret-api-key\n"
			+ "otp: otpauth://totp/db01?secret=JBSWY3DPEHPK3PXP";

	private static KeePassImport.Result open(String fixture,
			@Nullable String keyFile) throws Exception {
		byte[] file = load(fixture);
		byte[] key = keyFile == null ? null : load(keyFile);
		KdbxReader.Document document = KdbxReader.read(file, password(), key);
		return KeePassImport.map(document, UNTITLED);
	}

	private static void assertExpectedContent(KeePassImport.Result result) {
		List<KeePassImport.Password> p = result.passwords;
		assertEquals(9, p.size());
		assertEquals(6, result.skipped);
		assertPassword(p.get(0), "Email", "alice", "pw-email-1",
				"https://mail.example.org", "Main mailbox");
		assertPassword(p.get(1), "Jira", "bob", "pw-jira",
				"https://jira.example.com/", "Folder: Work");
		assertPassword(p.get(2), "db01", "root", "pw-db01", "", DB01_NOTES);
		assertPassword(p.get(3), "Dup", "dup", "pw-dup",
				"https://dup.example.com", "Folder: Work\nsame");
		assertPassword(p.get(4), "Bank", "alice", "pw-bank-3",
				"https://bank.example.org", "");
		assertPassword(p.get(5), "After History", "carol", "pw-after", "", "");
		assertPassword(p.get(6), "With file", "frank", "pw-file", "", "");
		assertPassword(p.get(7), "login.example.net", "dave", "pw-notitle",
				"https://login.example.net/path?x=1", "");
		assertPassword(p.get(8), UNTITLED, "eve", "pw-untitled", "", "");
	}

	private static void assertPassword(KeePassImport.Password actual,
			String title, String username, String password, String url,
			String notes) {
		assertEquals(title, actual.title);
		assertEquals(username, actual.username);
		assertEquals(password, actual.password);
		assertEquals(url, actual.url);
		assertEquals(notes, actual.notes);
	}

	private static KdbxException.Reason reasonOf(byte[] file,
			@Nullable char[] password, @Nullable byte[] keyFile) {
		try {
			KdbxReader.read(file, password, keyFile);
		} catch (KdbxException e) {
			return e.reason;
		}
		fail("expected the file to be refused");
		throw new AssertionError();
	}

	@Test
	public void version4Argon2dAesWithChaCha20InnerStream() throws Exception {
		assertExpectedContent(open(KdbxFixtures.V4_ARGON2D, null));
	}

	@Test
	public void version4Argon2idWithChaCha20OuterCipher() throws Exception {
		assertExpectedContent(open(KdbxFixtures.V4_ARGON2ID_CHACHA20, null));
	}

	@Test
	public void version4AesKdf() throws Exception {
		assertExpectedContent(open(KdbxFixtures.V4_AESKDF, null));
	}

	@Test
	public void version4Twofish() throws Exception {
		assertExpectedContent(open(KdbxFixtures.V4_TWOFISH, null));
	}

	@Test
	public void version4WithoutCompression() throws Exception {
		assertExpectedContent(open(KdbxFixtures.V4_NOCOMPRESS, null));
	}

	@Test
	public void version4WithRawKeyFile() throws Exception {
		assertExpectedContent(open(KdbxFixtures.V4_KEYFILE_RAW,
				KdbxFixtures.KEYFILE_RAW));
	}

	@Test
	public void version4WithXmlVersion2KeyFile() throws Exception {
		assertExpectedContent(open(KdbxFixtures.V4_KEYFILE_XML2,
				KdbxFixtures.KEYFILE_XML2));
	}

	@Test
	public void version3AesKdfWithSalsa20() throws Exception {
		assertExpectedContent(open(KdbxFixtures.V3_AESKDF_SALSA20, null));
	}

	@Test
	public void documentKeepsRecycleBinAndHistoryCounts() throws Exception {
		KdbxReader.Document document = KdbxReader.read(
				load(KdbxFixtures.V4_ARGON2D), password(), null);
		assertEquals(13, document.entries.size());
		assertEquals(2, document.historyEntries);
		int inBin = 0;
		int withBinary = 0;
		for (KdbxEntry entry : document.entries) {
			if (entry.inRecycleBin) {
				inBin++;
				assertEquals("Deleted thing", entry.string(KdbxEntry.KEY_TITLE));
				assertEquals("pw-deleted", entry.string(KdbxEntry.KEY_PASSWORD));
			}
			if (entry.hasBinary) withBinary++;
		}
		assertEquals(1, inBin);
		assertEquals(2, withBinary);
	}

	@Test
	public void wrongPasswordIsRefusedAsWrongKey() throws Exception {
		char[] wrong = "wrong horse".toCharArray();
		assertEquals(KdbxException.Reason.WRONG_KEY,
				reasonOf(load(KdbxFixtures.V4_ARGON2D), wrong.clone(), null));
		assertEquals(KdbxException.Reason.WRONG_KEY,
				reasonOf(load(KdbxFixtures.V3_AESKDF_SALSA20), wrong.clone(),
						null));
		assertEquals(KdbxException.Reason.WRONG_KEY,
				reasonOf(load(KdbxFixtures.V4_ARGON2ID_CHACHA20), wrong.clone(),
						null));
	}

	@Test
	public void missingOrWrongKeyFileIsRefusedAsWrongKey() throws Exception {
		assertEquals(KdbxException.Reason.WRONG_KEY,
				reasonOf(load(KdbxFixtures.V4_KEYFILE_RAW), password(), null));
		assertEquals(KdbxException.Reason.WRONG_KEY,
				reasonOf(load(KdbxFixtures.V4_KEYFILE_XML2), password(),
						load(KdbxFixtures.KEYFILE_RAW)));
	}

	@Test
	public void noCredentialsIsRefused() throws Exception {
		assertEquals(KdbxException.Reason.WRONG_KEY,
				reasonOf(load(KdbxFixtures.V4_ARGON2D), null, null));
		assertEquals(KdbxException.Reason.WRONG_KEY,
				reasonOf(load(KdbxFixtures.V4_ARGON2D), new char[0], new byte[0]));
	}

	@Test
	public void passwordBufferIsNotModifiedByTheReader() throws Exception {
		char[] password = password();
		KdbxReader.read(load(KdbxFixtures.V4_AESKDF), password, null);
		assertTrue(Arrays.equals(KdbxFixtures.PASSWORD, password));
	}

	@Test
	public void flippedByteInAnHmacBlockIsCorrupted() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		int headerLength = KdbxHeader.parse(file).headerLength;
		int firstBlockData = headerLength + 64 + 36;
		file[firstBlockData + 5] ^= 0x01;
		assertEquals(KdbxException.Reason.CORRUPTED,
				reasonOf(file, password(), null));
	}

	@Test
	public void flippedByteInTheHeaderHashIsCorrupted() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		int headerLength = KdbxHeader.parse(file).headerLength;
		file[headerLength + 3] ^= 0x01;
		assertEquals(KdbxException.Reason.CORRUPTED,
				reasonOf(file, password(), null));
	}

	@Test
	public void flippedByteInTheHeaderItselfIsCorrupted() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		int headerLength = KdbxHeader.parse(file).headerLength;
		int pos = 12;
		int seedAt = -1;
		while (pos < headerLength) {
			int id = file[pos] & 0xFF;
			int size = KdbxBytes.i32(file, pos + 1);
			if (id == KdbxHeader.ID_MASTER_SEED) seedAt = pos + 5;
			if (id == 0) break;
			pos += 5 + size;
		}
		assertTrue(seedAt > 0);
		file[seedAt + 7] ^= 0x01;
		assertEquals(KdbxException.Reason.CORRUPTED,
				reasonOf(file, password(), null));
	}

	@Test
	public void unknownCipherIsUnsupported() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		int headerLength = KdbxHeader.parse(file).headerLength;
		int pos = 12;
		int cipherAt = -1;
		while (pos < headerLength) {
			int id = file[pos] & 0xFF;
			int size = KdbxBytes.i32(file, pos + 1);
			if (id == KdbxHeader.ID_CIPHER) cipherAt = pos + 5;
			if (id == 0) break;
			pos += 5 + size;
		}
		assertTrue(cipherAt > 0);
		file[cipherAt + 2] ^= 0x01;
		fixHeaderHash(file, headerLength);
		assertEquals(KdbxException.Reason.UNSUPPORTED,
				reasonOf(file, password(), null));
	}

	@Test
	public void version3BlockHashMismatchIsCorrupted() throws Exception {
		byte[] file = load(KdbxFixtures.V3_AESKDF_SALSA20);
		file[file.length - 40] ^= 0x01;
		KdbxException.Reason reason = reasonOf(file, password(), null);
		assertTrue(reason == KdbxException.Reason.CORRUPTED
				|| reason == KdbxException.Reason.WRONG_KEY);
	}

	@Test
	public void truncatedFileIsCorrupted() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		assertEquals(KdbxException.Reason.CORRUPTED,
				reasonOf(Arrays.copyOf(file, file.length - 100), password(),
						null));
		assertEquals(KdbxException.Reason.CORRUPTED,
				reasonOf(Arrays.copyOf(file, 40), password(), null));
	}

	@Test
	public void kdb1SignatureIsUnsupported() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		file[4] = 0x65;
		file[5] = (byte) 0xFB;
		file[6] = 0x4B;
		file[7] = (byte) 0xB5;
		assertEquals(KdbxException.Reason.UNSUPPORTED,
				reasonOf(file, password(), null));
	}

	@Test
	public void unknownVersionAndRandomBytesAreUnsupported() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		file[10] = 5;
		assertEquals(KdbxException.Reason.UNSUPPORTED,
				reasonOf(file, password(), null));
		byte[] junk = new byte[1000];
		new java.util.Random(7).nextBytes(junk);
		assertEquals(KdbxException.Reason.UNSUPPORTED,
				reasonOf(junk, password(), null));
		assertEquals(KdbxException.Reason.UNSUPPORTED,
				reasonOf(new byte[0], password(), null));
	}

	@Test
	public void argon2MemoryAboveTheCapIsRefusedBeforeWork() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		int headerLength = KdbxHeader.parse(file).headerLength;
		byte[] memoryItem = {0x05, 0x01, 0x00, 0x00, 0x00, 'M', 0x08, 0x00,
				0x00, 0x00};
		int at = indexOf(Arrays.copyOf(file, headerLength), memoryItem);
		assertTrue(at > 0);
		int value = at + memoryItem.length;
		long huge = 1L << 40;
		for (int i = 0; i < 8; i++) {
			file[value + i] = (byte) (huge >>> (8 * i));
		}
		fixHeaderHash(file, headerLength);
		long started = System.nanoTime();
		assertEquals(KdbxException.Reason.TOO_LARGE,
				reasonOf(file, password(), null));
		assertTrue((System.nanoTime() - started) < 2_000_000_000L);
	}

	@Test
	public void argon2IterationsAboveTheCapAreRefused() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		int headerLength = KdbxHeader.parse(file).headerLength;
		byte[] item = {0x05, 0x01, 0x00, 0x00, 0x00, 'I', 0x08, 0x00, 0x00,
				0x00};
		int at = indexOf(Arrays.copyOf(file, headerLength), item);
		assertTrue(at > 0);
		file[at + item.length] = (byte) 0xE9;
		file[at + item.length + 1] = 0x03;
		fixHeaderHash(file, headerLength);
		assertEquals(KdbxException.Reason.TOO_LARGE,
				reasonOf(file, password(), null));
	}

	@Test
	public void aesKdfRoundsAboveTheCapAreRefused() throws Exception {
		byte[] file = load(KdbxFixtures.V4_AESKDF);
		int headerLength = KdbxHeader.parse(file).headerLength;
		byte[] item = {0x05, 0x01, 0x00, 0x00, 0x00, 'R', 0x08, 0x00, 0x00,
				0x00};
		int at = indexOf(Arrays.copyOf(file, headerLength), item);
		assertTrue(at > 0);
		long rounds = KdbxKdf.MAX_AES_ROUNDS + 1;
		for (int i = 0; i < 8; i++) {
			file[at + item.length + i] = (byte) (rounds >>> (8 * i));
		}
		fixHeaderHash(file, headerLength);
		assertEquals(KdbxException.Reason.TOO_LARGE,
				reasonOf(file, password(), null));
	}

	@Test
	public void arcFourInnerStreamIsUnsupported() throws Exception {
		byte[] file = load(KdbxFixtures.V3_AESKDF_SALSA20);
		int headerLength = KdbxHeader.parse(file).headerLength;
		byte[] field = {0x0A, 0x04, 0x00, 0x02, 0x00, 0x00, 0x00};
		int at = indexOf(Arrays.copyOf(file, headerLength), field);
		assertTrue(at > 0);
		file[at + 3] = 0x01;
		assertEquals(KdbxException.Reason.UNSUPPORTED,
				reasonOf(file, password(), null));
		file[at + 3] = 0x00;
		assertEquals(KdbxException.Reason.UNSUPPORTED,
				reasonOf(file, password(), null));
	}

	@Test
	public void variantDictionaryWithBadVersionIsRefused() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		int headerLength = KdbxHeader.parse(file).headerLength;
		byte[] start = {0x00, 0x01, 0x42, 0x05, 0x00, 0x00, 0x00, '$', 'U',
				'U', 'I', 'D'};
		int at = indexOf(Arrays.copyOf(file, headerLength), start);
		assertTrue(at > 0);
		file[at + 1] = 0x02;
		fixHeaderHash(file, headerLength);
		assertEquals(KdbxException.Reason.UNSUPPORTED,
				reasonOf(file, password(), null));
	}

	@Test
	public void duplicateHeaderFieldIsCorrupted() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		int headerLength = KdbxHeader.parse(file).headerLength;
		byte[] header = Arrays.copyOf(file, headerLength);
		int second = -1;
		int pos = 12;
		int seedAt = -1;
		while (pos < header.length) {
			int id = header[pos] & 0xFF;
			int size = KdbxBytes.i32(header, pos + 1);
			if (id == KdbxHeader.ID_MASTER_SEED) seedAt = pos;
			if (id == 0) {
				second = pos;
				break;
			}
			pos += 5 + size;
		}
		assertTrue(seedAt >= 0 && second > 0);
		byte[] seedField = Arrays.copyOfRange(header, seedAt, seedAt + 5 + 32);
		byte[] patched = new byte[file.length + seedField.length];
		System.arraycopy(header, 0, patched, 0, second);
		System.arraycopy(seedField, 0, patched, second, seedField.length);
		System.arraycopy(file, second, patched, second + seedField.length,
				file.length - second);
		fixHeaderHash(patched, headerLength + seedField.length);
		assertEquals(KdbxException.Reason.CORRUPTED,
				reasonOf(patched, password(), null));
	}

	@Test
	public void doctypeInsideTheDecryptedXmlIsRefused() throws Exception {
		KdbxInnerStream inner = KdbxInnerStream.create(
				KdbxInnerStream.ID_SALSA20, new byte[32]);
		String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
				+ "<!DOCTYPE KeePassFile [<!ENTITY x SYSTEM \"file:///etc/hostname\">]>\n"
				+ "<KeePassFile><Meta/><Root><Group><UUID>a</UUID><Name>Root</Name>"
				+ "<Entry><String><Key>Title</Key><Value>&x;</Value></String>"
				+ "</Entry></Group></Root></KeePassFile>";
		try {
			KdbxReader.parseXml(xml.getBytes(StandardCharsets.UTF_8), inner);
			fail("doctype accepted");
		} catch (KdbxException e) {
			assertEquals(KdbxException.Reason.CORRUPTED, e.reason);
		}
		String lower = xml.replace("<!DOCTYPE", "<!doctype").replace("<!ENTITY",
				"<!entity");
		try {
			KdbxReader.parseXml(lower.getBytes(StandardCharsets.UTF_8), inner);
			fail("lowercase doctype accepted");
		} catch (KdbxException e) {
			assertEquals(KdbxException.Reason.CORRUPTED, e.reason);
		}
	}

	@Test
	public void plainXmlWithoutProtectionParsesGroupsAndHistory()
			throws Exception {
		KdbxInnerStream inner = KdbxInnerStream.create(
				KdbxInnerStream.ID_CHACHA20, new byte[64]);
		String xml = "<KeePassFile><Meta><RecycleBinEnabled>True</RecycleBinEnabled>"
				+ "<RecycleBinUUID>bin</RecycleBinUUID></Meta><Root>"
				+ "<Group><UUID>root</UUID><Name>Root</Name>"
				+ "<Entry><String><Key>Title</Key><Value>one</Value></String>"
				+ "<History><Entry><String><Key>Title</Key><Value>old</Value>"
				+ "</String></Entry></History></Entry>"
				+ "<Group><UUID>bin</UUID><Name>Recycle Bin</Name>"
				+ "<Entry><String><Key>Title</Key><Value>gone</Value></String>"
				+ "</Entry></Group>"
				+ "<Group><UUID>a</UUID><Name>A</Name><Group><UUID>b</UUID>"
				+ "<Name>B</Name><Entry><String><Key>Title</Key><Value>deep</Value>"
				+ "</String></Entry></Group></Group>"
				+ "</Group></Root></KeePassFile>";
		KdbxReader.Document document = KdbxReader.parseXml(
				xml.getBytes(StandardCharsets.UTF_8), inner);
		assertEquals(3, document.entries.size());
		assertEquals(1, document.historyEntries);
		assertEquals("one", document.entries.get(0).string("Title"));
		assertTrue(document.entries.get(0).groupPath.isEmpty());
		assertTrue(document.entries.get(1).inRecycleBin);
		assertFalse(document.entries.get(2).inRecycleBin);
		assertEquals(Arrays.asList("A", "B"), document.entries.get(2).groupPath);
	}

	@Test
	public void unknownRootElementIsCorrupted() throws Exception {
		KdbxInnerStream inner = KdbxInnerStream.create(
				KdbxInnerStream.ID_CHACHA20, new byte[64]);
		try {
			KdbxReader.parseXml("<Other/>".getBytes(StandardCharsets.UTF_8),
					inner);
			fail("accepted");
		} catch (KdbxException e) {
			assertEquals(KdbxException.Reason.CORRUPTED, e.reason);
		}
	}

	@Test
	public void oversizedFileIsRefusedWithoutParsing() throws Exception {
		byte[] big = new byte[KdbxReader.MAX_FILE_BYTES + 1];
		assertEquals(KdbxException.Reason.TOO_LARGE,
				reasonOf(big, password(), null));
	}
}
