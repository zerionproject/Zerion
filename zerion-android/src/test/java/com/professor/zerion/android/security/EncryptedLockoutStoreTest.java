package com.professor.zerion.android.security;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.core.account.LoginThrottle;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class EncryptedLockoutStoreTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	@After
	public void tearDown() {
		ZerionEncryptedPrefs.resetForTests();
		RuntimeEnvironment.getApplication()
				.getSharedPreferences("lockout_v2", Context.MODE_PRIVATE)
				.edit().clear().commit();
	}

	private Context app() {
		return RuntimeEnvironment.getApplication();
	}

	private SharedPreferences backing() {
		return app().getSharedPreferences("lockout_v2", Context.MODE_PRIVATE);
	}

	@Test
	public void aRecordRoundTripsEncryptedAndLeavesNoPlaintextFile()
			throws Exception {
		File f = new File(tmp.getRoot(), "login.lockout");
		EncryptedLockoutStores stores = new EncryptedLockoutStores(app());
		LoginThrottle.Store store = stores.open(f);
		assertNull(store.load());
		store.save("1,3,boot,100,400");
		assertFalse(f.exists());
		assertEquals("1,3,boot,100,400", stores.open(f).load());
		Map<String, ?> raw = backing().getAll();
		assertEquals(1, raw.size());
		for (Object v : raw.values()) {
			assertFalse(((String) v).contains("boot"));
		}
		store.clear();
		assertNull(stores.open(f).load());
		assertTrue(backing().getAll().isEmpty());
	}

	@Test
	public void aPlaintextRecordIsMovedIntoTheEncryptedStoreOnFirstRead()
			throws Exception {
		File f = new File(tmp.getRoot(), "password.check.lockout");
		Files.write(f.toPath(),
				"1,5,boot-a,1000,9000".getBytes(StandardCharsets.UTF_8));
		EncryptedLockoutStores stores = new EncryptedLockoutStores(app());
		assertEquals("1,5,boot-a,1000,9000", stores.open(f).load());
		assertFalse("the plaintext file is gone", f.exists());
		assertEquals(1, backing().getAll().size());
		assertEquals("1,5,boot-a,1000,9000", stores.open(f).load());
	}

	@Test
	public void theThrottleKeepsItsRulesOnTopOfTheEncryptedStore()
			throws Exception {
		File f = new File(tmp.getRoot(), "decoy.lockout");
		EncryptedLockoutStores stores = new EncryptedLockoutStores(app());
		AtomicLong mono = new AtomicLong(10_000);
		LoginThrottle t = new LoginThrottle(stores.open(f), mono::get,
				() -> "boot-a", LoginThrottle.VAULT);
		t.recordFailure();
		assertTrue(t.remainingLockoutMs() > 0);
		LoginThrottle again = new LoginThrottle(stores.open(f), mono::get,
				() -> "boot-a", LoginThrottle.VAULT);
		assertTrue("the lockout survives a restart",
				again.remainingLockoutMs() > 0);
		LoginThrottle rebooted = new LoginThrottle(stores.open(f), mono::get,
				() -> "boot-b", LoginThrottle.VAULT);
		assertTrue("a reboot does not reset the count",
				rebooted.remainingLockoutMs() > 0);
		assertFalse(f.exists());
	}

	@Test
	public void withoutAKeystoreTheRecordStaysInThePlaintextFile()
			throws Exception {
		File f = new File(tmp.getRoot(), "login.lockout");
		EncryptedLockoutStores stores = new EncryptedLockoutStores(app(),
				ctx -> {
					throw new RuntimeException("keystore unavailable");
				});
		LoginThrottle.Store store = stores.open(f);
		store.save("1,1,boot,1,2");
		assertTrue(f.exists());
		assertEquals("1,1,boot,1,2", stores.open(f).load());
		store.clear();
		assertFalse(f.exists());
	}

	@Test
	public void aStoreThatCannotCommitFallsBackToTheFileWithoutLosingTheRecord()
			throws Exception {
		File f = new File(tmp.getRoot(), "unlock.throttle");
		MemorySharedPreferences prefs = new MemorySharedPreferences();
		prefs.refuseCommits = true;
		EncryptedLockoutStore store = new EncryptedLockoutStore(prefs, f);
		store.save("1,2,boot,5,6");
		assertTrue(f.exists());
		assertEquals("1,2,boot,5,6", store.load());
		prefs.refuseCommits = false;
		assertEquals("1,2,boot,5,6", store.load());
		assertFalse("moved once the store accepts it", f.exists());
	}

	@Test
	public void theStricterOfTwoRecordsWinsWhenBothExist() throws Exception {
		File f = new File(tmp.getRoot(), "unlock.throttle");
		MemorySharedPreferences prefs = new MemorySharedPreferences();
		prefs.values.put(f.getAbsolutePath(), "1,2,boot,100,200");
		Files.write(f.toPath(),
				"1,5,boot,100,900".getBytes(StandardCharsets.UTF_8));
		EncryptedLockoutStore store = new EncryptedLockoutStore(prefs, f);
		assertEquals("1,5,boot,100,900", store.load());
		assertEquals("1,5,boot,100,900",
				prefs.getString(f.getAbsolutePath(), null));
		assertFalse(f.exists());

		prefs.values.put(f.getAbsolutePath(), "1,5,boot,100,900");
		Files.write(f.toPath(),
				"1,2,boot,100,200".getBytes(StandardCharsets.UTF_8));
		assertEquals("1,5,boot,100,900", store.load());
		assertEquals("1,5,boot,100,900",
				prefs.getString(f.getAbsolutePath(), null));
		assertFalse(f.exists());

		prefs.values.put(f.getAbsolutePath(), "1,5,boot,100,200");
		Files.write(f.toPath(),
				"1,5,boot,100,900".getBytes(StandardCharsets.UTF_8));
		assertEquals("a later deadline wins at equal failures",
				"1,5,boot,100,900", store.load());
		assertFalse(f.exists());
	}

	@Test
	public void aCommitReportedAsFailedButStoredWritesNoPlaintextFile()
			throws Exception {
		File f = new File(tmp.getRoot(), "login.lockout");
		MemorySharedPreferences prefs = new MemorySharedPreferences();
		prefs.reportCommitsAsFailed = true;
		EncryptedLockoutStore store = new EncryptedLockoutStore(prefs, f);
		store.save("1,4,boot,7,8");
		assertFalse("the record was stored, so no plaintext copy",
				f.exists());
		assertEquals("1,4,boot,7,8", store.load());
		assertEquals("1,4,boot,7,8",
				prefs.getString(f.getAbsolutePath(), null));
	}
}
