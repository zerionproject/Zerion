package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.AttachmentBlob;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;

public class ChannelLargeAttachmentFetchTest {

	private final Random random = new Random(64);
	private CryptoComponent crypto;
	private ChannelTestNode publisher;
	private ChannelTestNode subscriber;
	private ChannelTestNode.TestChannel channel;

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		ChannelTestNode.MutableClock clock =
				new ChannelTestNode.MutableClock();
		channel = new ChannelTestNode.TestChannel(crypto, random);
		publisher = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport());
		subscriber = new ChannelTestNode(crypto, clock,
				ChannelTestNode.servedBy(publisher, channel.channelId));
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
		subscriber.deleteFiles();
	}

	private void fetches(int size) throws Exception {
		byte[] plain = new byte[size];
		random.nextBytes(plain);
		byte[] key = publisher.contentKey.generateAttachmentKey();
		byte[] blob = publisher.contentKey.encryptBlob(key,
				channel.channelId, "image/jpeg", plain.length, plain);
		byte[] blobHash = crypto.hash(
				"org.zerionproject/CHANNEL_ATTACHMENT_BLOB", blob);
		ChannelPost post = publisher.post(channel, 0,
				new byte[ChannelConstants.PREV_HASH_BYTES], "photo",
				Collections.singletonList(new ChannelPost.ChannelAttachment(
						blobHash, plain.length, "image/jpeg", key, null)));
		publisher.seedPublisher(channel, true, null, null, false,
				Collections.singletonList(post));
		publisher.blobStore.put(channel.channelId, blobHash, blob);
		subscriber.seedSubscriber(channel, true, null, null,
				Collections.singletonList(post));
		AttachmentBlob shown = subscriber.manager.fetchAttachment(
				channel.channelId, 0L, blobHash);
		assertNotNull("a " + size + " byte attachment is shown", shown);
		assertArrayEquals(plain, shown.getPlaintextBytes());
	}

	@Test
	public void anAttachmentJustOverTheDefaultFieldSizeIsFetched()
			throws Exception {
		fetches(70 * 1024);
	}

	@Test
	public void aPhotoSizedAttachmentIsFetched() throws Exception {
		fetches(3 * 1024 * 1024);
	}
}
