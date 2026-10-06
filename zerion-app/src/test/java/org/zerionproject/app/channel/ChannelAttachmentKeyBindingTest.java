package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.AttachmentSpec;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfWriter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Random;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;

public class ChannelAttachmentKeyBindingTest {

	private final Random random = new Random(186);
	private CryptoComponent crypto;
	private ChannelTestNode publisher;
	private ChannelTestNode.TestChannel channel;
	private final ChannelCodecTestComponent bdf =
			DaggerChannelCodecTestComponent.create();

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		channel = new ChannelTestNode.TestChannel(crypto, random);
		publisher = new ChannelTestNode(crypto,
				new ChannelTestNode.MutableClock(),
				ChannelTestNode.noTransport());
		publisher.seedPublisher(channel, true, null, null, false,
				Collections.<ChannelPost>emptyList());
		byte[] photo = new byte[2048];
		random.nextBytes(photo);
		publisher.manager.publishPostWithAttachments(channel.channelId,
				"photo", 0L, Collections.singletonList(
						new AttachmentSpec("image/jpeg", photo, null)));
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
	}

	@Test
	public void aPostServedUnchangedIsTakenIn() throws Exception {
		ChannelTestNode subscriber = subscriber(false);
		try {
			subscriber.manager.refreshChannel(channel.channelId);
			assertEquals(1, subscriber.store.getPosts(channel.channelId)
					.size());
		} finally {
			subscriber.deleteFiles();
		}
	}

	@Test
	public void aPostWithAnExchangedAttachmentKeyIsRefused()
			throws Exception {
		ChannelTestNode subscriber = subscriber(true);
		try {
			try {
				subscriber.manager.refreshChannel(channel.channelId);
			} catch (org.zerionproject.core.api.db.DbException ignored) {
			}
			assertEquals("a post whose attachment key was exchanged is"
					+ " held", 0, subscriber.store.getPosts(
					channel.channelId).size());
		} finally {
			subscriber.deleteFiles();
		}
	}

	private ChannelTestNode subscriber(boolean exchangeKey) throws Exception {
		ChannelTransport served =
				ChannelTestNode.servedBy(publisher, channel.channelId);
		ChannelTransport transport = new ChannelTransport() {
			@Override
			public ChannelServer bindServer(byte[] id,
					@Nullable String onionPrivateKey,
					ChannelRequestHandler handler) throws IOException {
				throw new IOException();
			}

			@Override
			public byte[] requestFromOnion(String onion, byte[] request)
					throws IOException {
				byte[] response = served.requestFromOnion(onion, request);
				return exchangeKey ? exchangeFirstKey(response) : response;
			}

			@Override
			public boolean isReachable(String onion) {
				return true;
			}
		};
		ChannelTestNode s = new ChannelTestNode(crypto,
				new ChannelTestNode.MutableClock(), transport);
		s.seedSubscriber(channel, true, null, null,
				Collections.<ChannelPost>emptyList());
		return s;
	}

	private byte[] exchangeFirstKey(byte[] response) throws IOException {
		BdfReader r = bdf.getBdfReaderFactory().createReader(
				new ByteArrayInputStream(response));
		BdfDictionary d = r.readDictionary();
		BdfList posts = d.getList("posts", new BdfList());
		if (posts.isEmpty()) return response;
		BdfDictionary post = (BdfDictionary) posts.get(0);
		BdfDictionary attachment =
				(BdfDictionary) post.getList("attachments").get(0);
		byte[] other = new byte[attachment.getRaw("key").length];
		random.nextBytes(other);
		attachment.put("key", other);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = bdf.getBdfWriterFactory().createWriter(out);
		w.writeDictionary(d);
		w.flush();
		return out.toByteArray();
	}
}
