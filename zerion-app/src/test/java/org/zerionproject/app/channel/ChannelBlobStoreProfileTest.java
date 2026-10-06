package org.zerionproject.app.channel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.util.IoUtils;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelBlobStoreProfileTest {

	private final File root = new File(System.getProperty("java.io.tmpdir"),
			"zt-blob-profiles-" + System.nanoTime());
	private final byte[] channelId = new byte[32];
	private final byte[] blobHash = new byte[32];
	private final byte[] blob = {1, 2, 3, 4};
	private CryptoComponent crypto;
	private String activeProfile = "default";

	private final DatabaseConfig config = new DatabaseConfig() {
		@Override
		public File getDatabaseDirectory() {
			return new File(new File(root, activeProfile), "db");
		}

		@Override
		public File getDatabaseKeyDirectory() {
			return new File(new File(root, activeProfile), "key");
		}

		@Override
		@Nullable
		public KeyStrengthener getKeyStrengthener() {
			return null;
		}
	};

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		channelId[0] = 7;
		blobHash[0] = 9;
	}

	@After
	public void tearDown() {
		IoUtils.deleteFileOrDir(root);
	}

	private static boolean holdsFiles(File dir) {
		File[] children = dir.listFiles();
		if (children == null) return false;
		for (File c : children) {
			if (c.isFile() || holdsFiles(c)) return true;
		}
		return false;
	}

	@Test
	public void blobsGoToTheSignedInProfilesDirectory() throws Exception {
		ChannelBlobStore store = new ChannelBlobStore(config,
				new MemorySettings(), crypto);
		activeProfile = "b7c1";

		store.put(channelId, blobHash, blob);

		assertTrue(holdsFiles(new File(root, "b7c1/channel-blobs")));
		assertFalse("nothing in the default profile's directory",
				new File(root, "default/channel-blobs").exists());
		assertArrayEquals(blob, store.get(channelId, blobHash));
	}

	@Test
	public void blobsLeftInTheDefaultProfileMoveToTheirProfile()
			throws Exception {
		MemorySettings hiddenSettings = new MemorySettings();
		new ChannelBlobStore(config, hiddenSettings, crypto)
				.put(channelId, blobHash, blob);
		assertTrue(holdsFiles(new File(root, "default/channel-blobs")));

		ChannelBlobStore store = new ChannelBlobStore(config, hiddenSettings,
				crypto);
		activeProfile = "b7c1";

		assertArrayEquals(blob, store.get(channelId, blobHash));
		assertTrue(holdsFiles(new File(root, "b7c1/channel-blobs")));
		assertFalse(holdsFiles(new File(root, "default/channel-blobs")));
	}

	private static final class MemorySettings implements SettingsManager {
		private final Map<String, Settings> byNamespace = new HashMap<>();

		@Override
		public synchronized Settings getSettings(String namespace) {
			Settings s = new Settings();
			Settings stored = byNamespace.get(namespace);
			if (stored != null) s.putAll(stored);
			return s;
		}

		@Override
		public Settings getSettings(Transaction txn, String namespace) {
			return getSettings(namespace);
		}

		@Override
		public synchronized void mergeSettings(Settings s, String namespace) {
			Settings merged = getSettings(namespace);
			merged.putAll(s);
			byNamespace.put(namespace, merged);
		}

		@Override
		public void mergeSettings(Transaction txn, Settings s,
				String namespace) {
			mergeSettings(s, namespace);
		}
	}
}
