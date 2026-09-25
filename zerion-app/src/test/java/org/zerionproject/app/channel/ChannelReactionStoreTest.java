package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** EXT-13-F05: the store itself refuses to grow past the channel ceiling. */
public class ChannelReactionStoreTest {

	private static final class MemorySettings implements SettingsManager {
		private final Map<String, Settings> byNamespace = new HashMap<>();

		@Override
		public Settings getSettings(String namespace) {
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
		public void mergeSettings(Settings s, String namespace) {
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

	private static byte[] signer(int i) {
		byte[] k = new byte[32];
		k[0] = (byte) (i >> 8);
		k[1] = (byte) i;
		return k;
	}

	@Test
	public void theStoreStopsAtTheChannelCeiling() throws DbException {
		ChannelCodecTestComponent c =
				DaggerChannelCodecTestComponent.create();
		ChannelReactionStore store = new ChannelReactionStore(
				new MemorySettings(), c.getBdfReaderFactory(),
				c.getBdfWriterFactory());
		byte[] channel = new byte[32];
		int perPost = ChannelConstants.MAX_REACTIONS_PER_POST;
		int total = ChannelConstants.MAX_REACTIONS_PER_CHANNEL;
		int added = 0;
		long post = 1;
		int s = 0;
		while (added < total) {
			for (int i = 0; i < perPost && added < total; i++, s++) {
				assertTrue(store.putReaction(channel, new ChannelReaction(
						post, "+1", signer(s), new byte[4], 0)));
				added++;
			}
			post++;
		}
		assertEquals(total, store.getReactions(channel).size());
		assertFalse("the ceiling refuses one more", store.putReaction(channel,
				new ChannelReaction(post + 1, "+1", signer(s + 1),
						new byte[4], 0)));
		assertEquals(total, store.getReactions(channel).size());
		assertTrue("a signer may still replace its own reaction",
				store.putReaction(channel, new ChannelReaction(1, "-1",
						signer(0), new byte[4], 1)));
		assertEquals(total, store.getReactions(channel).size());
	}
}
