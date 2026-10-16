package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ApplicationStatus;
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
import static org.junit.Assert.fail;

public class ChannelApprovalRoundTripTest {

	private final Random random = new Random(271);
	private final List<byte[]> applications = new ArrayList<>();
	private final List<byte[]> checks = new ArrayList<>();
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
	public void anApprovedApplicantThatLeftAndAppliedAgainStaysApproved()
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

		assertTrue("an approved key waits for no new approval",
				publisher.manager.listPendingApplications(channel.channelId)
						.isEmpty());
		assertEquals(ChannelApplication.Status.APPROVED, held().getStatus());
		clock.advance(31_000L);
		subscriber.refreshAndReschedule();

		assertArrayEquals("the capability is wrapped to the new key",
				capability, subscriber.store.getChannel(channel.channelId)
						.getJoinCapability());
	}

	@Test
	public void anApprovalMadeBeforeARenewalIsNeitherAnsweredNorListed()
			throws Exception {
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");
		publisher.manager.approveApplication(channel.channelId,
				held().getApplicantEd25519());
		assertEquals("APPROVED", check().status);

		publisher.manager.rotateJoinCapability(channel.channelId);

		ChannelPullCodec.ApprovalResponse answer = check();
		assertEquals("a revoked approval was answered", "UNKNOWN",
				answer.status);
		assertNull(answer.envelope);
		assertTrue("a revoked approval is listed as approved",
				publisher.manager.listAllApplications(channel.channelId)
						.isEmpty());
	}

	@Test
	public void aLegacyApprovalIsStillListedAndKnownAfterTheUpgrade()
			throws Exception {
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");
		publisher.manager.approveApplication(channel.channelId,
				held().getApplicantEd25519());

		ChannelApplication legacy = legacy();

		List<ChannelApplication> listed =
				publisher.manager.listAllApplications(channel.channelId);
		assertEquals("an approval from before the upgrade is hidden", 1,
				listed.size());
		assertEquals(ChannelApplication.Status.APPROVED,
				listed.get(0).getStatus());
		assertTrue("an approval from before the upgrade is not known",
				known(legacy.getApplicantEd25519()));
	}

	@Test
	public void aLegacyKeyThatAppliesAgainWaitsWithNothingWrapped()
			throws Exception {
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");
		approveAndRefresh();
		legacy();
		assertEquals(ChannelApplication.Status.APPROVED, held().getStatus());

		subscriber.manager.leaveChannel(channel.channelId);
		subscriber.seedSubscriber(channel, false, null, null,
				Collections.emptyList());
		clock.advance(ChannelTestNode.HOUR);
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");

		ChannelApplication again = stored();
		assertEquals("a legacy approval was renewed",
				ChannelApplication.Status.PENDING, again.getStatus());
		assertNull(again.getKemCiphertext());
		assertNull(again.getEnvelope());
		assertNull(again.getApprovedUnder());
	}

	@Test
	public void aRenewalRevokesALegacyApprovalToo() throws Exception {
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");
		publisher.manager.approveApplication(channel.channelId,
				held().getApplicantEd25519());
		ChannelApplication legacy = legacy();
		assertEquals("APPROVED", check().status);
		assertTrue(known(legacy.getApplicantEd25519()));

		publisher.manager.rotateJoinCapability(channel.channelId);

		assertTrue("a legacy approval outlived a renewal",
				publisher.manager.listAllApplications(channel.channelId)
						.isEmpty());
		assertFalse(known(legacy.getApplicantEd25519()));
		ChannelPullCodec.ApprovalResponse answer = check();
		assertEquals("UNKNOWN", answer.status);
		assertNull(answer.envelope);
	}

	@Test
	public void aCheckForALegacyApprovalNeverWrapsTheCurrentCapability()
			throws Exception {
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");
		publisher.manager.approveApplication(channel.channelId,
				held().getApplicantEd25519());
		ChannelApplication approved = stored();
		publisher.manager.rotateJoinCapability(channel.channelId);
		ChannelApplication legacy = legacy();

		ChannelPullCodec.ApprovalResponse answer = check();

		assertEquals("APPROVED", answer.status);
		assertArrayEquals("a capability was wrapped again",
				approved.getKemCiphertext(), answer.kemCt);
		assertArrayEquals(approved.getEnvelope(), answer.envelope);

		publisher.applicationStore.putApplication(channel.channelId,
				new ChannelApplication(legacy.getDisplayName(),
						legacy.getApplicantEd25519(),
						legacy.getApplicantMlDsa(),
						legacy.getApplicantEphemeralAgreementPub(),
						legacy.getAppliedAtHourMs(),
						ChannelApplication.Status.APPROVED, null, null));
		answer = check();
		assertEquals("UNKNOWN", answer.status);
		assertNull(answer.kemCt);
		assertNull(answer.envelope);
	}

	private ChannelApplication stored() throws Exception {
		List<ChannelApplication> all = publisher.applicationStore
				.getApplications(channel.channelId);
		assertEquals(1, all.size());
		return all.get(0);
	}

	private ChannelApplication legacy() throws Exception {
		ChannelApplication a = stored();
		ChannelApplication old = new ChannelApplication(a.getDisplayName(),
				a.getApplicantEd25519(), a.getApplicantMlDsa(),
				a.getApplicantEphemeralAgreementPub(),
				a.getAppliedAtHourMs(), a.getStatus(), a.getKemCiphertext(),
				a.getEnvelope());
		publisher.applicationStore.putApplication(channel.channelId, old);
		assertNull(stored().getApprovedUnder());
		return old;
	}

	@SuppressWarnings("unchecked")
	private boolean known(byte[] ed) throws Exception {
		java.util.Set<String> known = (java.util.Set<String>) publisher.call(
				"knownSigners", new Class<?>[] {ChannelState.class},
				publisher.store.getChannel(channel.channelId));
		return known.contains(
				org.zerionproject.core.util.StringUtils.toHexString(ed));
	}

	private ChannelPullCodec.ApprovalResponse check() throws Exception {
		org.zerionproject.core.api.crypto.KeyPair keys =
				(org.zerionproject.core.api.crypto.KeyPair) subscriber.call(
						"memberKeys", new Class<?>[] {byte[].class},
						channel.channelId);
		org.zerionproject.core.api.crypto.HybridSignaturePublicKey pub =
				(org.zerionproject.core.api.crypto.HybridSignaturePublicKey)
						keys.getPublic();
		org.zerionproject.core.api.crypto.HybridSignaturePrivateKey priv =
				(org.zerionproject.core.api.crypto.HybridSignaturePrivateKey)
						keys.getPrivate();
		long ts = clock.currentTimeMillis() / ChannelTestNode.HOUR
				* ChannelTestNode.HOUR;
		byte[] sig = subscriber.signatures.signUserCheckApproval(
				subscriber.codec.checkApprovalSignedInput(channel.channelId,
						ts), priv.getEd25519Component(),
				priv.getMlDsaPrivateKey());
		byte[] request = subscriber.pullCodec.encodeCheckApprovalRequest(
				channel.channelId, ts, pub.getEd25519PublicKey(),
				pub.getMlDsaPublicKey(), sig);
		return publisher.pullCodec.decodeApprovalResponse(
				publisher.handle(channel.channelId, request));
	}

	@Test
	public void aBannedApprovedApplicantIsNotApprovedAgain()
			throws Exception {
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");
		approveAndRefresh();
		byte[] approvedKey = held().getApplicantEphemeralAgreementPub();
		publisher.manager.banSubscriber(channel.channelId,
				held().getApplicantEd25519());
		subscriber.manager.leaveChannel(channel.channelId);
		subscriber.seedSubscriber(channel, false, null, null,
				Collections.emptyList());
		clock.advance(ChannelTestNode.HOUR);

		try {
			subscriber.manager.applyToJoin(channel.channelId, "Applicant");
			fail("a banned key was told its application arrived");
		} catch (org.zerionproject.core.api.db.DbException expected) {
		}
		assertArrayEquals("a banned key was approved again", approvedKey,
				held().getApplicantEphemeralAgreementPub());
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

		assertFalse("an older application is refused",
				ChannelTestNode.ackOk(
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

	@Test
	public void anApplicationThePublisherNoLongerHoldsCanBeMadeAgain()
			throws Exception {
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");
		publisher.applicationStore.removeAll(channel.channelId);
		clock.advance(31_000L);
		subscriber.refreshAndReschedule();

		assertEquals("a lost application is not a denial",
				ApplicationStatus.NOT_APPLIED,
				subscriber.manager.getMyApplicationStatus(channel.channelId));
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");
		approveAndRefresh();
		assertArrayEquals(capability, subscriber.store
				.getChannel(channel.channelId).getJoinCapability());
	}

	@Test
	public void aDeniedApplicationStillReadsDenied() throws Exception {
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");
		publisher.manager.denyApplication(channel.channelId,
				held().getApplicantEd25519());
		clock.advance(31_000L);
		subscriber.refreshAndReschedule();

		assertEquals(ApplicationStatus.DENIED,
				subscriber.manager.getMyApplicationStatus(channel.channelId));
	}

	@Test
	public void anOlderApplicantStillHearsDeniedForALostApplication()
			throws Exception {
		subscriber.manager.applyToJoin(channel.channelId, "Applicant");
		clock.advance(31_000L);
		subscriber.refreshAndReschedule();
		assertEquals("the poll went out", 1, checks.size());
		publisher.applicationStore.removeAll(channel.channelId);

		assertEquals("UNKNOWN", statusFor(checks.get(0), null));
		assertEquals("DENIED", statusFor(checks.get(0), 2L));
	}

	private String statusFor(byte[] check, @Nullable Long version)
			throws Exception {
		byte[] request = check;
		if (version != null) {
			org.zerionproject.core.api.data.BdfDictionary d =
					DaggerChannelCodecTestComponent.create()
							.getBdfReaderFactory()
							.createReader(new java.io.ByteArrayInputStream(
									check)).readDictionary();
			d.put("v", version);
			java.io.ByteArrayOutputStream out =
					new java.io.ByteArrayOutputStream();
			org.zerionproject.core.api.data.BdfWriter w =
					DaggerChannelCodecTestComponent.create()
							.getBdfWriterFactory().createWriter(out);
			w.writeDictionary(d);
			w.flush();
			request = out.toByteArray();
		}
		return publisher.pullCodec.decodeApprovalResponse(
				publisher.handle(channel.channelId, request)).status;
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
				String type = publisher.pullCodec.peekType(request);
				if (ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN.equals(type)) {
					applications.add(request.clone());
				} else if (ChannelConstants.WIRE_TYPE_CHECK_APPROVAL
						.equals(type)) {
					checks.add(request.clone());
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
