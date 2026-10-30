package org.zerionproject.tor;

import android.content.Context;

import com.professor.zerion.android.security.DeviceTorStateStore;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.security.TestZerionEncryptedPrefs;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.annotation.Nullable;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class TorGuardStateDeviceWideTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private static final class ProfileDb implements TorStateStore {

		@Nullable
		StoredState stored = null;

		@Override
		@Nullable
		public StoredState load() {
			return stored;
		}

		@Override
		public boolean save(StoredState state) {
			stored = state;
			return true;
		}

		@Override
		public void clear() {
			stored = null;
		}
	}

	@After
	public void tearDown() {
		TestZerionEncryptedPrefs.reset();
		RuntimeEnvironment.getApplication()
				.getSharedPreferences("tor_state_v2", Context.MODE_PRIVATE)
				.edit().clear().commit();
	}

	private static TorStatePersistence signIn(File dataDir, ProfileDb db) {
		TestZerionEncryptedPrefs.reset();
		return new TorStatePersistence(dataDir, new DeviceTorStateStore(
				RuntimeEnvironment.getApplication(), db));
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	@Test
	public void twoProfilesSigningInOneAfterTheOtherShareOneGuardSet()
			throws Exception {
		File dataDir = tmp.newFolder(".tor");
		File state = new File(dataDir, TorStatePersistence.STATE_FILE);
		ProfileDb visible = new ProfileDb();
		ProfileDb hidden = new ProfileDb();

		TorStatePersistence first = signIn(dataDir, visible);
		first.restore();
		assertFalse(state.exists());
		Files.write(state.toPath(), bytes("Guard in EntryGuard relayA"));
		first.persist();
		assertFalse("no plaintext state after sign-out", state.exists());
		assertNull("nothing is kept in the profile database", visible.stored);

		TorStatePersistence second = signIn(dataDir, hidden);
		second.restore();
		assertArrayEquals("the hidden profile gets the same guards",
				bytes("Guard in EntryGuard relayA"),
				Files.readAllBytes(state.toPath()));
		Files.write(state.toPath(), bytes("Guard in EntryGuard relayA relayB"));
		state.setLastModified(System.currentTimeMillis() + 5_000);
		second.persist();
		assertNull(hidden.stored);

		TorStatePersistence again = signIn(dataDir, visible);
		again.restore();
		assertArrayEquals("the first profile sees the update",
				bytes("Guard in EntryGuard relayA relayB"),
				Files.readAllBytes(state.toPath()));
	}

	@Test
	public void aStateLeftInAProfileDatabaseIsMovedToTheDevice()
			throws Exception {
		File dataDir = tmp.newFolder(".tor");
		File state = new File(dataDir, TorStatePersistence.STATE_FILE);
		ProfileDb old = new ProfileDb();
		old.stored = new TorStateStore.StoredState(bytes("old guards"), 1L);
		signIn(dataDir, old).restore();
		assertArrayEquals(bytes("old guards"),
				Files.readAllBytes(state.toPath()));
		assertNull("cleared from the profile database", old.stored);
		state.delete();
		signIn(dataDir, new ProfileDb()).restore();
		assertArrayEquals("kept device-wide", bytes("old guards"),
				Files.readAllBytes(state.toPath()));
	}
}
