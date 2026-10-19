package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelDeleteNotSignedException;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DbException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelDeleteForEveryoneTest {

	private final Random random = new Random(518);
	private ChannelTestNode.MutableClock clock;
	private FakeOnionNetwork network;
	private ChannelTestNode publisher;
	private ChannelTestNode subscriber;

	@Before
	public void setUp() {
		CryptoComponent crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		network = new FakeOnionNetwork(random);
		publisher = new ChannelTestNode(crypto, clock, network);
		subscriber = new ChannelTestNode(crypto, clock, network);
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
		subscriber.deleteFiles();
	}

	@Test
	public void aSignedDeleteRemovesTheChannelForEveryone() throws Exception {
		byte[] id = createAndJoin();
		String onion = publisher.store.getChannel(id).getCurrentOnion();

		assertFalse(publisher.manager.hasDeletionNotice(id));
		publisher.manager.deleteChannelForEveryone(id);

		assertTrue("no signed notice is kept",
				publisher.manager.hasDeletionNotice(id));
		assertNull(publisher.store.getChannel(id));
		assertTrue("the tombstone is not served",
				network.published().contains(onion));
		subscriber.manager.refreshChannel(id);
		assertNull("the subscriber kept the channel",
				subscriber.store.getChannel(id));
	}

	@Test
	public void withoutASignatureNothingIsRemovedAnywhere() throws Exception {
		byte[] id = createAndJoin();
		String onion = publisher.store.getChannel(id).getCurrentOnion();
		publisher.settings.deleteSettings(ChannelStore.NS_PRIV,
				Collections.singletonList(ChannelStore.hex(id)));

		try {
			publisher.manager.deleteChannelForEveryone(id);
			fail("an unsigned delete reported success");
		} catch (ChannelDeleteNotSignedException expected) {
		}

		assertFalse(publisher.manager.hasDeletionNotice(id));
		ChannelState kept = publisher.store.getChannel(id);
		assertNotNull("the channel was removed on this phone", kept);
		assertTrue(kept.weArePublisher());
		assertEquals(1, publisher.store.getPosts(id).size());
		assertTrue("the channel stopped being served",
				network.published().contains(onion));
		try {
			subscriber.manager.refreshChannel(id);
		} catch (DbException unreachableWithoutTheKey) {
		}
		assertNotNull("the subscriber lost the channel",
				subscriber.store.getChannel(id));
	}

	@Test
	public void aSubscriberCannotDeleteForEveryone() throws Exception {
		byte[] id = createAndJoin();

		try {
			subscriber.manager.deleteChannelForEveryone(id);
			fail("a subscriber deleted the channel for everyone");
		} catch (DbException expected) {
			assertFalse(expected instanceof ChannelDeleteNotSignedException);
		}

		assertNotNull(subscriber.store.getChannel(id));
		assertNotNull(publisher.store.getChannel(id));
		assertFalse(subscriber.manager.hasDeletionNotice(id));
		assertFalse(publisher.manager.hasDeletionNotice(id));
	}

	@Test
	public void aMalformedKeyStillLetsTheOwnerRemoveItFromThisPhone()
			throws Exception {
		byte[] id = createAndJoin();
		publisher.store.putPublisherPrivKey(id, new byte[10]);
		try {
			publisher.manager.deleteChannelForEveryone(id);
			fail("a malformed key signed a deletion");
		} catch (ChannelDeleteNotSignedException expected) {
		}
		assertNotNull("nothing may be removed yet",
				publisher.store.getChannel(id));
		publisher.manager.deleteChannel(id);
		assertNull("the channel stayed on this phone",
				publisher.store.getChannel(id));
		assertFalse(publisher.manager.hasDeletionNotice(id));
	}

	@Test
	public void aNoticeStaysWhenTheLastStepOfADeletionFails()
			throws Exception {
		byte[] id = createAndJoin();
		publisher.settings.failWritesTo("zerion-channels-index");
		try {
			publisher.manager.deleteChannelForEveryone(id);
			fail("the failed last step went unnoticed");
		} catch (DbException expected) {
			assertFalse(expected instanceof ChannelDeleteNotSignedException);
		}
		assertTrue(publisher.manager.hasDeletionNotice(id));
	}

	private byte[] createAndJoin() throws Exception {
		ChannelState s = publisher.manager.createChannel("name", "about",
				true);
		byte[] id = s.getChannelId();
		publisher.manager.publishPost(id, "hello", 0L);
		String link = publisher.manager.exportInviteLink(id);
		subscriber.manager.joinChannel(
				subscriber.manager.parseInviteLink(link));
		subscriber.manager.refreshChannel(id);
		assertNotNull(subscriber.store.getChannel(id));
		return id;
	}
}
