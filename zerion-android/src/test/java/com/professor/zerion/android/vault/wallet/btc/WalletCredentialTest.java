package com.professor.zerion.android.vault.wallet.btc;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.InMemoryVault;
import com.professor.zerion.android.vault.wallet.WalletStore;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicLong;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class WalletCredentialTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final AtomicLong clock = new AtomicLong(10_000);
	private final BtcWalletSettings settings;

	public WalletCredentialTest() throws Exception {
		settings = new BtcWalletSettings(
				new WalletStore(new InMemoryVault().manager));
	}

	private WalletCredential credential() {
		return new WalletCredential(settings, clock::get);
	}

	@Test
	public void setupWritesTheRecordAndMatchesOnlyTheRightCredential()
			throws Exception {
		WalletCredential c = credential();
		assertFalse(c.exists());
		assertFalse(c.isPin());
		c.setup("PIN", "2468".toCharArray());
		assertTrue(c.exists());
		assertTrue(c.isPin());
		JSONObject o = settings.read();
		assertEquals(120_000, o.getInt("authIter"));
		char[] wrong = "1357".toCharArray();
		assertFalse(c.matches(wrong));
		assertArrayEquals("the credential is wiped after use",
				new char[4], wrong);
		char[] right = "2468".toCharArray();
		assertTrue(c.matches(right));
		assertArrayEquals(new char[4], right);
	}

	@Test
	public void aMissingOrBrokenRecordNeverMatches() throws Exception {
		WalletCredential c = credential();
		assertFalse(c.matches("anything".toCharArray()));
		settings.write(new JSONObject().put("authSalt", "!!").put("authIter",
				1).put("authHash", "AA=="));
		assertFalse(c.matches("anything".toCharArray()));
	}

	@Test
	public void failuresArePersistedAndThrottleAfterTheFreeOnes()
			throws Exception {
		WalletCredential c = credential();
		c.setup("PASSWORD", "pw".toCharArray());
		c.restoreFailures();
		assertFalse(c.recordFailure());
		assertFalse(c.recordFailure());
		assertFalse(c.isThrottled());
		assertTrue("the third failure relocks", c.recordFailure());
		assertTrue(c.isThrottled());
		assertEquals(3, settings.readQuiet().getInt("authFailures"));
		clock.addAndGet(1_000);
		assertFalse(c.isThrottled());
		c.recordSuccess();
		assertEquals(0, settings.readQuiet().getInt("authFailures"));
	}

	@Test
	public void restoredFailuresThrottleANewInstanceOnce() throws Exception {
		WalletCredential first = credential();
		first.setup("PASSWORD", "pw".toCharArray());
		first.restoreFailures();
		for (int i = 0; i < 4; i++) first.recordFailure();
		WalletCredential second = credential();
		assertFalse("nothing is restored until asked", second.isThrottled());
		second.restoreFailures();
		assertTrue(second.isThrottled());
		clock.addAndGet(WalletCredentialThrottle.delayFor(4));
		assertFalse(second.isThrottled());
		settings.writeQuiet(settings.readQuiet().put("authFailures", 9));
		second.restoreFailures();
		assertFalse("failures are restored only once", second.isThrottled());
	}
}
