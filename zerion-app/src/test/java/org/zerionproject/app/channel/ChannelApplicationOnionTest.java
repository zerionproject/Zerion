package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ApplicationStatus;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.db.DbException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelApplicationOnionTest {

	private final Random random = new Random(9173);
	private final List<ChannelTestNode> nodes = new ArrayList<>();
	private final List<String> seenByImpostor =
			Collections.synchronizedList(new ArrayList<>());
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private FakeOnionNetwork network;
	private ChannelTestNode owner;
	private byte[] id;
	private String real;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		network = new FakeOnionNetwork(random);
		owner = new ChannelTestNode(crypto, clock, network);
		nodes.add(owner);
		id = owner.manager.createChannel("name", "about", false, true)
				.getChannelId();
		owner.manager.publishPost(id, "for members", 0L);
		real = owner.store.getChannel(id).getCurrentOnion();
	}

	@After
	public void tearDown() {
		for (ChannelTestNode n : nodes) n.deleteFiles();
	}

	@Test
	public void anImpostorOnTheLinkOnionReceivesNoApplication()
			throws Exception {
		String fake = impostor(false);
		ChannelTestNode applicant = joinThrough(linkWith(fake));

		try {
			applicant.manager.applyToJoin(id, "Applicant");
			fail("an application went to an onion the owner never confirmed");
		} catch (DbException expected) {
		}

		assertEquals(Collections.emptyList(),
				typesSeen(ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN));
		assertTrue(owner.manager.listPendingApplications(id).isEmpty());
		assertNull(new ChannelMyApplicationsStore(applicant.settings,
				DaggerChannelCodecTestComponent.create().getBdfReaderFactory(),
				DaggerChannelCodecTestComponent.create().getBdfWriterFactory())
				.get(id));
	}

	@Test
	public void theApplicationReachesTheOnionTheOwnerConfirms()
			throws Exception {
		String fake = impostor(true);
		ChannelTestNode applicant = joinThrough(linkWith(fake));

		applicant.manager.applyToJoin(id, "Applicant");

		assertEquals(Collections.emptyList(),
				typesSeen(ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN));
		assertEquals(1, owner.manager.listPendingApplications(id).size());
		assertEquals(real, applicant.store.getChannel(id).getCurrentOnion());

		approveAndRefresh(applicant);

		assertNotNull(applicant.store.getChannel(id).getJoinCapability());
		assertEquals(Collections.singletonList("for members"),
				bodies(applicant));
		assertEquals(Collections.emptyList(),
				typesSeen(ChannelConstants.WIRE_TYPE_CHECK_APPROVAL));
	}

	@Test
	public void aCheckForApprovalGoesOnlyToTheOnionTheOwnerConfirms()
			throws Exception {
		String fake = impostor(true);
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		applicant.manager.applyToJoin(id, "Applicant");
		assertEquals(1, owner.manager.listPendingApplications(id).size());
		ChannelState held = applicant.store.getChannel(id);
		applicant.store.putChannel(withOnion(held, fake));

		approveAndRefresh(applicant);

		assertEquals(Collections.emptyList(),
				typesSeen(ChannelConstants.WIRE_TYPE_CHECK_APPROVAL));
		assertNotNull("the approval was read from the owner's onion",
				applicant.store.getChannel(id).getJoinCapability());
		assertEquals(Collections.singletonList("for members"),
				bodies(applicant));
	}

	@Test
	public void aCheckForApprovalIsNotSentWhereNoOwnerConfirms()
			throws Exception {
		String fake = impostor(false);
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		applicant.manager.applyToJoin(id, "Applicant");
		ChannelState held = applicant.store.getChannel(id);
		applicant.store.putChannel(withOnion(held, fake));
		owner.manager.approveApplication(id, applicantKey(applicant));

		clock.advance(ChannelTestNode.HOUR);
		try {
			applicant.manager.refreshChannel(id);
		} catch (DbException expected) {
		}

		assertEquals(Collections.emptyList(),
				typesSeen(ChannelConstants.WIRE_TYPE_CHECK_APPROVAL));
		assertNull(applicant.store.getChannel(id).getJoinCapability());
	}

	@Test
	public void aMemberThatTookNoManifestNamesItselfOnlyToTheOwner()
			throws Exception {
		id = owner.manager.createChannel("open", "about", true)
				.getChannelId();
		real = owner.store.getChannel(id).getCurrentOnion();
		ChannelTestNode member = joinThrough(linkWith(impostor(false)));

		try {
			member.manager.announceMyself(id, "Member");
			fail("a name went to an onion the owner never confirmed");
		} catch (DbException expected) {
		}

		assertEquals(Collections.emptyList(),
				typesSeen(ChannelConstants.WIRE_TYPE_ANNOUNCE));
		assertTrue(owner.manager.getAnnouncedSubscribers(id).isEmpty());
	}

	@Test
	public void anApplicationWhoseAnswerIsLostIsReportedAndCanBeSentAgain()
			throws Exception {
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		ownerReplies((type, request, reply) ->
				ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN.equals(type)
						? new byte[0] : reply);

		try {
			applicant.manager.applyToJoin(id, "Applicant");
			fail("an application without an answer was reported sent");
		} catch (DbException expected) {
		}

		assertEquals(ApplicationStatus.NOT_APPLIED,
				applicant.manager.getMyApplicationStatus(id));
		assertEquals(1, owner.manager.listPendingApplications(id).size());
		byte[] held = owner.manager.listPendingApplications(id).get(0)
				.getApplicantEphemeralAgreementPub();

		ownerReplies((type, request, reply) -> reply);
		applicant.manager.applyToJoin(id, "Applicant");
		assertEquals(ApplicationStatus.PENDING,
				applicant.manager.getMyApplicationStatus(id));
		assertEquals(1, owner.manager.listPendingApplications(id).size());
		assertArrayEquals("the same application was sent again", held,
				owner.manager.listPendingApplications(id).get(0)
						.getApplicantEphemeralAgreementPub());

		approveAndRefresh(applicant);

		assertNotNull(applicant.store.getChannel(id).getJoinCapability());
		assertEquals(Collections.singletonList("for members"),
				bodies(applicant));
	}

	@Test
	public void aLateFirstCopyCannotReplaceTheApplicationSentAgain()
			throws Exception {
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		List<byte[]> held = new ArrayList<>();
		ownerReplies(new Reply() {
			@Override
			public byte[] reply(String type, byte[] request, byte[] reply) {
				return reply;
			}

			@Override
			public boolean forwards(String type, byte[] request) {
				if (!ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN.equals(type)
						|| !held.isEmpty()) {
					return true;
				}
				held.add(request);
				return false;
			}
		});
		try {
			applicant.manager.applyToJoin(id, "Applicant");
			fail("an application without an answer was reported sent");
		} catch (DbException expected) {
		}
		assertTrue(owner.manager.listPendingApplications(id).isEmpty());

		applicant.manager.applyToJoin(id, "Applicant");
		assertEquals(1, owner.manager.listPendingApplications(id).size());
		owner.handle(id, held.get(0));
		assertEquals(1, owner.manager.listPendingApplications(id).size());

		approveAndRefresh(applicant);

		assertNotNull("the approval opens with the key the applicant holds",
				applicant.store.getChannel(id).getJoinCapability());
	}

	@Test
	public void aCheckForApprovalDuringTheApplicationDoesNotLoseIt()
			throws Exception {
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		AtomicBoolean checked = new AtomicBoolean();
		ownerReplies(new Reply() {
			@Override
			public byte[] reply(String type, byte[] request, byte[] reply) {
				return reply;
			}

			@Override
			public boolean forwards(String type, byte[] request) {
				if (ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN.equals(type)
						&& checked.compareAndSet(false, true)) {
					try {
						applicant.manager.refreshChannel(id);
					} catch (Exception ignored) {
					}
				}
				return true;
			}
		});

		applicant.manager.applyToJoin(id, "Applicant");

		assertTrue(checked.get());
		assertEquals(ApplicationStatus.PENDING,
				applicant.manager.getMyApplicationStatus(id));
		approveAndRefresh(applicant);
		assertNotNull(applicant.store.getChannel(id).getJoinCapability());
	}

	@Test
	public void anApprovalThatDoesNotOpenIsForgottenSoTheApplicantCanApplyAgain()
			throws Exception {
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		applicant.manager.applyToJoin(id, "Applicant");
		ownerReplies((type, request, reply) -> {
			if (!ChannelConstants.WIRE_TYPE_CHECK_APPROVAL.equals(type)) {
				return reply;
			}
			BdfDictionary d = read(reply);
			byte[] envelope = d.getOptionalRaw("envelope");
			if (envelope == null) return reply;
			envelope = envelope.clone();
			envelope[envelope.length - 1] ^= 1;
			d.put("envelope", envelope);
			return write(d);
		});

		owner.manager.approveApplication(id, applicantKey(applicant));
		clock.advance(ChannelTestNode.HOUR);
		try {
			applicant.manager.refreshChannel(id);
		} catch (DbException expected) {
		}

		assertNull(applicant.store.getChannel(id).getJoinCapability());
		assertEquals(ApplicationStatus.NOT_APPLIED,
				applicant.manager.getMyApplicationStatus(id));

		ownerReplies((type, request, reply) -> reply);
		applicant.manager.applyToJoin(id, "Applicant");
		assertTrue("an approved key is approved again at once",
				owner.manager.listPendingApplications(id).isEmpty());
		clock.advance(ChannelTestNode.HOUR);
		applicant.manager.refreshChannel(id);
		assertNotNull(applicant.store.getChannel(id).getJoinCapability());
	}

	@Test
	public void aNewNameAfterALostAnswerIsTheOneTheOwnerKeeps()
			throws Exception {
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		List<byte[]> held = new ArrayList<>();
		ownerReplies(new Reply() {
			@Override
			public byte[] reply(String type, byte[] request, byte[] reply) {
				return reply;
			}

			@Override
			public boolean forwards(String type, byte[] request) {
				if (!ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN.equals(type)
						|| !held.isEmpty()) {
					return true;
				}
				held.add(request);
				return false;
			}
		});
		try {
			applicant.manager.applyToJoin(id, "John Smith");
			fail("an application without an answer was reported sent");
		} catch (DbException expected) {
		}

		applicant.manager.applyToJoin(id, "J");
		owner.handle(id, held.get(0));

		assertEquals(1, owner.manager.listPendingApplications(id).size());
		assertEquals("J", owner.manager.listPendingApplications(id).get(0)
				.getDisplayName());
		approveAndRefresh(applicant);
		assertNotNull(applicant.store.getChannel(id).getJoinCapability());
	}

	@Test
	public void aRepeatTheOwnerHoldsIsAcknowledgedWhenItsBudgetIsSpent()
			throws Exception {
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		ownerReplies((type, request, reply) ->
				ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN.equals(type)
						? new byte[0] : reply);
		try {
			applicant.manager.applyToJoin(id, "Applicant");
			fail("an application without an answer was reported sent");
		} catch (DbException expected) {
		}
		Field budget = ChannelManagerImpl.class.getDeclaredField(
				"writeBudget");
		budget.setAccessible(true);
		budget.set(owner.manager, new ChannelWriteBudget(0L, 0L));

		ownerReplies((type, request, reply) -> reply);
		applicant.manager.applyToJoin(id, "Applicant");

		assertEquals(ApplicationStatus.PENDING,
				applicant.manager.getMyApplicationStatus(id));
		approveAndRefresh(applicant);
		assertNotNull(applicant.store.getChannel(id).getJoinCapability());
	}

	@Test
	public void aKemCiphertextThatDoesNotOpenIsForgottenToo()
			throws Exception {
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		applicant.manager.applyToJoin(id, "Applicant");
		ownerReplies((type, request, reply) -> {
			if (!ChannelConstants.WIRE_TYPE_CHECK_APPROVAL.equals(type)) {
				return reply;
			}
			BdfDictionary d = read(reply);
			byte[] kemCt = d.getOptionalRaw("kemCt");
			if (kemCt == null) return reply;
			kemCt = kemCt.clone();
			kemCt[0] ^= 1;
			d.put("kemCt", kemCt);
			return write(d);
		});

		owner.manager.approveApplication(id, applicantKey(applicant));
		clock.advance(ChannelTestNode.HOUR);
		try {
			applicant.manager.refreshChannel(id);
		} catch (DbException expected) {
		}

		assertNull(applicant.store.getChannel(id).getJoinCapability());
		assertEquals(ApplicationStatus.NOT_APPLIED,
				applicant.manager.getMyApplicationStatus(id));
	}

	@Test
	public void aRecordWithoutTheConfirmationMarkReadsAsConfirmed()
			throws Exception {
		ChannelMyApplicationsStore store = new ChannelMyApplicationsStore(
				owner.settings,
				DaggerChannelCodecTestComponent.create().getBdfReaderFactory(),
				DaggerChannelCodecTestComponent.create().getBdfWriterFactory());
		store.put(id, new ChannelMyApplicationsStore.MyApplication("Earlier",
				new byte[] {1}, new byte[] {2}, 0L,
				ApplicationStatus.PENDING, true));

		assertTrue(store.get(id).confirmed);
		store.put(id, new ChannelMyApplicationsStore.MyApplication("Earlier",
				new byte[] {1}, new byte[] {2}, 0L,
				ApplicationStatus.PENDING, true, false));
		assertFalse(store.get(id).confirmed);
	}

	@Test
	public void anApplicationAfterARenameAndARefusalIsStillTheNewest()
			throws Exception {
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		ownerReplies((type, request, reply) ->
				ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN.equals(type)
						? new byte[0] : reply);
		for (String name : new String[] {"First", "Second"}) {
			try {
				applicant.manager.applyToJoin(id, name);
				fail("an application without an answer was reported sent");
			} catch (DbException expected) {
			}
		}
		assertEquals("Second", owner.manager.listPendingApplications(id)
				.get(0).getDisplayName());
		ownerReplies((type, request, reply) -> reply);
		Field budget = ChannelManagerImpl.class.getDeclaredField(
				"writeBudget");
		budget.setAccessible(true);
		Object full = budget.get(owner.manager);
		budget.set(owner.manager, new ChannelWriteBudget(0L, 0L));
		try {
			applicant.manager.applyToJoin(id, "Third");
			fail("a refused application was reported sent");
		} catch (DbException expected) {
		}
		budget.set(owner.manager, full);

		applicant.manager.applyToJoin(id, "Third");

		assertEquals("Third", owner.manager.listPendingApplications(id)
				.get(0).getDisplayName());
		approveAndRefresh(applicant);
		assertNotNull(applicant.store.getChannel(id).getJoinCapability());
	}

	@Test
	public void aRecordWithoutARememberedHourStillDatesARenameLater()
			throws Exception {
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		List<byte[]> held = new ArrayList<>();
		ownerReplies(new Reply() {
			@Override
			public byte[] reply(String type, byte[] request, byte[] reply) {
				return reply;
			}

			@Override
			public boolean forwards(String type, byte[] request) {
				if (!ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN.equals(type)
						|| !held.isEmpty()) {
					return true;
				}
				held.add(request);
				return false;
			}
		});
		try {
			applicant.manager.applyToJoin(id, "Earlier");
			fail("an application without an answer was reported sent");
		} catch (DbException expected) {
		}
		applicant.settings.deleteSettings("zerion-channels-my-applications",
				Collections.singletonList(ChannelStore.hex(id) + ".floor"));

		applicant.manager.applyToJoin(id, "Later");
		owner.handle(id, held.get(0));

		assertEquals("Later", owner.manager.listPendingApplications(id)
				.get(0).getDisplayName());
	}

	@Test
	public void aRefusedCopyDoesNotRemoveAConfirmedApplication()
			throws Exception {
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		applicant.manager.applyToJoin(id, "Applicant");
		ChannelMyApplicationsStore.MyApplication held =
				myApplication(applicant);
		assertTrue(held.confirmed);

		Object kept = applicant.call("settleApplication",
				new Class<?>[] {byte[].class, byte[].class, boolean.class},
				id, held.ephemeralAgreementPub, false);

		assertEquals(Boolean.TRUE, kept);
		assertEquals(ApplicationStatus.PENDING,
				applicant.manager.getMyApplicationStatus(id));
	}

	@Test
	public void anApplicationOlderThanTheOneTheOwnerHoldsIsNotReportedSent()
			throws Exception {
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		ownerReplies((type, request, reply) ->
				ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN.equals(type)
						? new byte[0] : reply);
		for (String name : new String[] {"First", "Second"}) {
			try {
				applicant.manager.applyToJoin(id, name);
				fail("an application without an answer was reported sent");
			} catch (DbException expected) {
			}
		}
		ownerReplies((type, request, reply) -> reply);
		applicant.manager.leaveChannel(id);
		applicant.manager.joinChannel(applicant.manager.parseInviteLink(
				owner.manager.exportInviteLink(id)));

		try {
			applicant.manager.applyToJoin(id, "Third");
			fail("an application the owner did not keep was reported sent");
		} catch (org.zerionproject.app.api.channel
				.ChannelApplicationUnconfirmedException expected) {
		}
		assertEquals(ApplicationStatus.NOT_APPLIED,
				applicant.manager.getMyApplicationStatus(id));
		assertEquals("Second", owner.manager.listPendingApplications(id)
				.get(0).getDisplayName());

		clock.advance(2L * ChannelTestNode.HOUR);
		applicant.manager.applyToJoin(id, "Third");
		assertEquals(ApplicationStatus.PENDING,
				applicant.manager.getMyApplicationStatus(id));
		assertEquals("Third", owner.manager.listPendingApplications(id)
				.get(0).getDisplayName());
		approveAndRefresh(applicant);
		assertNotNull(applicant.store.getChannel(id).getJoinCapability());
	}

	@Test
	public void anApplicationThatCannotBeWrittenIsNotCalledUnconfirmed()
			throws Exception {
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		applicant.manager.applyToJoin(id, "Applicant");
		approveAndRefresh(applicant);
		assertNotNull(applicant.store.getChannel(id).getJoinCapability());
		Field codec = ChannelManagerImpl.class.getDeclaredField(
				"pullCodecInstance");
		codec.setAccessible(true);
		codec.set(applicant.manager, new ChannelPullCodec(
				DaggerChannelCodecTestComponent.create().getBdfReaderFactory(),
				out -> (BdfWriter) java.lang.reflect.Proxy.newProxyInstance(
						BdfWriter.class.getClassLoader(),
						new Class<?>[] {BdfWriter.class},
						(proxy, method, args) -> {
							throw new IOException();
						})));

		try {
			applicant.manager.applyToJoin(id, "Again");
			fail("an application that was never written was reported sent");
		} catch (org.zerionproject.app.api.channel
				.ChannelApplicationUnconfirmedException e) {
			fail("an application that was never sent was called unconfirmed");
		} catch (DbException expected) {
		}
	}

	@Test
	public void anApprovalWithAnUnreadableKeyIsForgotten() throws Exception {
		assertForgottenAfterApprovalWithKey(new byte[] {1, 2, 3});
	}

	@Test
	public void anApprovalWithNoKeyAndNoAccessIsForgotten()
			throws Exception {
		assertForgottenAfterApprovalWithKey(null);
	}

	@Test
	public void aSpentBudgetRefusesAnApplicationTheOwnerDoesNotHold()
			throws Exception {
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		Field budget = ChannelManagerImpl.class.getDeclaredField(
				"writeBudget");
		budget.setAccessible(true);
		budget.set(owner.manager, new ChannelWriteBudget(0L, 0L));

		try {
			applicant.manager.applyToJoin(id, "Applicant");
			fail("a refused application was reported sent");
		} catch (DbException expected) {
		}

		assertTrue(owner.manager.listPendingApplications(id).isEmpty());
		assertNull(myApplication(applicant));
	}

	private void assertForgottenAfterApprovalWithKey(@Nullable byte[] key)
			throws Exception {
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		applicant.manager.applyToJoin(id, "Applicant");
		ChannelMyApplicationsStore.MyApplication held =
				myApplication(applicant);
		new ChannelMyApplicationsStore(applicant.settings,
				DaggerChannelCodecTestComponent.create().getBdfReaderFactory(),
				DaggerChannelCodecTestComponent.create().getBdfWriterFactory())
				.put(id, new ChannelMyApplicationsStore.MyApplication(
						held.displayName, key, held.ephemeralAgreementPub,
						held.appliedAtHourMs, ApplicationStatus.PENDING,
						true, true));

		owner.manager.approveApplication(id, applicantKey(applicant));
		clock.advance(ChannelTestNode.HOUR);
		try {
			applicant.manager.refreshChannel(id);
		} catch (DbException expected) {
		}

		assertNull(applicant.store.getChannel(id).getJoinCapability());
		assertEquals(ApplicationStatus.NOT_APPLIED,
				applicant.manager.getMyApplicationStatus(id));
	}

	@Test
	public void anApplicationTheOwnerRefusesIsNotShownAsPending()
			throws Exception {
		id = owner.manager.createChannel("closed", "about", false, false)
				.getChannelId();
		real = owner.store.getChannel(id).getCurrentOnion();
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));

		try {
			applicant.manager.applyToJoin(id, "Applicant");
			fail("a refused application was reported sent");
		} catch (DbException expected) {
		}

		assertNull(myApplication(applicant));
		assertTrue(owner.manager.listPendingApplications(id).isEmpty());
	}

	private interface Reply {
		byte[] reply(String type, byte[] request, byte[] reply)
				throws Exception;

		default boolean forwards(String type, byte[] request) {
			return true;
		}
	}

	private void ownerReplies(Reply replies) throws Exception {
		network.bindServer(id, owner.store.getChannel(id)
				.getOnionPrivateKey(), request -> {
			try {
				String type = read(request).getString("type");
				if (!replies.forwards(type, request)) return new byte[0];
				return replies.reply(type, request,
						owner.handle(id, request));
			} catch (Exception e) {
				return new byte[0];
			}
		});
	}

	@Nullable
	private ChannelMyApplicationsStore.MyApplication myApplication(
			ChannelTestNode applicant) throws Exception {
		return new ChannelMyApplicationsStore(applicant.settings,
				DaggerChannelCodecTestComponent.create().getBdfReaderFactory(),
				DaggerChannelCodecTestComponent.create().getBdfWriterFactory())
				.get(id);
	}

	private void approveAndRefresh(ChannelTestNode applicant)
			throws Exception {
		owner.manager.approveApplication(id, applicantKey(applicant));
		clock.advance(ChannelTestNode.HOUR);
		applicant.manager.refreshChannel(id);
	}

	private byte[] applicantKey(ChannelTestNode applicant) throws Exception {
		return java.util.Arrays.copyOfRange(
				applicant.manager.getMyChannelPublicKey(id), 0, 32);
	}

	private String impostor(boolean asksForTheRealOnion) throws IOException {
		return network.bindServer(id, "key-impostor", request -> {
			try {
				BdfDictionary d = read(request);
				String type = d.getString("type");
				seenByImpostor.add(type);
				if (asksForTheRealOnion && ChannelConstants
						.WIRE_TYPE_CONFIRM_ONION.equals(type)) {
					d.put("onion", real);
					return network.requestFromOnion(real, write(d));
				}
				return network.requestFromOnion(real, request);
			} catch (Exception e) {
				return new byte[0];
			}
		}).getOnionAddress();
	}

	private List<String> typesSeen(String type) {
		List<String> out = new ArrayList<>();
		synchronized (seenByImpostor) {
			for (String t : seenByImpostor) {
				if (t.equals(type)) out.add(t);
			}
		}
		return out;
	}

	private String linkWith(String onion) throws Exception {
		return owner.manager.exportInviteLink(id).replace(real, onion);
	}

	private ChannelTestNode joinThrough(String link) throws Exception {
		ChannelTestNode n = new ChannelTestNode(crypto, clock, network, null,
				ChannelTestNode.identity(
						crypto.generateHybridSignatureKeyPair()));
		nodes.add(n);
		n.manager.joinChannel(n.manager.parseInviteLink(link));
		return n;
	}

	private List<String> bodies(ChannelTestNode n) throws Exception {
		List<String> out = new ArrayList<>();
		for (org.zerionproject.app.api.channel.ChannelPost p
				: n.manager.getRecentPosts(id, 10L)) {
			out.add(p.getBody());
		}
		return out;
	}

	private static ChannelState withOnion(ChannelState s, String onion) {
		return new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(), s.getPublisherMlDsaPubKey(),
				s.getName(), s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), onion, s.getManifestSeq(),
				s.weArePublisher(), s.getHighestKnownPostSeq(),
				s.getContentKeyHash(), s.getContentKey(),
				s.getActiveDelegations(), s.getRevokedDelegationSeqs(),
				s.getNextDelegationSeq(), s.getOnionPrivateKey(),
				s.getPinnedPostSeq(), s.requiresApproval(),
				s.getRetiredDelegations());
	}

	private static BdfDictionary read(byte[] bytes) throws Exception {
		BdfReader r = DaggerChannelCodecTestComponent.create()
				.getBdfReaderFactory()
				.createReader(new ByteArrayInputStream(bytes));
		return r.readDictionary();
	}

	private static byte[] write(BdfDictionary d) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = DaggerChannelCodecTestComponent.create()
				.getBdfWriterFactory().createWriter(out);
		w.writeDictionary(d);
		w.flush();
		return out.toByteArray();
	}
}
