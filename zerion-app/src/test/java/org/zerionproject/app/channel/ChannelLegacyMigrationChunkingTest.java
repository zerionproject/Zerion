package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelLegacyMigrationChunkingTest {

	private static final String LEGACY_NS = "zerion-channels-posts";

	private final Random random = new Random(415);
	private final ChannelCodecTestComponent bdf =
			DaggerChannelCodecTestComponent.create();
	private CryptoComponent crypto;
	private ChannelTestNode helper;
	private ChannelTestNode.MemorySettings settings;

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		helper = new ChannelTestNode(crypto,
				new ChannelTestNode.MutableClock(),
				ChannelTestNode.noTransport());
		settings = helper.settings;
	}

	@After
	public void tearDown() {
		helper.deleteFiles();
	}

	@Test
	public void aLargeListMovesInBoundedChunks() throws Exception {
		ChannelTestNode.TestChannel c =
				new ChannelTestNode.TestChannel(crypto, random);
		List<ChannelPost> posts = helper.posts(c, 200);
		writeLegacy(c.channelId, posts);
		ChannelStore store = store();
		int before = settings.batchWrites();

		List<ChannelPost> moved = store.getPosts(c.channelId);

		assertEquals(200, moved.size());
		for (int i = 0; i < 200; i++) {
			assertEquals(i, moved.get(i).getSeqNum());
		}
		assertTrue("the whole list was written in "
				+ (settings.batchWrites() - before) + " write(s)",
				settings.batchWrites() - before >= 3);
		assertNull(settings.getSetting(LEGACY_NS,
				ChannelStore.hex(c.channelId)));
		assertEquals(199L, store.posts().tip(c.channelId).seqNum);
	}

	@Test
	public void movingOneChannelDoesNotReadAnotherChannelsList()
			throws Exception {
		ChannelTestNode.TestChannel a =
				new ChannelTestNode.TestChannel(crypto, random);
		ChannelTestNode.TestChannel b =
				new ChannelTestNode.TestChannel(crypto, random);
		writeLegacy(a.channelId, helper.posts(a, 3));
		writeLegacy(b.channelId, helper.posts(b, 100));
		long other = settings.getSetting(LEGACY_NS,
				ChannelStore.hex(b.channelId)).length();
		ChannelStore store = store();
		long before = settings.bytesRead();

		store.getPosts(a.channelId);

		long read = settings.bytesRead() - before;
		assertTrue("moving a 3-post channel read " + read
				+ " characters, the other channel's list is " + other,
				read < other);
	}

	@Test
	public void aMoveCutShortResumesWhereItStopped() throws Exception {
		ChannelTestNode.TestChannel c =
				new ChannelTestNode.TestChannel(crypto, random);
		List<ChannelPost> posts = helper.posts(c, 200);
		writeLegacy(c.channelId, posts);
		settings.crashAfterWrite(1);
		try {
			store().getPosts(c.channelId);
			fail("the move was not cut short");
		} catch (ChannelTestNode.SimulatedCrash expected) {
		}
		settings.crashAfterWrite(-1);

		ChannelStore reopened = store();
		List<ChannelPost> moved = reopened.getPosts(c.channelId);

		assertEquals(200, moved.size());
		for (int i = 0; i < 200; i++) {
			assertEquals(i, moved.get(i).getSeqNum());
			assertEquals(posts.get(i).getBody(), moved.get(i).getBody());
		}
		assertNull(settings.getSetting(LEGACY_NS,
				ChannelStore.hex(c.channelId)));
		assertEquals(200L, reopened.posts().meta(c.channelId).count);
	}

	@Test
	public void aChannelIsRemovedWithoutMovingItsPostsFirst()
			throws Exception {
		ChannelTestNode.TestChannel c =
				new ChannelTestNode.TestChannel(crypto, random);
		writeLegacy(c.channelId, helper.posts(c, 200));
		ChannelStore store = store();
		int before = settings.batchWrites();

		store.removeChannel(c.channelId);

		assertEquals("the posts were moved before being deleted", 0,
				settings.batchWrites() - before);
		String hex = ChannelStore.hex(c.channelId);
		for (String[] row : settings.rows()) {
			assertTrue(row[0] + " / " + row[1],
					!row[0].contains(hex) && !row[1].contains(hex));
		}
	}

	@Test
	public void aChannelWhoseListCannotBeReadCanStillBeRemoved()
			throws Exception {
		ChannelTestNode.TestChannel c =
				new ChannelTestNode.TestChannel(crypto, random);
		Settings s = new Settings();
		s.put(ChannelStore.hex(c.channelId), "not a list");
		settings.mergeSettings(s, LEGACY_NS);
		ChannelStore store = store();
		try {
			store.getPosts(c.channelId);
			fail("a list that cannot be read was taken as empty");
		} catch (org.zerionproject.core.api.db.DbException expected) {
		}

		store.removeChannel(c.channelId);

		assertNull(settings.getSetting(LEGACY_NS,
				ChannelStore.hex(c.channelId)));
	}

	private ChannelStore store() {
		return new ChannelStore(settings, bdf.getBdfReaderFactory(),
				bdf.getBdfWriterFactory());
	}

	private void writeLegacy(byte[] channelId, List<ChannelPost> posts)
			throws Exception {
		BdfList list = new BdfList();
		for (ChannelPost p : posts) {
			BdfDictionary d = new BdfDictionary();
			d.put("seqNum", p.getSeqNum());
			d.put("prevHash", p.getPrevHash());
			d.put("timestampHourMs", p.getTimestampHourMs());
			d.put("body", p.getBody());
			d.put("ttlMs", p.getTtlMs());
			d.put("signature", p.getSignature());
			d.put("read", false);
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
	}
}
