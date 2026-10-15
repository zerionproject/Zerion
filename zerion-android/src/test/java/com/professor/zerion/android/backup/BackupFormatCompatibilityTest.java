package com.professor.zerion.android.backup;

import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.crypto.VaultCrypto;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static com.professor.zerion.android.backup.BackupBundleParserTest.EARLIER_BUNDLE;
import static com.professor.zerion.android.backup.BackupBundleParserTest.hex;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BackupFormatCompatibilityTest {

	static final String EARLIER_SEALED = "5a424b3102002dabf4b4acee53e0"
			+ "e140642d5c00805dd676154edcb0144e24620359daabaee400020000000000"
			+ "080000000100000020b8042b200960d58ab0e74637485e2af2bdfad27d86ea"
			+ "31b61c99c198cb274cbe0000009b0000000cde0e1887793126623bbbeb390d"
			+ "79be25b9185baf5b9a9b4764749b63a1fb0722ada9cab7b1fe58f237b10563"
			+ "548ec6ef2e5604c7a1129e159b6b4c07153e36d09fee8de8337241147a570e"
			+ "498b18f39820a75ba46e3802716fae95e6081d1e44cb536e29c91b91ec9dd3"
			+ "3052bddcf85366a9bf47b03e2cf64eb43ab4ea516ca454c7c65190ca13ba32"
			+ "36418b4d17f55ad0c70d4e0237a8";

	private static final int HEADER = 4 + 2 + 32 + 16;
	private static final Argon2.Argon2Params CHEAP =
			new Argon2.Argon2Params(Argon2.MIN_MEMORY_KB, 1, 1,
					Argon2.DEFAULT_HASH_LENGTH);
	private static final Argon2.Argon2Params SMALLEST_ACCEPTED =
			new Argon2.Argon2Params(Argon2.LOW_MEMORY_KB, 1, 1,
					Argon2.DEFAULT_HASH_LENGTH);

	@Test
	public void aFileSealedByTheEarlierCodeStillOpens() throws Exception {
		BackupCrypto.Opened opened = new BackupCrypto().open(
				hex(EARLIER_SEALED), "fixture passphrase".toCharArray());
		assertArrayEquals(hex(EARLIER_BUNDLE), opened.bundle);
		assertEquals("Old Backup é",
				BackupBundle.fromBytes(opened.bundle).displayName);
	}

	@Test
	public void aNewFileOpensTheWayTheEarlierCodeOpenedIt() {
		byte[] bundle = "a bundle".getBytes(StandardCharsets.UTF_8);
		char[] pass = "passphrase".toCharArray();
		byte[] file = new BackupCrypto().seal(bundle, pass, (byte) 0, CHEAP);

		ByteBuffer buf = ByteBuffer.wrap(file);
		byte[] header = new byte[HEADER];
		buf.get(header);
		assertEquals(2, header[4]);
		byte[] salt = Arrays.copyOfRange(header, 6, 38);
		byte[] params = Arrays.copyOfRange(header, 38, 54);
		byte[] mac = new byte[32];
		buf.get(mac);
		byte[] sealed = new byte[buf.getInt()];
		buf.get(sealed);
		assertEquals(0, buf.remaining());

		VaultCrypto crypto = new VaultCrypto();
		byte[] master = new Argon2().deriveKey(pass, salt,
				Argon2.Argon2Params.fromBytes(params));
		byte[] encKey = crypto.hkdfSha256(master, salt,
				"Zerion-Account-Backup-ZBK2-enc", 32);
		byte[] verifyKey = crypto.hkdfSha256(master, salt,
				"Zerion-Account-Backup-ZBK2-verify", 32);
		assertTrue(crypto.verifyPasswordMac(verifyKey, mac));
		assertArrayEquals(bundle, crypto.decrypt(
				VaultCrypto.EncryptedData.fromBytes(sealed), encKey, header));
	}

	@Test
	public void aVersionOneFileStillOpens() throws Exception {
		byte[] bundle = "a version one bundle".getBytes(StandardCharsets.UTF_8);
		char[] pass = "passphrase".toCharArray();
		Argon2 argon2 = new Argon2();
		VaultCrypto crypto = new VaultCrypto();
		byte[] salt = argon2.generateSalt();
		byte flags = 0;
		ByteBuffer header = ByteBuffer.allocate(HEADER);
		header.put(new byte[] {'Z', 'B', 'K', '1'});
		header.put((byte) 1);
		header.put(flags);
		header.put(salt);
		header.put(SMALLEST_ACCEPTED.toBytes());
		byte[] master = argon2.deriveKey(pass, salt, SMALLEST_ACCEPTED);
		byte[] aadBase =
				"Zerion-Account-Backup-ZBK1".getBytes(StandardCharsets.UTF_8);
		byte[] aad = Arrays.copyOf(aadBase, aadBase.length + 2);
		aad[aadBase.length] = 1;
		aad[aadBase.length + 1] = flags;
		byte[] mac = crypto.computePasswordVerificationMac(master);
		byte[] sealed = crypto.encrypt(bundle, master, aad).toBytes();
		ByteBuffer file = ByteBuffer.allocate(HEADER + 32 + 4 + sealed.length);
		file.put(header.array());
		file.put(mac);
		file.putInt(sealed.length);
		file.put(sealed);

		BackupCrypto.Opened opened = new BackupCrypto().open(file.array(), pass);
		assertArrayEquals(bundle, opened.bundle);
	}

	@Test
	public void sealingWritesTheCiphertextStraightIntoTheFile() {
		ThreadAllocations allocations = ThreadAllocations.require();
		int size = 8 * 1024 * 1024;
		byte[] bundle = new byte[size];
		new Random(1).nextBytes(bundle);
		BackupCrypto crypto = new BackupCrypto();
		crypto.seal(new byte[16], "p".toCharArray(), (byte) 0, CHEAP);
		long before = allocations.now();
		byte[] file = crypto.seal(bundle, "p".toCharArray(), (byte) 0, CHEAP);
		long used = allocations.now() - before;
		assertTrue(file.length > size);
		assertTrue("sealing " + size + " bytes allocated " + used,
				used < 2L * size);
	}

	@Test
	public void openingDecryptsStraightFromTheFile() throws Exception {
		ThreadAllocations allocations = ThreadAllocations.require();
		int size = 8 * 1024 * 1024;
		byte[] bundle = new byte[size];
		new Random(2).nextBytes(bundle);
		BackupCrypto crypto = new BackupCrypto();
		char[] pass = "p".toCharArray();
		byte[] small = crypto.seal(new byte[16], pass, (byte) 0,
				SMALLEST_ACCEPTED);
		byte[] large = crypto.seal(bundle, pass, (byte) 0, SMALLEST_ACCEPTED);
		crypto.open(small, pass);
		long before = allocations.now();
		crypto.open(small, pass);
		long baseline = allocations.now() - before;
		before = allocations.now();
		BackupCrypto.Opened opened = crypto.open(large, pass);
		long used = allocations.now() - before - baseline;
		assertArrayEquals(bundle, opened.bundle);
		assertTrue("opening " + size + " bytes allocated " + used,
				used < 2L * size);
	}
}
