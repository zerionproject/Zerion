package org.zerionproject.app.channel;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.settings.SettingsManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelAttachmentServingBoundsTest {

	private static final int MIB = 1024 * 1024;
	private static final int MAX_RESPONSE = 16 * MIB;

	private final Random random = new Random(194);
	private final ExecutorService exec = Executors.newCachedThreadPool();
	private CryptoComponent crypto;
	private ChannelTestNode node;
	private WatchedBlobStore blobs;
	private ChannelTestNode.TestChannel channel;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		node = new ChannelTestNode(crypto,
				new ChannelTestNode.MutableClock(),
				ChannelTestNode.noTransport(), WatchedBlobStore::new);
		blobs = (WatchedBlobStore) node.blobStore;
		channel = new ChannelTestNode.TestChannel(crypto, random);
		node.seedPublisher(channel, true, null, null, false,
				node.posts(channel, 1));
	}

	@After
	public void tearDown() {
		blobs.open();
		exec.shutdownNow();
		node.deleteFiles();
	}

	@Test
	public void aSmallBlobIsServed() throws Exception {
		byte[] hash = blob(4096);
		ChannelPullCodec.AttachmentResponse r = fetch(hash);
		assertEquals(4096, r.blob.length);
	}

	@Test
	public void aBlobTooLargeForAResponseIsNotRead() throws Exception {
		byte[] hash = blob(20 * MIB);
		byte[] response = node.handle(channel.channelId, request(hash));
		assertTrue("a response of " + response.length + " bytes",
				response.length <= MAX_RESPONSE);
		assertEquals("the blob is not read", 0, blobs.reads.get());
	}

	@Test(timeout = 60_000)
	public void concurrentFetchesAreBoundedInMemory() throws Exception {
		byte[] first = blob(13 * MIB);
		byte[] second = blob(13 * MIB);
		blobs.close();
		Future<byte[]> a = exec.submit(
				() -> node.handle(channel.channelId, request(first)));
		assertTrue(blobs.entered.await(10, TimeUnit.SECONDS));
		Future<byte[]> b = exec.submit(
				() -> node.handle(channel.channelId, request(second)));
		byte[] refused;
		try {
			refused = b.get(5, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			fail("a second large fetch was read while the first one was");
			return;
		}
		assertEquals("the second fetch is refused", 0, refused.length);
		assertEquals(1, blobs.reads.get());
		blobs.open();
		byte[] served = a.get(30, TimeUnit.SECONDS);
		assertTrue("the first fetch is served in full",
				served.length > 13 * MIB);
		assertTrue(node.handle(channel.channelId, request(second)).length
				> 13 * MIB);
	}

	private ChannelPullCodec.AttachmentResponse fetch(byte[] hash)
			throws Exception {
		return node.pullCodec.decodeAttachmentResponse(
				node.handle(channel.channelId, request(hash)));
	}

	private byte[] request(byte[] hash) throws Exception {
		return node.pullCodec.encodeAttachmentRequest(channel.channelId,
				hash, null, null);
	}

	private byte[] blob(int size) throws Exception {
		byte[] hash = new byte[32];
		random.nextBytes(hash);
		node.blobStore.put(channel.channelId, hash, new byte[1]);
		File f = find(node.root, ChannelStore.hex(hash) + ".bin");
		assertNotNull(f);
		try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
			raf.setLength(size);
		}
		return hash;
	}

	private static File find(File dir, String name) {
		File[] children = dir.listFiles();
		if (children == null) return null;
		for (File c : children) {
			if (c.isDirectory()) {
				File found = find(c, name);
				if (found != null) return found;
			} else if (c.getName().equals(name)) {
				return c;
			}
		}
		return null;
	}

	private static final class WatchedBlobStore extends ChannelBlobStore {

		final AtomicInteger reads = new AtomicInteger();
		final CountDownLatch entered = new CountDownLatch(1);
		private volatile CountDownLatch gate = new CountDownLatch(0);

		WatchedBlobStore(DatabaseConfig config, SettingsManager settings,
				CryptoComponent crypto) {
			super(config, settings, crypto);
		}

		void close() {
			gate = new CountDownLatch(1);
		}

		void open() {
			gate.countDown();
		}

		@Override
		byte[] get(byte[] channelId, byte[] blobHash) throws IOException {
			reads.incrementAndGet();
			entered.countDown();
			try {
				gate.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return super.get(channelId, blobHash);
		}
	}
}
