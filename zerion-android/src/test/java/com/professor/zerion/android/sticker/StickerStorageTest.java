package com.professor.zerion.android.sticker;

import com.professor.zerion.android.security.MemorySharedPreferences;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StickerStorageTest {

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private final MemorySharedPreferences prefs =
			new MemorySharedPreferences();

	@Nullable
	private SecretKey legacyKey = null;

	private static byte[] png(int seed) {
		byte[] b = new byte[300];
		for (int i = 0; i < b.length; i++) b[i] = (byte) (seed + i);
		return b;
	}

	private StickerStorage storage() throws IOException {
		return new StickerStorage(tmp.newFolder("stickers"), prefs,
				() -> legacyKey);
	}

	private StickerStorage storage(File dir) {
		return new StickerStorage(dir, prefs, () -> legacyKey);
	}

	private static SecretKey newKey() throws Exception {
		KeyGenerator gen = KeyGenerator.getInstance("AES");
		gen.init(256);
		return gen.generateKey();
	}

	private static void writeLegacy(File dir, String id, SecretKey key,
			byte[] png) throws Exception {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, key);
		byte[] nonce = cipher.getIV();
		byte[] ct = cipher.doFinal(png);
		byte[] all = new byte[nonce.length + ct.length];
		System.arraycopy(nonce, 0, all, 0, nonce.length);
		System.arraycopy(ct, 0, all, nonce.length, ct.length);
		Files.write(new File(dir, id + ".bin").toPath(), all);
	}

	@Nullable
	private static byte[] openWithProfileKey(MemorySharedPreferences prefs,
			File f) throws Exception {
		String b64 = prefs.getString(StickerStorage.PREF_KEY, null);
		assertNotNull(b64);
		byte[] raw = Base64.getDecoder().decode(b64);
		byte[] all = Files.readAllBytes(f.toPath());
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(raw, "AES"),
					new GCMParameterSpec(128, all, 0, 12));
			cipher.updateAAD("zerion-sticker-v2".getBytes("UTF-8"));
			return cipher.doFinal(all, 12, all.length - 12);
		} catch (Exception e) {
			return null;
		}
	}

	@Test
	public void aFreshInstallKeepsTheKeyInTheProfileSettings()
			throws Exception {
		StickerStorage s = storage();
		assertNull(prefs.getString(StickerStorage.PREF_KEY, null));
		String id = s.save(png(1));
		String key = prefs.getString(StickerStorage.PREF_KEY, null);
		assertNotNull(key);
		assertEquals(32, Base64.getDecoder().decode(key).length);
		assertArrayEquals(png(1), s.load(id));
		assertArrayEquals(png(1), storage(s.getDir()).load(id));
		assertNotNull(openWithProfileKey(prefs,
				new File(s.getDir(), id + ".bin")));
		assertEquals(Arrays.asList(id), s.listIds());
	}

	@Test
	public void twoProfilesWithDifferentKeysCannotReadEachOther()
			throws Exception {
		StickerStorage s = storage();
		String id = s.save(png(2));
		MemorySharedPreferences other = new MemorySharedPreferences();
		StickerStorage otherProfile =
				new StickerStorage(s.getDir(), other, () -> null);
		try {
			otherProfile.load(id);
			fail();
		} catch (IOException expected) {
		}
	}

	@Test
	public void aStickerUnderTheOldKeystoreKeyIsReadAndReEncrypted()
			throws Exception {
		File dir = tmp.newFolder("stickers");
		legacyKey = newKey();
		writeLegacy(dir, "abc", legacyKey, png(3));
		File f = new File(dir, "abc.bin");
		byte[] before = Files.readAllBytes(f.toPath());
		StickerStorage s = storage(dir);
		assertArrayEquals(png(3), s.load("abc"));
		byte[] after = Files.readAllBytes(f.toPath());
		assertFalse(Arrays.equals(before, after));
		assertFalse(new File(dir, "abc.bin.new").exists());
		assertArrayEquals(png(3), openWithProfileKey(prefs, f));
		legacyKey = null;
		assertArrayEquals(png(3), storage(dir).load("abc"));
	}

	@Test
	public void aMigrationInterruptedAfterTheNewFileIsFinishedOnNextRead()
			throws Exception {
		File dir = tmp.newFolder("stickers");
		StickerStorage s = storage(dir);
		String id = s.save(png(4));
		File f = new File(dir, id + ".bin");
		File pending = new File(dir, id + ".bin.new");
		byte[] good = Files.readAllBytes(f.toPath());
		Files.write(pending.toPath(), good);
		byte[] garbage = new byte[good.length];
		Arrays.fill(garbage, (byte) 0x5a);
		Files.write(f.toPath(), garbage);
		List<String> ids = storage(dir).listIds();
		assertEquals(Arrays.asList(id), ids);
		assertFalse(pending.exists());
		assertArrayEquals(png(4), storage(dir).load(id));
	}

	@Test
	public void withoutASessionNoNewStickerIsStoredButOldOnesStillOpen()
			throws Exception {
		File dir = tmp.newFolder("stickers");
		legacyKey = newKey();
		writeLegacy(dir, "old", legacyKey, png(5));
		prefs.refuseCommits = true;
		StickerStorage s = storage(dir);
		try {
			s.save(png(6));
			fail();
		} catch (IOException expected) {
		}
		assertArrayEquals(png(5), s.load("old"));
		assertNull(prefs.getString(StickerStorage.PREF_KEY, null));
		assertFalse(new File(dir, "old.bin.new").exists());
	}

	@Test
	public void deleteShredsTheFileAndAnyPendingCopy() throws Exception {
		File dir = tmp.newFolder("stickers");
		StickerStorage s = storage(dir);
		String id = s.save(png(7));
		File pending = new File(dir, id + ".bin.new");
		Files.write(pending.toPath(), new byte[40]);
		File tmpFile = new File(dir, id + ".bin.tmp");
		Files.write(tmpFile.toPath(), new byte[40]);
		assertTrue(s.delete(id));
		assertFalse(new File(dir, id + ".bin").exists());
		assertFalse(pending.exists());
		assertFalse(tmpFile.exists());
		assertTrue(s.listIds().isEmpty());
	}

	@Test
	public void aTruncatedPendingCopyDoesNotDestroyTheLegacySticker()
			throws Exception {
		File dir = tmp.newFolder("stickers");
		legacyKey = newKey();
		StickerStorage s = storage(dir);
		String fresh = s.save(png(8));
		writeLegacy(dir, "old", legacyKey, png(3));
		File legacyFile = new File(dir, "old.bin");
		byte[] legacyBytes = Files.readAllBytes(legacyFile.toPath());
		File pending = new File(dir, "old.bin.new");
		byte[] truncated = new byte[100];
		Arrays.fill(truncated, (byte) 0x33);
		Files.write(pending.toPath(), truncated);

		List<String> ids = storage(dir).listIds();
		assertTrue(ids.contains("old"));
		assertTrue(ids.contains(fresh));
		assertFalse("the unreadable pending copy is removed",
				pending.exists());
		assertArrayEquals("the legacy sticker is untouched", legacyBytes,
				Files.readAllBytes(legacyFile.toPath()));
		assertArrayEquals(png(3), storage(dir).load("old"));
		assertArrayEquals(png(3), openWithProfileKey(prefs, legacyFile));

		Files.write(pending.toPath(), truncated);
		assertArrayEquals(png(3), storage(dir).load("old"));
		assertFalse(pending.exists());
	}

	@Test
	public void aTruncatedTemporaryFileIsRemovedAndNeverListed()
			throws Exception {
		File dir = tmp.newFolder("stickers");
		StickerStorage s = storage(dir);
		String id = s.save(png(9));
		File tmpFile = new File(dir, id + ".bin.tmp");
		Files.write(tmpFile.toPath(), new byte[50]);
		File strayTmp = new File(dir, "abcdef.bin.tmp");
		Files.write(strayTmp.toPath(), new byte[50]);
		File pendingTmp = new File(dir, "abcdef.bin.new.tmp");
		Files.write(pendingTmp.toPath(), new byte[50]);
		assertEquals(Arrays.asList(id), storage(dir).listIds());
		assertFalse(tmpFile.exists());
		assertFalse(strayTmp.exists());
		assertFalse(pendingTmp.exists());
		assertArrayEquals(png(9), storage(dir).load(id));
		assertFalse(new File(dir, id + ".bin.tmp").exists());
	}

	@Test
	public void aDamagedStoredKeyIsNotReplaced() throws Exception {
		File dir = tmp.newFolder("stickers");
		prefs.values.put(StickerStorage.PREF_KEY, "not base64 at all!");
		StickerStorage s = storage(dir);
		try {
			s.save(png(10));
			fail();
		} catch (IOException expected) {
		}
		assertEquals("not base64 at all!",
				prefs.getString(StickerStorage.PREF_KEY, null));
		prefs.values.put(StickerStorage.PREF_KEY,
				Base64.getEncoder().encodeToString(new byte[16]));
		try {
			storage(dir).save(png(10));
			fail();
		} catch (IOException expected) {
		}
		assertEquals(Base64.getEncoder().encodeToString(new byte[16]),
				prefs.getString(StickerStorage.PREF_KEY, null));
		assertTrue(storage(dir).listIds().isEmpty());
	}

	@Test
	public void aKeyStoredByAnotherInstanceDuringCreationIsTheOneUsed()
			throws Exception {
		File dir = tmp.newFolder("stickers");
		byte[] foreign = new byte[32];
		Arrays.fill(foreign, (byte) 0x42);
		String foreignEncoded = Base64.getEncoder().encodeToString(foreign);
		prefs.afterCommit = () -> {
			prefs.values.put(StickerStorage.PREF_KEY, foreignEncoded);
			prefs.afterCommit = null;
		};
		StickerStorage s = storage(dir);
		String id = s.save(png(11));
		assertEquals(foreignEncoded,
				prefs.getString(StickerStorage.PREF_KEY, null));
		assertArrayEquals(png(11), openWithProfileKey(prefs,
				new File(dir, id + ".bin")));
		assertArrayEquals(png(11), storage(dir).load(id));
	}
}
