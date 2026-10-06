package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.db.HyperSqlDatabaseForTests;
import org.zerionproject.core.settings.SettingsManagerOverDatabase;
import org.zerionproject.core.test.TestMessageFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Base64;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ChannelPostMigrationTest {

	private static final String LEGACY_NS = "zerion-channels-posts";

	private final File testDir = getTestDirectory();
	private final SecretKey dbKey = getSecretKey();
	private final Random random = new Random(226);
	private final ChannelCodecTestComponent bdf =
			DaggerChannelCodecTestComponent.create();
	private CryptoComponent crypto;
	private DatabaseComponent db;
	private SettingsManager settings;

	@Before
	public void setUp() throws Exception {
		assertTrue(testDir.mkdirs());
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		db = HyperSqlDatabaseForTests.open(testDir, dbKey, new EventBus() {
			@Override
			public void addListener(EventListener l) {
			}

			@Override
			public void removeListener(EventListener l) {
			}

			@Override
			public void broadcast(Event e) {
			}
		}, new TestMessageFactory());
		settings = SettingsManagerOverDatabase.create(db);
	}

	@After
	public void tearDown() throws Exception {
		if (db != null) db.close();
		deleteTestDirectory(testDir);
	}

	@Test
	public void legacyPostsMoveToOneValuePerPost() throws Exception {
		ChannelTestNode helper = new ChannelTestNode(crypto,
				new ChannelTestNode.MutableClock(),
				ChannelTestNode.noTransport());
		try {
			ChannelTestNode.TestChannel c =
					new ChannelTestNode.TestChannel(crypto, random);
			List<ChannelPost> posts = helper.posts(c, 5);
			writeLegacy(c.channelId, posts, 2, 3);
			ChannelStore store = new ChannelStore(settings,
					bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory());

			List<ChannelPost> moved = store.getPosts(c.channelId);

			assertEquals(5, moved.size());
			for (int i = 0; i < 5; i++) {
				assertEquals(i, moved.get(i).getSeqNum());
				assertEquals(posts.get(i).getBody(), moved.get(i).getBody());
				assertTrue(java.util.Arrays.equals(
						helper.chain.hashOf(posts.get(i)),
						helper.chain.hashOf(moved.get(i))));
			}
			assertTrue(moved.get(2).isRead());
			assertFalse(moved.get(4).isRead());
			assertTrue(moved.get(3).isWithheld());
			assertNull("the legacy list is left",
					settings.getSettings(LEGACY_NS)
							.get(ChannelStore.hex(c.channelId)));
			assertEquals(4L, store.posts().tip(c.channelId).seqNum);
			assertFalse(settings.getSettings("zerion-channels-post:"
					+ ChannelStore.hex(c.channelId) + ":2").isEmpty());
		} finally {
			helper.deleteFiles();
		}
	}

	@Test
	public void anInterruptedMoveLeavesNoOldCopy() throws Exception {
		ChannelTestNode helper = new ChannelTestNode(crypto,
				new ChannelTestNode.MutableClock(),
				ChannelTestNode.noTransport());
		try {
			ChannelTestNode.TestChannel c =
					new ChannelTestNode.TestChannel(crypto, random);
			List<ChannelPost> posts = helper.posts(c, 3);
			writeLegacy(c.channelId, posts, -1, -1);
			new ChannelStore(settings, bdf.getBdfReaderFactory(),
					bdf.getBdfWriterFactory()).getPosts(c.channelId);
			writeLegacy(c.channelId, posts, -1, -1);

			ChannelStore reopened = new ChannelStore(settings,
					bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory());
			assertEquals(3, reopened.getPosts(c.channelId).size());
			assertNull(settings.getSettings(LEGACY_NS)
					.get(ChannelStore.hex(c.channelId)));
		} finally {
			helper.deleteFiles();
		}
	}

	@Test
	public void deletingAChannelLeavesNoRowOnTheRealDatabase()
			throws Exception {
		ChannelTestNode helper = new ChannelTestNode(crypto,
				new ChannelTestNode.MutableClock(),
				ChannelTestNode.noTransport());
		try {
			ChannelTestNode.TestChannel c =
					new ChannelTestNode.TestChannel(crypto, random);
			writeLegacy(c.channelId, helper.posts(c, 3), -1, -1);
			ChannelStore store = new ChannelStore(settings,
					bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory());
			store.getPosts(c.channelId);

			store.removeChannel(c.channelId);

			String hex = ChannelStore.hex(c.channelId);
			for (int i = 0; i < 3; i++) {
				assertTrue(settings.getSettings("zerion-channels-post:" + hex
						+ ":" + i).isEmpty());
			}
			assertTrue(settings.getSettings("zerion-channels-post-meta:"
					+ hex).isEmpty());
			assertFalse(settings.getSettings(LEGACY_NS).containsKey(hex));
		} finally {
			helper.deleteFiles();
		}
	}

	private void writeLegacy(byte[] channelId, List<ChannelPost> posts,
			int readThrough, int withheld) throws Exception {
		BdfList list = new BdfList();
		for (ChannelPost p : posts) {
			BdfDictionary d = new BdfDictionary();
			d.put("seqNum", p.getSeqNum());
			d.put("prevHash", p.getPrevHash());
			d.put("timestampHourMs", p.getTimestampHourMs());
			d.put("body", p.getBody());
			d.put("ttlMs", p.getTtlMs());
			d.put("signature", p.getSignature());
			d.put("read", p.getSeqNum() <= readThrough);
			if (p.getSeqNum() == withheld) d.put("withheld", true);
			d.put("attachments", new BdfList());
			list.add(d);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = bdf.getBdfWriterFactory().createWriter(out);
		w.writeList(list);
		w.flush();
		Settings s = new Settings();
		s.put(ChannelStore.hex(channelId), Base64.getEncoder()
				.withoutPadding().encodeToString(out.toByteArray()));
		settings.mergeSettings(s, LEGACY_NS);
		assertEquals(ChannelConstants.PREV_HASH_BYTES,
				posts.get(0).getPrevHash().length);
	}
}
