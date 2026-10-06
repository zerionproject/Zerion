package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfReader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import static org.junit.Assert.assertTrue;

public class ChannelPollingIndependenceTest {

	private final Random random = new Random(183);
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private ChannelTestNode publisherA;
	private ChannelTestNode publisherB;
	private ChannelTestNode subscriber;
	private ChannelTestNode.TestChannel a;
	private ChannelTestNode.TestChannel b;
	private final List<Long> pullsA = new ArrayList<>();
	private final List<Long> pullsB = new ArrayList<>();

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		a = new ChannelTestNode.TestChannel(crypto, random);
		b = new ChannelTestNode.TestChannel(crypto, random);
		publisherA = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport());
		publisherB = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport());
		publisherA.seedPublisher(a, true, null, null, false,
				publisherA.posts(a, 1));
		publisherB.seedPublisher(b, true, null, null, false,
				publisherB.posts(b, 1));
		subscriber = new ChannelTestNode(crypto, clock, new ChannelTransport() {
			@Override
			public ChannelServer bindServer(byte[] id,
					@Nullable String key, ChannelRequestHandler handler)
					throws IOException {
				throw new IOException();
			}

			@Override
			public byte[] requestFromOnion(String onion, byte[] request)
					throws IOException {
				try {
					BdfReader r = DaggerChannelCodecTestComponent.create()
							.getBdfReaderFactory().createReader(
									new ByteArrayInputStream(request));
					BdfDictionary d = r.readDictionary();
					byte[] id = d.getRaw("channelId");
					if (Arrays.equals(id, a.channelId)) {
						pullsA.add(clock.currentTimeMillis());
						return publisherA.handle(id, request);
					}
					pullsB.add(clock.currentTimeMillis());
					return publisherB.handle(id, request);
				} catch (Exception e) {
					throw new IOException(e);
				}
			}

			@Override
			public boolean isReachable(String onion) {
				return true;
			}
		});
		subscriber.seedSubscriber(a, true, null, null,
				Collections.<ChannelPost>emptyList());
		subscriber.seedSubscriber(b, true, null, null,
				Collections.<ChannelPost>emptyList());
	}

	@After
	public void tearDown() {
		publisherA.deleteFiles();
		publisherB.deleteFiles();
		subscriber.deleteFiles();
	}

	@Test
	public void twoSubscriptionsArePulledOnTheirOwnSchedules()
			throws Exception {
		for (int i = 0; i < 60; i++) {
			subscriber.refreshAndReschedule();
			clock.advance(subscriber.scheduledDelays.get(
					subscriber.scheduledDelays.size() - 1));
		}
		assertTrue(pullsA.size() > 5 && pullsB.size() > 5);
		int apart = 0;
		for (Long t : pullsA) {
			if (!pullsB.contains(t)) apart++;
		}
		assertTrue("the two channels were pulled in step: " + apart
				+ " of " + pullsA.size() + " pulls apart",
				apart * 2 > pullsA.size());
	}
}
