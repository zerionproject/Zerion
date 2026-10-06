package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelApplication;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChannelApprovalRoundTripTest {

	private final Random random = new Random(271);
	private final List<byte[]> applications = new ArrayList<>();
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private ChannelTestNode publisher;
	private ChannelTestNode subscriber;
	private ChannelTestNode.TestChannel channel;
	private byte[] capability;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		publisher = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport());
		channel = new ChannelTestNode.TestChannel(crypto, random);
		capability = new byte[32];
		random.nextBytes(capability);
		publisher.seedPublisher(channel, false, capability,
				publisher.contentKey.generateContentKey(), true,
				publisher.posts(channel, 2));
		subscriber = new ChannelTestNode(crypto, clock,
				recording(ChannelTestNode.servedBy(publisher,
						channel.channelId)), null,
				identity(crypto.generateHybridSignatureKeyPair()));
		subscriber.seedSubscriber(channel, false, null, null,
				Collections.emptyList());
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
		subscriber.deleteFiles();
	}

	@Test
	public void anApprovedApplicantReceivesTheCapabilityOnItsNextRefresh()
			throws Exception {
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");
		List<ChannelApplication> pending =
				publisher.manager.listPendingApplications(channel.channelId);
		assertEquals("the application reached the publisher", 1,
				pending.size());

		subscriber.refreshAndReschedule();
		assertNull("nothing is granted before approval",
				subscriber.store.getChannel(channel.channelId)
						.getJoinCapability());

		approveAndRefresh();

		ChannelState after = subscriber.store.getChannel(channel.channelId);
		assertArrayEquals("the applicant holds the capability", capability,
				after.getJoinCapability());
		assertEquals("and pulled the posts with it", 2,
				subscriber.store.getPosts(channel.channelId).size());
	}

	@Test
	public void anApplicantThatLeftAndAppliedAgainCanBeApprovedAgain()
			throws Exception {
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");
		approveAndRefresh();
		assertArrayEquals(capability, subscriber.store
				.getChannel(channel.channelId).getJoinCapability());

		subscriber.manager.leaveChannel(channel.channelId);
		subscriber.seedSubscriber(channel, false, null, null,
				Collections.emptyList());
		clock.advance(ChannelTestNode.HOUR);
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");

		List<ChannelApplication> pending =
				publisher.manager.listPendingApplications(channel.channelId);
		assertEquals("the new application waits for approval again", 1,
				pending.size());
		approveAndRefresh();

		assertArrayEquals("the capability is wrapped to the new key",
				capability, subscriber.store.getChannel(channel.channelId)
						.getJoinCapability());
	}

	@Test
	public void aReplayedOlderApplicationDoesNotDisplaceTheNewerOne()
			throws Exception {
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");
		byte[] first = applications.get(0);
		subscriber.manager.leaveChannel(channel.channelId);
		subscriber.seedSubscriber(channel, false, null, null,
				Collections.emptyList());
		clock.advance(ChannelTestNode.HOUR);
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");
		byte[] newerKey = held().getApplicantEphemeralAgreementPub();

		assertTrue(ChannelTestNode.ackOk(
				publisher.handle(channel.channelId, first)));
		assertArrayEquals("the older application is ignored", newerKey,
				held().getApplicantEphemeralAgreementPub());

		approveAndRefresh();
		assertArrayEquals(capability, subscriber.store
				.getChannel(channel.channelId).getJoinCapability());
	}

	@Test
	public void aRepeatedApplicationLeavesAnApprovalInPlace()
			throws Exception {
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");
		byte[] request = applications.get(0);
		publisher.manager.approveApplication(channel.channelId,
				held().getApplicantEd25519());

		assertTrue(ChannelTestNode.ackOk(
				publisher.handle(channel.channelId, request)));
		assertEquals("a replay changes nothing",
				ChannelApplication.Status.APPROVED, held().getStatus());
		assertFalse(publisher.manager.listAllApplications(channel.channelId)
				.isEmpty());
	}

	private void approveAndRefresh() throws Exception {
		List<ChannelApplication> pending =
				publisher.manager.listPendingApplications(channel.channelId);
		publisher.manager.approveApplication(channel.channelId,
				pending.get(0).getApplicantEd25519());
		clock.advance(31_000L);
		subscriber.refreshAndReschedule();
	}

	private ChannelApplication held() throws Exception {
		List<ChannelApplication> all =
				publisher.manager.listAllApplications(channel.channelId);
		assertEquals(1, all.size());
		return all.get(0);
	}

	private ChannelTransport recording(ChannelTransport inner) {
		return new ChannelTransport() {
			@Override
			public ChannelServer bindServer(byte[] id,
					@Nullable String onionPrivateKey,
					ChannelRequestHandler handler)
					throws java.io.IOException {
				return inner.bindServer(id, onionPrivateKey, handler);
			}

			@Override
			public byte[] requestFromOnion(String onion, byte[] request)
					throws java.io.IOException {
				if (ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN.equals(
						publisher.pullCodec.peekType(request))) {
					applications.add(request.clone());
				}
				return inner.requestFromOnion(onion, request);
			}

			@Override
			public boolean isReachable(String onion) {
				return inner.isReachable(onion);
			}
		};
	}

	private static IdentityManager identity(KeyPair hybrid) {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) hybrid.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) hybrid.getPublic();
		LocalAuthor me = new LocalAuthor(new AuthorId(new byte[32]),
				Author.FORMAT_VERSION, "Applicant", pub.getEd25519Component(),
				priv.getEd25519Component());
		return (IdentityManager) Proxy.newProxyInstance(
				IdentityManager.class.getClassLoader(),
				new Class<?>[] {IdentityManager.class},
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "getLocalAuthor":
							return me;
						case "getLocalMlDsaSigPublicKey":
							return pub.getMlDsaPublicKey();
						case "getLocalMlDsaSigPrivateKey":
							return priv.getMlDsaPrivateKey();
						default:
							Class<?> r = method.getReturnType();
							if (r == boolean.class) return false;
							if (r == int.class) return 0;
							if (r == long.class) return 0L;
							return null;
					}
				});
	}
}
