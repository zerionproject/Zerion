package com.professor.zerion.android.vault.keepass;

import org.bouncycastle.crypto.engines.Salsa20Engine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import static com.professor.zerion.android.vault.keepass.KdbxFixtures.LABELS;
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
		return KeePassImport.map(document, LABELS);
	}

	private static void assertExpectedContent(KeePassImport.Result result) {
		List<KeePassImport.Password> p = result.passwords;
		assertEquals(9, p.size());
		assertEquals(4, result.skipped);
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

	private static KdbxException.Reason parseReason(byte[] file) {
		try {
			KdbxHeader.parse(file);
		} catch (KdbxException e) {
			return e.reason;
		}
		fail("expected the header to be refused");
		throw new AssertionError();
	}

	private static int headerFieldValue(byte[] file, int id) throws Exception {
		int headerLength = KdbxHeader.parse(file).headerLength;
		int pos = 12;
		while (pos < headerLength) {
			int fieldId = file[pos] & 0xFF;
			int size = KdbxBytes.i32(file, pos + 1);
			if (fieldId == id) return pos + 5;
			if (fieldId == 0) break;
			pos += 5 + size;
		}
		throw new AssertionError("field " + id + " not found");
	}

	private static int kdfValue(byte[] file, char name) throws Exception {
		int headerLength = KdbxHeader.parse(file).headerLength;
		byte[] item = {0x05, 0x01, 0x00, 0x00, 0x00, (byte) name, 0x08, 0x00,
				0x00, 0x00};
		int at = indexOf(Arrays.copyOf(file, headerLength), item);
		assertTrue(at > 0);
		return at + item.length;
	}

	private static void putU64(byte[] file, int at, long value) {
		for (int i = 0; i < 8; i++) {
			file[at + i] = (byte) (value >>> (8 * i));
		}
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
	public void version4Argon2Version10() throws Exception {
		assertExpectedContent(open(KdbxFixtures.V4_ARGON2D_V10, null));
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
	public void version4WithXmlVersion1KeyFile() throws Exception {
		assertExpectedContent(open(KdbxFixtures.V4_KEYFILE_XML1,
				KdbxFixtures.KEYFILE_XML1));
	}

	@Test
	public void version3AesKdfWithSalsa20() throws Exception {
		assertExpectedContent(open(KdbxFixtures.V3_AESKDF_SALSA20, null));
	}

	@Test
	public void version3WithChaCha20OuterCipher() throws Exception {
		assertExpectedContent(open(KdbxFixtures.V3_CHACHA20, null));
	}

	@Test
	public void simpleDatabaseSkipsNothing() throws Exception {
		KeePassImport.Result result = open(KdbxFixtures.V4_SIMPLE, null);
		assertEquals(2, result.passwords.size());
		assertEquals(0, result.skipped);
		assertPassword(result.passwords.get(0), "One", "u1", "pw-one",
				"https://one.example.org", "");
	}

	@Test
	public void documentKeepsRecycleBinAndHistoryCounts() throws Exception {
		KdbxReader.Document document = KdbxReader.read(
				load(KdbxFixtures.V4_ARGON2D), password(), null);
		assertEquals(13, document.entries.size());
		assertEquals(2, document.historyEntries);
		assertEquals(0, document.skippedEntries);
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
				reasonOf(load(KdbxFixtures.V3_CHACHA20), wrong.clone(), null));
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
		int seedAt = headerFieldValue(file, KdbxHeader.ID_MASTER_SEED);
		file[seedAt + 7] ^= 0x01;
		assertEquals(KdbxException.Reason.CORRUPTED,
				reasonOf(file, password(), null));
	}

	@Test
	public void unknownCipherIsUnsupported() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		int headerLength = KdbxHeader.parse(file).headerLength;
		int cipherAt = headerFieldValue(file, KdbxHeader.ID_CIPHER);
		file[cipherAt + 2] ^= 0x01;
		fixHeaderHash(file, headerLength);
		assertEquals(KdbxException.Reason.UNSUPPORTED,
				reasonOf(file, password(), null));
	}

	@Test
	public void trailingDataAfterTheLastBlockIsCorrupted() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		byte[] longer = Arrays.copyOf(file, file.length + 7);
		assertEquals(KdbxException.Reason.CORRUPTED,
				reasonOf(longer, password(), null));
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
	public void version3TruncatedFileIsRefused() throws Exception {
		byte[] file = load(KdbxFixtures.V3_AESKDF_SALSA20);
		KdbxException.Reason reason = reasonOf(
				Arrays.copyOf(file, file.length - 64), password(), null);
		assertTrue(reason == KdbxException.Reason.CORRUPTED
				|| reason == KdbxException.Reason.WRONG_KEY);
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
		new Random(7).nextBytes(junk);
		assertEquals(KdbxException.Reason.UNSUPPORTED,
				reasonOf(junk, password(), null));
		assertEquals(KdbxException.Reason.UNSUPPORTED,
				reasonOf(new byte[0], password(), null));
	}

	@Test
	public void argon2MemoryAboveTheCapIsRefusedBeforeWork() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		int headerLength = KdbxHeader.parse(file).headerLength;
		putU64(file, kdfValue(file, 'M'), 1L << 40);
		fixHeaderHash(file, headerLength);
		assertEquals(KdbxException.Reason.TOO_LARGE, parseReason(file));
		assertEquals(KdbxException.Reason.TOO_LARGE,
				reasonOf(file, password(), null));
	}

	@Test
	public void argon2MemoryAtTheCapIsAcceptedByTheHeader() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		int headerLength = KdbxHeader.parse(file).headerLength;
		putU64(file, kdfValue(file, 'M'), KdbxKdf.MAX_ARGON2_MEMORY);
		putU64(file, kdfValue(file, 'I'), 64);
		fixHeaderHash(file, headerLength);
		KdbxHeader header = KdbxHeader.parse(file);
		assertEquals(KdbxKdf.MAX_ARGON2_MEMORY, header.kdf.memoryBytes);
		assertEquals(64, header.kdf.iterations);
	}

	@Test
	public void argon2MemoryJustAboveTheCapIsRefused() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		int headerLength = KdbxHeader.parse(file).headerLength;
		putU64(file, kdfValue(file, 'M'), KdbxKdf.MAX_ARGON2_MEMORY + 1024);
		fixHeaderHash(file, headerLength);
		assertEquals(KdbxException.Reason.TOO_LARGE, parseReason(file));
	}

	@Test
	public void argon2WorkAboveTheCombinedCapIsRefused() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		int headerLength = KdbxHeader.parse(file).headerLength;
		putU64(file, kdfValue(file, 'M'), KdbxKdf.MAX_ARGON2_MEMORY);
		putU64(file, kdfValue(file, 'I'), 65);
		fixHeaderHash(file, headerLength);
		assertEquals(KdbxException.Reason.TOO_LARGE, parseReason(file));
	}

	@Test
	public void argon2IterationsAboveTheCapAreRefused() throws Exception {
		byte[] file = load(KdbxFixtures.V4_ARGON2D);
		int headerLength = KdbxHeader.parse(file).headerLength;
		putU64(file, kdfValue(file, 'I'), 1001);
		fixHeaderHash(file, headerLength);
		assertEquals(KdbxException.Reason.TOO_LARGE, parseReason(file));
	}

	@Test
	public void aesKdfRoundsAboveTheCapAreRefused() throws Exception {
		byte[] file = load(KdbxFixtures.V4_AESKDF);
		int headerLength = KdbxHeader.parse(file).headerLength;
		putU64(file, kdfValue(file, 'R'), KdbxKdf.MAX_AES_ROUNDS + 1);
		fixHeaderHash(file, headerLength);
		assertEquals(KdbxException.Reason.TOO_LARGE, parseReason(file));
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
		int seedAt = headerFieldValue(file, KdbxHeader.ID_MASTER_SEED) - 5;
		int endAt = headerFieldValue(file, KdbxHeader.ID_END) - 5;
		byte[] seedField = Arrays.copyOfRange(file, seedAt, seedAt + 5 + 32);
		byte[] patched = new byte[file.length + seedField.length];
		System.arraycopy(file, 0, patched, 0, endAt);
		System.arraycopy(seedField, 0, patched, endAt, seedField.length);
		System.arraycopy(file, endAt, patched, endAt + seedField.length,
				file.length - endAt);
		fixHeaderHash(patched, headerLength + seedField.length);
		assertEquals(KdbxException.Reason.CORRUPTED,
				reasonOf(patched, password(), null));
	}

	private static KdbxInnerStream salsa() throws KdbxException {
		return KdbxInnerStream.create(KdbxInnerStream.ID_SALSA20, new byte[32]);
	}

	private static KdbxReader.Document parse(String xml) throws KdbxException {
		return KdbxReader.parseXml(xml.getBytes(StandardCharsets.UTF_8),
				salsa());
	}

	private static KdbxException.Reason parseReasonOf(String xml) {
		try {
			parse(xml);
		} catch (KdbxException e) {
			return e.reason;
		}
		fail("accepted");
		throw new AssertionError();
	}

	@Test
	public void doctypeInsideTheDecryptedXmlIsRefused() throws Exception {
		String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
				+ "<!DOCTYPE KeePassFile [<!ENTITY x SYSTEM \"file:///etc/hostname\">]>\n"
				+ "<KeePassFile><Meta/><Root><Group><UUID>a</UUID><Name>Root</Name>"
				+ "<Entry><String><Key>Title</Key><Value>&x;</Value></String>"
				+ "</Entry></Group></Root></KeePassFile>";
		assertEquals(KdbxException.Reason.CORRUPTED, parseReasonOf(xml));
		String lower = xml.replace("<!DOCTYPE", "<!doctype").replace("<!ENTITY",
				"<!entity");
		assertEquals(KdbxException.Reason.CORRUPTED, parseReasonOf(lower));
	}

	@Test
	public void plainXmlWithoutProtectionParsesGroupsAndHistory()
			throws Exception {
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
		KdbxReader.Document document = parse(xml);
		assertEquals(3, document.entries.size());
		assertEquals(1, document.historyEntries);
		assertEquals("one", document.entries.get(0).string("Title"));
		assertTrue(document.entries.get(0).groupPath.isEmpty());
		assertTrue(document.entries.get(1).inRecycleBin);
		assertFalse(document.entries.get(2).inRecycleBin);
		assertEquals(Arrays.asList("A", "B"), document.entries.get(2).groupPath);
	}

	private static String protect(Salsa20Engine engine, byte[] plain) {
		byte[] out = new byte[plain.length];
		engine.processBytes(plain, 0, plain.length, out, 0);
		return Base64.getEncoder().encodeToString(out);
	}

	private static Salsa20Engine writerStream() throws Exception {
		byte[] key = MessageDigest.getInstance("SHA-256").digest(new byte[32]);
		Salsa20Engine engine = new Salsa20Engine();
		engine.init(true, new ParametersWithIV(new KeyParameter(key),
				new byte[] {(byte) 0xE8, 0x30, 0x09, 0x4B, (byte) 0x97, 0x20,
						0x5D, 0x2A}));
		return engine;
	}

	@Test
	public void protectedMetaBinaryConsumesTheStreamBeforeThePasswords()
			throws Exception {
		Salsa20Engine writer = writerStream();
		byte[] binary = new byte[20_000];
		new Random(3).nextBytes(binary);
		String binaryText = protect(writer, binary).replaceAll("(.{76})", "$1\n");
		String passwordText = protect(writer,
				"pw-after-binary".getBytes(StandardCharsets.UTF_8));
		String custom = protect(writer, "hidden".getBytes(StandardCharsets.UTF_8));
		String xml = "<KeePassFile><Meta><Binaries>"
				+ "<Binary ID=\"0\" Compressed=\"False\" Protected=\"True\">\n"
				+ binaryText + "\n</Binary></Binaries></Meta><Root>"
				+ "<Group><UUID>r</UUID><Name>Root</Name><Entry>"
				+ "<String><Key>Title</Key><Value>t</Value></String>"
				+ "<String><Key>Password</Key><Value Protected=\"True\">"
				+ passwordText + "</Value></String>"
				+ "<String><Key>Extra</Key><Value Protected=\"True\">" + custom
				+ "</Value></String>"
				+ "</Entry></Group></Root></KeePassFile>";
		KdbxReader.Document document = parse(xml);
		assertEquals(1, document.entries.size());
		assertEquals("pw-after-binary",
				document.entries.get(0).string(KdbxEntry.KEY_PASSWORD));
		assertEquals("hidden", document.entries.get(0).string("Extra"));
	}

	@Test
	public void protectedValueWithBadBase64IsCorrupted() throws Exception {
		String xml = "<KeePassFile><Meta/><Root><Group><UUID>r</UUID>"
				+ "<Name>Root</Name><Entry><String><Key>Password</Key>"
				+ "<Value Protected=\"True\">ab=cd</Value></String></Entry>"
				+ "</Group></Root></KeePassFile>";
		assertEquals(KdbxException.Reason.CORRUPTED, parseReasonOf(xml));
		String odd = xml.replace("ab=cd", "abcde");
		assertEquals(KdbxException.Reason.CORRUPTED, parseReasonOf(odd));
	}

	@Test
	public void oversizedValueIsCutAndTheEntryKept() throws Exception {
		StringBuilder big = new StringBuilder();
		for (int i = 0; i < KdbxDocumentHandler.MAX_VALUE_CHARS + 100; i++) {
			big.append('n');
		}
		String xml = "<KeePassFile><Meta/><Root><Group><UUID>r</UUID>"
				+ "<Name>Root</Name><Entry><String><Key>Title</Key>"
				+ "<Value>big</Value></String><String><Key>Notes</Key><Value>"
				+ big + "</Value></String></Entry></Group></Root></KeePassFile>";
		KdbxReader.Document document = parse(xml);
		assertEquals(1, document.entries.size());
		assertEquals(KdbxDocumentHandler.MAX_VALUE_CHARS,
				document.entries.get(0).string(KdbxEntry.KEY_NOTES).length());
	}

	@Test
	public void entryWithTooManyStringsIsSkippedNotFatal() throws Exception {
		StringBuilder strings = new StringBuilder();
		for (int i = 0; i <= KdbxDocumentHandler.MAX_STRINGS_PER_ENTRY; i++) {
			strings.append("<String><Key>k").append(i)
					.append("</Key><Value>v</Value></String>");
		}
		String xml = "<KeePassFile><Meta/><Root><Group><UUID>r</UUID>"
				+ "<Name>Root</Name><Entry>" + strings + "</Entry>"
				+ "<Entry><String><Key>Title</Key><Value>ok</Value></String>"
				+ "</Entry></Group></Root></KeePassFile>";
		KdbxReader.Document document = parse(xml);
		assertEquals(1, document.entries.size());
		assertEquals("ok", document.entries.get(0).string("Title"));
		assertEquals(1, document.skippedEntries);
		assertEquals(1, KeePassImport.map(document, LABELS).skipped);
	}

	@Test
	public void storedHeaderHashIsCheckedWhenPresent() throws Exception {
		byte[] expected = new byte[32];
		Arrays.fill(expected, (byte) 7);
		String good = Base64.getEncoder().encodeToString(expected);
		String xml = "<KeePassFile><Meta><HeaderHash>%s</HeaderHash></Meta>"
				+ "<Root><Group><UUID>r</UUID><Name>Root</Name></Group></Root>"
				+ "</KeePassFile>";
		KdbxReader.parseXml(KdbxStreams.bytes(String.format(xml, good)
				.getBytes(StandardCharsets.UTF_8), 0), salsa(), expected);
		byte[] other = expected.clone();
		other[0] ^= 1;
		try {
			KdbxReader.parseXml(KdbxStreams.bytes(String.format(xml,
					Base64.getEncoder().encodeToString(other))
					.getBytes(StandardCharsets.UTF_8), 0), salsa(), expected);
			fail("accepted");
		} catch (KdbxException e) {
			assertEquals(KdbxException.Reason.CORRUPTED, e.reason);
		}
		try {
			KdbxReader.checkHeaderHash("not base64!", expected);
			fail("accepted");
		} catch (KdbxException e) {
			assertEquals(KdbxException.Reason.CORRUPTED, e.reason);
		}
	}

	@Test
	public void unknownRootElementIsCorrupted() throws Exception {
		assertEquals(KdbxException.Reason.CORRUPTED, parseReasonOf("<Other/>"));
	}

	@Test
	public void oversizedFileIsRefusedWithoutParsing() throws Exception {
		byte[] big = new byte[KdbxReader.MAX_FILE_BYTES + 1];
		assertEquals(KdbxException.Reason.TOO_LARGE,
				reasonOf(big, password(), null));
	}
}
