package org.zerionproject.app.introduction;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.MlKemKeyPair;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.identity.AuthorFactory;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.app.introduction.IntroductionTestNet.Call;
import org.zerionproject.app.introduction.IntroductionTestNet.Party;
import org.zerionproject.app.introduction.IntroductionTestNet.Recorder;
import org.zerionproject.app.introduction.IntroductionTestNet.Sent;
import org.zerionproject.app.introduction.IntroductionTestNet.Transition;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.inject.Inject;

import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_ACTIVATE_MAC;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_ALICE_ACTIVATE_KEY;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_ALICE_ACTIVATE_KEY_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_ALICE_AUTH_MAC_KEY_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_ALICE_AUTH_NONCE_KEY_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_ALICE_MAC_KEY;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_AUTH_MAC;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_AUTH_NONCE;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_AUTH_SECRET_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_AUTH_SIGN;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_BOB_ACTIVATE_KEY;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_BOB_ACTIVATE_KEY_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_BOB_AUTH_MAC_KEY_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_BOB_AUTH_NONCE_KEY_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_BOB_MAC_KEY;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_FINAL_SECRET_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_MASTER_KEY;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_PRE_MASTER_KEY;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_ROOT_KEY_V2;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_SHARED_SECRET_V2;
import static org.zerionproject.app.introduction.IntroduceeState.AWAIT_ACTIVATE;
import static org.zerionproject.app.introduction.IntroduceeState.AWAIT_AUTH;
import static org.zerionproject.app.introduction.IntroduceeState.LOCAL_ACCEPTED;
import static org.zerionproject.app.introduction.IntroduceeState.REMOTE_ACCEPTED;
import static org.zerionproject.app.introduction.IntroduceeState.START;
import static org.zerionproject.app.introduction.IntroductionConstants.SESSION_KEY_MASTER_KEY;
import static org.zerionproject.app.introduction.KeySchedule.ANNOUNCEMENT_ID;
import static org.zerionproject.app.introduction.KeySchedule.ANNOUNCEMENT_KEY;
import static org.zerionproject.app.introduction.KeySchedule.CURRENT_VERSION;
import static org.zerionproject.app.introduction.KeySchedule.EARLIER_VERSION;
import static org.zerionproject.app.introduction.KeySchedule.SEPARATED_VERSION;
import static org.zerionproject.app.introduction.MessageType.ABORT;
import static org.zerionproject.app.introduction.MessageType.ACCEPT;
import static org.zerionproject.app.introduction.MessageType.ACTIVATE;
import static org.zerionproject.app.introduction.MessageType.AUTH;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IntroductionKeySeparationTest extends BrambleTestCase {

	private static final String[] SEPARATED_LABELS = {
			LABEL_SHARED_SECRET_V2, LABEL_AUTH_SECRET_V2,
			LABEL_ALICE_AUTH_MAC_KEY_V2, LABEL_BOB_AUTH_MAC_KEY_V2,
			LABEL_ALICE_AUTH_NONCE_KEY_V2, LABEL_BOB_AUTH_NONCE_KEY_V2,
			LABEL_FINAL_SECRET_V2, LABEL_ALICE_ACTIVATE_KEY_V2,
			LABEL_BOB_ACTIVATE_KEY_V2, LABEL_ROOT_KEY_V2
	};

	private static final String[] EARLIER_LABELS = {
			LABEL_MASTER_KEY, LABEL_PRE_MASTER_KEY, LABEL_ALICE_MAC_KEY,
			LABEL_BOB_MAC_KEY, LABEL_ALICE_ACTIVATE_KEY,
			LABEL_BOB_ACTIVATE_KEY
	};

	private static final String[] STORED_SESSION_KEYS = {
			"sessionId", "role", "state", "requestTimestamp", "introducer",
			"local", "remote"
	};

	private static final String[] STORED_LOCAL_KEYS = {
			"alice", "ephemeralPublicKey", "transportProperties",
			"acceptTimestamp", "macKey", "mlDsaPubKey",
			"mlKemEphemeralPublicKey", "localTimestamp",
			"lastLocalMessageId", "ephemeralPrivateKey",
			"mlKemEphemeralPrivateKey", "ownKemSecret"
	};

	private static final String[] STORED_REMOTE_KEYS = {
			"alice", "ephemeralPublicKey", "transportProperties",
			"acceptTimestamp", "macKey", "mlDsaPubKey",
			"mlKemEphemeralPublicKey", "remoteAuthor", "lastRemoteMessageId"
	};

	@Inject
	ClientHelper clientHelper;
	@Inject
	CryptoComponent cryptoComponent;
	@Inject
	MlKemProvider mlKemProvider;
	@Inject
	AuthorFactory authorFactory;
	@Inject
	MetadataEncoder metadataEncoder;
	@Inject
	MetadataParser metadataParser;

	private final MessageEncoder messageEncoder;
	private final MessageParser messageParser;

	public IntroductionKeySeparationTest() {
		IntroductionIntegrationTestComponent component =
				DaggerIntroductionIntegrationTestComponent.builder().build();
		IntroductionIntegrationTestComponent.Helper
				.injectEagerSingletons(component);
		component.inject(this);
		messageEncoder = component.getMessageEncoder();
		messageParser = component.getMessageParser();
	}

	private IntroductionTestNet net(int aliceVersion, int bobVersion) {
		return new IntroductionTestNet(clientHelper, cryptoComponent,
				mlKemProvider, authorFactory, metadataEncoder, metadataParser,
				messageEncoder, messageParser, aliceVersion, bobVersion);
	}

	private static <T> T only(List<T> list) {
		assertEquals(1, list.size());
		return list.get(0);
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			boolean match = true;
			for (int j = 0; j < needle.length && match; j++) {
				match = haystack[i + j] == needle[j];
			}
			if (match) return true;
		}
		return false;
	}

	private static boolean allZero(byte[] b) {
		for (byte x : b) if (x != 0) return false;
		return true;
	}

	private static BdfDictionary properties(Sent accept) throws Exception {
		return accept.body.getDictionary(5);
	}

	private static boolean announces(Sent accept) throws Exception {
		return properties(accept).containsKey(ANNOUNCEMENT_ID.getString());
	}

	private static boolean usesAny(Recorder r, String[] labels) {
		for (String label : labels) if (r.usesLabel(label)) return true;
		return false;
	}

	private static void assertSeparated(Party p) {
		assertTrue(p.recorder.usesLabel(LABEL_SHARED_SECRET_V2));
		assertFalse(usesAny(p.recorder, EARLIER_LABELS));
	}

	private static void assertEarlier(Party p) {
		assertTrue(p.recorder.usesLabel(LABEL_PRE_MASTER_KEY));
		assertFalse(usesAny(p.recorder, SEPARATED_LABELS));
	}

	private static void assertCompleted(IntroductionTestNet net)
			throws Exception {
		for (Party p : net.parties()) {
			assertEquals(1, p.contactRoots.size());
			assertEquals(1, p.rotationRoots.size());
			assertArrayEquals(p.contactRoots.get(0), p.rotationRoots.get(0));
			assertEquals(1, p.activations);
			assertEquals(1, p.remoteProperties.size());
			assertEquals(1, p.sent(ACTIVATE).size());
			assertTrue(p.sent(ABORT).isEmpty());
			assertTrue(p.received(ABORT).isEmpty());
			IntroduceeSession s = p.session();
			assertNotNull(s);
			assertEquals(START, s.getState());
			assertNull(s.getMasterKey());
		}
		assertArrayEquals(net.alice().contactRoots.get(0),
				net.bob().contactRoots.get(0));
		assertEquals(IntroducerState.START,
				net.introducerSession().getState());
	}

	private static void assertFailedWithoutAContact(IntroductionTestNet net)
			throws Exception {
		for (Party p : net.parties()) {
			assertTrue(p.contactRoots.isEmpty());
			assertTrue(p.rotationRoots.isEmpty());
			assertTrue(p.remoteProperties.isEmpty());
			assertEquals(0, p.activations);
			assertTrue(p.sent(ACTIVATE).isEmpty());
			assertFalse(p.sent(ABORT).isEmpty()
					&& p.received(ABORT).isEmpty());
			IntroduceeSession s = p.session();
			assertNotNull(s);
			assertEquals(START, s.getState());
			assertNull(s.getMasterKey());
			assertNull(s.getLocal().macKey);
			assertNull(s.getRemote().macKey);
			assertNull(s.getLocal().ownKemSecret);
			assertTrue(p.recorder.derivations(LABEL_ROOT_KEY_V2).isEmpty());
			assertTrue(p.recorder.calls("deriveKey", LABEL_MASTER_KEY)
					.isEmpty());
		}
		assertEquals(IntroducerState.START,
				net.introducerSession().getState());
	}

	private static void assertStoredAsBefore(Party p, boolean masterKey)
			throws Exception {
		BdfDictionary stored = p.storedSession();
		Set<String> expected = new TreeSet<>(Arrays.asList(
				STORED_SESSION_KEYS));
		if (masterKey) expected.add("masterKey");
		assertEquals(expected, new TreeSet<>(stored.keySet()));
		assertEquals(new TreeSet<>(Arrays.asList(STORED_LOCAL_KEYS)),
				new TreeSet<>(stored.getDictionary("local").keySet()));
		assertEquals(new TreeSet<>(Arrays.asList(STORED_REMOTE_KEYS)),
				new TreeSet<>(stored.getDictionary("remote").keySet()));
	}

	private static List<Boolean> authMacChecks(Party p) {
		List<Boolean> results = new ArrayList<>();
		for (Call c : p.recorder.calls("verifyMac", LABEL_AUTH_MAC)) {
			results.add(c.verified);
		}
		return results;
	}

	private static Map<TransportId, TransportProperties> withVersion(
			Map<TransportId, TransportProperties> properties, String value) {
		Map<TransportId, TransportProperties> changed =
				new HashMap<>(properties);
		TransportProperties announcement = new TransportProperties();
		announcement.put(ANNOUNCEMENT_KEY, value);
		changed.put(ANNOUNCEMENT_ID, announcement);
		return changed;
	}

	@Test
	public void twoCurrentIntroduceesSeparateTheRootFromTheKeysOfTheRun()
			throws Exception {
		for (boolean aliceFirst : new boolean[] {true, false}) {
			IntroductionTestNet net = net(CURRENT_VERSION, CURRENT_VERSION);
			net.introduceAndAccept(aliceFirst ? net.alice() : net.bob());
			assertCompleted(net);
			for (Party p : net.parties()) {
				assertSeparated(p);
				assertTrue(announces(only(p.sent(ACCEPT))));
				assertTrue(announces(only(p.received(ACCEPT))));
				assertArrayEquals(p.contactRoots.get(0),
						only(p.recorder.derivations(LABEL_ROOT_KEY_V2))
								.result);
				assertEquals(net.other(p).properties,
						only(p.remoteProperties));
				assertFalse(only(p.remoteProperties)
						.containsKey(ANNOUNCEMENT_ID));
			}
		}
	}

	@Test
	public void theRootIsNeverAKeyOrAnInputOrAWireValueDuringTheIntroduction()
			throws Exception {
		IntroductionTestNet net = net(CURRENT_VERSION, CURRENT_VERSION);
		net.introduceAndAccept(net.alice());
		assertCompleted(net);
		for (Party p : net.parties()) {
			boolean alice = p == net.alice();
			Recorder r = p.recorder;
			byte[] root = p.contactRoots.get(0);
			assertFalse("the root keyed a step of the introduction",
					r.usedAsKey(root));
			assertFalse("the root was an input of a step of the introduction",
					r.usedAsInput(root));
			for (Sent s : net.wire) {
				assertFalse(contains(s.message.getBody(), root));
			}

			Call shared = r.derivations(LABEL_SHARED_SECRET_V2).get(0);
			for (Call c : r.derivations(LABEL_SHARED_SECRET_V2)) {
				assertArrayEquals(shared.result, c.result);
			}
			Call finalSecret = only(r.derivations(LABEL_FINAL_SECRET_V2));
			assertArrayEquals(shared.result, only(finalSecret.keys));
			Call rootCall = only(r.derivations(LABEL_ROOT_KEY_V2));
			assertArrayEquals(finalSecret.result, only(rootCall.keys));
			assertTrue(rootCall.inputs.isEmpty());
			assertEquals(1, r.producing(root).size());

			Call macKey = only(r.derivations(alice
					? LABEL_ALICE_AUTH_MAC_KEY_V2
					: LABEL_BOB_AUTH_MAC_KEY_V2));
			Call nonceKey = only(r.derivations(alice
					? LABEL_ALICE_AUTH_NONCE_KEY_V2
					: LABEL_BOB_AUTH_NONCE_KEY_V2));
			assertArrayEquals(only(macKey.keys), only(nonceKey.keys));
			Call authSecret = only(r.producing(only(macKey.keys)));
			assertEquals(LABEL_AUTH_SECRET_V2, authSecret.label);
			assertArrayEquals(shared.result, only(authSecret.keys));
			Call activateKey = only(r.derivations(alice
					? LABEL_ALICE_ACTIVATE_KEY_V2
					: LABEL_BOB_ACTIVATE_KEY_V2));
			assertArrayEquals(finalSecret.result, only(activateKey.keys));

			Sent auth = only(p.sent(AUTH));
			Call authMac = only(r.producing(auth.body.getRaw(3)));
			assertEquals("mac", authMac.method);
			assertEquals(LABEL_AUTH_MAC, authMac.label);
			assertArrayEquals(macKey.result, only(authMac.keys));
			assertArrayEquals(auth.body.getRaw(3), cryptoComponent.mac(
					LABEL_AUTH_MAC, new SecretKey(macKey.result),
					authMac.inputs.toArray(new byte[0][])));

			Call signature = only(r.producing(auth.body.getRaw(4)));
			assertEquals("hybridSign", signature.method);
			assertEquals(LABEL_AUTH_SIGN, signature.label);
			assertTrue(signature.keys.isEmpty());
			byte[] nonce = only(signature.inputs);
			Call nonceCall = only(r.producing(nonce));
			assertEquals("mac", nonceCall.method);
			assertEquals(LABEL_AUTH_NONCE, nonceCall.label);
			assertArrayEquals(nonceKey.result, only(nonceCall.keys));
			assertTrue(nonceCall.inputs.isEmpty());
			assertArrayEquals(nonce, cryptoComponent.mac(LABEL_AUTH_NONCE,
					new SecretKey(nonceKey.result)));

			Sent activate = only(p.sent(ACTIVATE));
			Call activateMac = only(r.producing(activate.body.getRaw(3)));
			assertEquals("mac", activateMac.method);
			assertEquals(LABEL_ACTIVATE_MAC, activateMac.label);
			assertArrayEquals(activateKey.result, only(activateMac.keys));
			assertTrue(activateMac.inputs.isEmpty());
			assertArrayEquals(activate.body.getRaw(3), cryptoComponent.mac(
					LABEL_ACTIVATE_MAC, new SecretKey(activateKey.result)));

			byte[][] used = {
					shared.result, authSecret.result, macKey.result,
					nonceKey.result, finalSecret.result, activateKey.result,
					nonce, auth.body.getRaw(3), activate.body.getRaw(3)
			};
			for (byte[] value : used) {
				assertFalse(Arrays.equals(root, value));
			}
		}
	}

	@Test
	public void theSecretsBehindTheSeparatedKeysAreWipedAfterUse()
			throws Exception {
		IntroductionTestNet net = net(CURRENT_VERSION, CURRENT_VERSION);
		net.introduceAndAccept(net.bob());
		assertCompleted(net);
		String[] wiped = {
				LABEL_SHARED_SECRET_V2, LABEL_AUTH_SECRET_V2,
				LABEL_ALICE_AUTH_MAC_KEY_V2, LABEL_BOB_AUTH_MAC_KEY_V2,
				LABEL_ALICE_AUTH_NONCE_KEY_V2, LABEL_BOB_AUTH_NONCE_KEY_V2,
				LABEL_FINAL_SECRET_V2
		};
		for (Party p : net.parties()) {
			for (String label : wiped) {
				List<Call> derived = p.recorder.derivations(label);
				assertFalse(label, derived.isEmpty());
				for (Call c : derived) {
					assertNotNull(c.derived);
					assertFalse(allZero(c.result));
					assertTrue(label, allZero(c.derived.getBytes()));
				}
			}
			assertEquals(3,
					p.recorder.derivations(LABEL_SHARED_SECRET_V2).size());
			assertEquals(2,
					p.recorder.derivations(LABEL_AUTH_SECRET_V2).size());
		}
	}

	@Test
	public void aSeparatedSessionKeepsNoKeyInStorageWhileItWaitsForTheAuth()
			throws Exception {
		IntroductionTestNet net = net(CURRENT_VERSION, CURRENT_VERSION);
		int[] checked = {0};
		net.setHook((to, type) -> {
			if (to == null || type != AUTH) return;
			IntroduceeSession s = to.session();
			assertNotNull(s);
			assertEquals(AWAIT_AUTH, s.getState());
			assertNull(s.getMasterKey());
			assertNull(s.getLocal().macKey);
			assertNull(s.getRemote().macKey);
			assertNotNull(s.getLocal().ownKemSecret);
			assertFalse(to.storedSession()
					.containsKey(SESSION_KEY_MASTER_KEY));
			assertStoredAsBefore(to, false);
			checked[0]++;
		});
		net.introduceAndAccept(net.alice());
		assertEquals(2, checked[0]);
		assertCompleted(net);
		for (Party p : net.parties()) assertSeparated(p);
	}

	@Test
	public void anIntroduceeThatDoesNotAnnounceGetsTheEarlierKeySchedule()
			throws Exception {
		for (boolean earlierIsAlice : new boolean[] {true, false}) {
			for (boolean earlierFirst : new boolean[] {true, false}) {
				IntroductionTestNet net = net(
						earlierIsAlice ? EARLIER_VERSION : CURRENT_VERSION,
						earlierIsAlice ? CURRENT_VERSION : EARLIER_VERSION);
				Party earlier = earlierIsAlice ? net.alice() : net.bob();
				Party current = net.other(earlier);
				net.introduceAndAccept(earlierFirst ? earlier : current);
				assertCompleted(net);
				assertFalse(announces(only(earlier.sent(ACCEPT))));
				assertTrue(announces(only(current.sent(ACCEPT))));
				assertTrue(announces(only(earlier.received(ACCEPT))));
				assertEquals(clientHelper.toDictionary(earlier.properties),
						properties(only(earlier.sent(ACCEPT))));
				assertEquals(earlier.properties,
						only(current.remoteProperties));
				for (Party p : net.parties()) {
					assertEarlier(p);
					byte[] root = p.contactRoots.get(0);
					assertArrayEquals(root, only(p.recorder.calls("deriveKey",
							LABEL_MASTER_KEY)).result);
					assertTrue(p.recorder.usedAsKey(root));
				}
			}
		}
	}

	@Test
	public void twoEarlierIntroduceesRunTheEarlierKeyScheduleUnchanged()
			throws Exception {
		IntroductionTestNet net = net(EARLIER_VERSION, EARLIER_VERSION);
		net.introduceAndAccept(net.alice());
		assertCompleted(net);
		for (Party p : net.parties()) {
			assertEarlier(p);
			assertEquals(clientHelper.toDictionary(p.properties),
					properties(only(p.sent(ACCEPT))));
			assertEquals(clientHelper.toDictionary(p.properties),
					properties(only(net.other(p).received(ACCEPT))));
			byte[] root = p.contactRoots.get(0);
			assertTrue(p.recorder.usedAsKey(root));
			Call activateKey = only(p.recorder.derivations(
					p == net.alice() ? LABEL_ALICE_ACTIVATE_KEY
							: LABEL_BOB_ACTIVATE_KEY));
			assertArrayEquals(root, only(activateKey.keys));
		}
	}

	@Test
	public void anIntroducerOfTheEarlierReleaseForwardsBothAnnouncements()
			throws Exception {
		IntroductionTestNet net = net(CURRENT_VERSION, CURRENT_VERSION);
		net.introduceAndAccept(net.bob());
		for (Party p : net.parties()) {
			BdfDictionary sent = properties(only(p.sent(ACCEPT)));
			BdfDictionary forwarded =
					properties(only(net.other(p).received(ACCEPT)));
			assertTrue(sent.containsKey(ANNOUNCEMENT_ID.getString()));
			assertEquals(String.valueOf(SEPARATED_VERSION),
					sent.getDictionary(ANNOUNCEMENT_ID.getString())
							.getString(ANNOUNCEMENT_KEY));
			assertArrayEquals(clientHelper.toByteArray(sent),
					clientHelper.toByteArray(forwarded));
			assertEquals(9, only(p.sent(ACCEPT)).body.size());
			assertEquals(6, only(p.sent(AUTH)).body.size());
			assertEquals(4, only(p.sent(ACTIVATE)).body.size());
		}
		assertCompleted(net);
		for (Party p : net.parties()) assertSeparated(p);
	}

	@Test
	public void strippingOneAnnouncementFailsTheIntroduction()
			throws Exception {
		for (boolean stripAlice : new boolean[] {true, false}) {
			for (boolean aliceFirst : new boolean[] {true, false}) {
				IntroductionTestNet net =
						net(CURRENT_VERSION, CURRENT_VERSION);
				Party stripped = stripAlice ? net.alice() : net.bob();
				Party deceived = net.other(stripped);
				net.setRewrite((to, properties) -> to == deceived
						? KeySchedule.withoutAnnouncement(properties)
						: properties);
				net.introduceAndAccept(aliceFirst ? net.alice() : net.bob());
				assertTrue(announces(only(stripped.sent(ACCEPT))));
				assertFalse(announces(only(deceived.received(ACCEPT))));
				assertTrue(announces(only(deceived.sent(ACCEPT))));
				assertTrue(announces(only(stripped.received(ACCEPT))));
				assertFailedWithoutAContact(net);
				assertTrue(stripped.recorder
						.usesLabel(LABEL_SHARED_SECRET_V2));
				assertFalse(usesAny(stripped.recorder, EARLIER_LABELS));
				assertTrue(deceived.recorder.usesLabel(LABEL_PRE_MASTER_KEY));
				assertFalse(usesAny(deceived.recorder, SEPARATED_LABELS));
				List<Boolean> checks = new ArrayList<>(authMacChecks(stripped));
				checks.addAll(authMacChecks(deceived));
				assertFalse(checks.isEmpty());
				assertFalse(checks.contains(Boolean.TRUE));
			}
		}
	}

	@Test
	public void strippingBothAnnouncementsFailsTheIntroduction()
			throws Exception {
		for (boolean aliceFirst : new boolean[] {true, false}) {
			IntroductionTestNet net = net(CURRENT_VERSION, CURRENT_VERSION);
			net.setRewrite((to, properties) ->
					KeySchedule.withoutAnnouncement(properties));
			net.introduceAndAccept(aliceFirst ? net.alice() : net.bob());
			for (Party p : net.parties()) {
				assertTrue(announces(only(p.sent(ACCEPT))));
				assertFalse(announces(only(p.received(ACCEPT))));
			}
			assertFailedWithoutAContact(net);
			List<Boolean> checks = new ArrayList<>();
			for (Party p : net.parties()) {
				assertTrue(p.recorder.usesLabel(LABEL_PRE_MASTER_KEY));
				assertFalse(usesAny(p.recorder, SEPARATED_LABELS));
				checks.addAll(authMacChecks(p));
			}
			assertFalse(checks.isEmpty());
			assertFalse(checks.contains(Boolean.TRUE));
		}
	}

	@Test
	public void addingAnAnnouncementForAnEarlierIntroduceeFailsTheIntroduction()
			throws Exception {
		for (boolean earlierIsAlice : new boolean[] {true, false}) {
			IntroductionTestNet net = net(
					earlierIsAlice ? EARLIER_VERSION : CURRENT_VERSION,
					earlierIsAlice ? CURRENT_VERSION : EARLIER_VERSION);
			Party earlier = earlierIsAlice ? net.alice() : net.bob();
			Party current = net.other(earlier);
			net.setRewrite((to, properties) -> to == current
					? KeySchedule.announce(properties, SEPARATED_VERSION)
					: properties);
			net.introduceAndAccept(earlier);
			assertFalse(announces(only(earlier.sent(ACCEPT))));
			assertTrue(announces(only(current.received(ACCEPT))));
			assertFailedWithoutAContact(net);
			assertTrue(current.recorder.usesLabel(LABEL_SHARED_SECRET_V2));
			assertFalse(usesAny(earlier.recorder, SEPARATED_LABELS));
		}
	}

	@Test
	public void changingAnAnnouncedVersionFailsTheIntroduction()
			throws Exception {
		for (boolean changeAlice : new boolean[] {true, false}) {
			IntroductionTestNet net = net(CURRENT_VERSION, CURRENT_VERSION);
			Party changed = changeAlice ? net.alice() : net.bob();
			Party deceived = net.other(changed);
			net.setRewrite((to, properties) -> to == deceived
					? withVersion(properties,
					String.valueOf(SEPARATED_VERSION + 1))
					: properties);
			net.introduceAndAccept(changed);
			assertFailedWithoutAContact(net);
			byte[] seenByChanged = changed.recorder
					.derivations(LABEL_SHARED_SECRET_V2).get(0).result;
			byte[] seenByDeceived = deceived.recorder
					.derivations(LABEL_SHARED_SECRET_V2).get(0).result;
			assertFalse(Arrays.equals(seenByChanged, seenByDeceived));
			for (Party p : net.parties()) {
				assertFalse(usesAny(p.recorder, EARLIER_LABELS));
			}
		}
	}

	@Test
	public void aMalformedAnnouncementEndsTheIntroduction() throws Exception {
		String[] values = {"1", "0", "02", "+2", "128", "x", "2 "};
		for (String value : values) {
			IntroductionTestNet net = net(CURRENT_VERSION, CURRENT_VERSION);
			Party deceived = net.bob();
			net.setRewrite((to, properties) -> to == deceived
					? withVersion(properties, value) : properties);
			net.introduceAndAccept(net.alice());
			assertFailedWithoutAContact(net);
			assertTrue(value, deceived.sent(AUTH).isEmpty());
			assertFalse(value, deceived.sent(ABORT).isEmpty());
			assertFalse(usesAny(deceived.recorder, SEPARATED_LABELS));
			assertFalse(usesAny(deceived.recorder, EARLIER_LABELS));
		}
	}

	@Test
	public void aSessionStoredByTheEarlierCodeAfterItsAuthCompletesAsItBegan()
			throws Exception {
		for (boolean bothUpdate : new boolean[] {true, false}) {
			IntroductionTestNet net = net(EARLIER_VERSION, EARLIER_VERSION);
			List<Party> updated = new ArrayList<>();
			net.setHook((to, type) -> {
				if (to == null || type != AUTH) return;
				if (!bothUpdate && to != net.alice()) return;
				IntroduceeSession s = to.session();
				assertNotNull(s);
				assertEquals(AWAIT_AUTH, s.getState());
				assertNotNull(s.getMasterKey());
				assertNotNull(s.getLocal().macKey);
				assertNotNull(s.getLocal().ownKemSecret);
				assertStoredAsBefore(to, true);
				to.setKeyScheduleVersion(CURRENT_VERSION);
				updated.add(to);
			});
			net.introduceAndAccept(net.bob());
			assertEquals(bothUpdate ? 2 : 1, updated.size());
			assertCompleted(net);
			for (Party p : net.parties()) {
				assertEarlier(p);
				assertFalse(announces(only(p.sent(ACCEPT))));
			}
		}
	}

	@Test
	public void anAcceptSentByTheEarlierCodeKeepsItsSessionInTheEarlierForm()
			throws Exception {
		for (boolean earlierIsAlice : new boolean[] {true, false}) {
			IntroductionTestNet net = net(
					earlierIsAlice ? EARLIER_VERSION : CURRENT_VERSION,
					earlierIsAlice ? CURRENT_VERSION : EARLIER_VERSION);
			Party updating = earlierIsAlice ? net.alice() : net.bob();
			Party current = net.other(updating);
			net.introduce();
			updating.accept();
			net.run();
			updating.setKeyScheduleVersion(CURRENT_VERSION);
			current.accept();
			net.run();
			assertCompleted(net);
			assertFalse(announces(only(updating.sent(ACCEPT))));
			assertTrue(announces(only(current.sent(ACCEPT))));
			for (Party p : net.parties()) assertEarlier(p);
		}
	}

	@Test
	public void aRequestAcceptedAfterTheUpdateUsesTheSeparatedForm()
			throws Exception {
		IntroductionTestNet net = net(EARLIER_VERSION, CURRENT_VERSION);
		net.introduce();
		net.alice().setKeyScheduleVersion(CURRENT_VERSION);
		net.alice().accept();
		net.run();
		net.bob().accept();
		net.run();
		assertCompleted(net);
		for (Party p : net.parties()) {
			assertSeparated(p);
			assertTrue(announces(only(p.sent(ACCEPT))));
		}
	}

	@Test
	public void aSessionStoredByTheEarlierCodeBeforeTheActivateCompletesAsItBegan()
			throws Exception {
		for (boolean bothUpdate : new boolean[] {true, false}) {
			IntroductionTestNet net = net(EARLIER_VERSION, EARLIER_VERSION);
			List<Party> updated = new ArrayList<>();
			net.setHook((to, type) -> {
				if (to == null || type != ACTIVATE) return;
				if (!bothUpdate && to != net.bob()) return;
				if (updated.contains(to)) return;
				IntroduceeSession s = to.session();
				assertNotNull(s);
				assertEquals(AWAIT_ACTIVATE, s.getState());
				assertNotNull(s.getLocal().macKey);
				assertNotNull(s.getRemote().macKey);
				to.setKeyScheduleVersion(CURRENT_VERSION);
				updated.add(to);
			});
			net.introduceAndAccept(net.alice());
			assertEquals(bothUpdate ? 2 : 1, updated.size());
			assertCompleted(net);
			for (Party p : net.parties()) {
				assertEarlier(p);
				assertFalse(announces(only(p.sent(ACCEPT))));
			}
		}
	}

	@Test
	public void aRemoteAcceptStoredBeforeTheUpdateGivesTheSeparatedForm()
			throws Exception {
		for (boolean earlierIsAlice : new boolean[] {true, false}) {
			IntroductionTestNet net = net(
					earlierIsAlice ? EARLIER_VERSION : CURRENT_VERSION,
					earlierIsAlice ? CURRENT_VERSION : EARLIER_VERSION);
			Party updating = earlierIsAlice ? net.alice() : net.bob();
			Party current = net.other(updating);
			net.introduce();
			current.accept();
			net.run();
			IntroduceeSession stored = updating.session();
			assertNotNull(stored);
			assertEquals(REMOTE_ACCEPTED, stored.getState());
			updating.setKeyScheduleVersion(CURRENT_VERSION);
			updating.accept();
			net.run();
			assertCompleted(net);
			for (Party p : net.parties()) {
				assertSeparated(p);
				assertTrue(announces(only(p.sent(ACCEPT))));
			}
		}
	}

	@Test
	public void strippingTheAnnouncementTowardAnEarlierIntroduceeFailsTheIntroduction()
			throws Exception {
		for (boolean earlierIsAlice : new boolean[] {true, false}) {
			for (boolean earlierFirst : new boolean[] {true, false}) {
				IntroductionTestNet net = net(
						earlierIsAlice ? EARLIER_VERSION : CURRENT_VERSION,
						earlierIsAlice ? CURRENT_VERSION : EARLIER_VERSION);
				Party earlier = earlierIsAlice ? net.alice() : net.bob();
				Party current = net.other(earlier);
				net.setRewrite((to, properties) -> to == earlier
						? KeySchedule.withoutAnnouncement(properties)
						: properties);
				net.introduceAndAccept(earlierFirst ? earlier : current);
				assertTrue(announces(only(current.sent(ACCEPT))));
				assertFalse(announces(only(earlier.received(ACCEPT))));
				assertFailedWithoutAContact(net);
				for (Party p : net.parties()) assertEarlier(p);
				List<Boolean> checks = new ArrayList<>(authMacChecks(earlier));
				checks.addAll(authMacChecks(current));
				assertFalse(checks.isEmpty());
				assertFalse(checks.contains(Boolean.TRUE));
			}
		}
	}

	private static List<Transition> abortsOf(Party p) {
		List<Transition> found = new ArrayList<>();
		for (Transition t : p.transitions) {
			if (t.output.getState() == START && !t.inputSecrets.isEmpty()) {
				found.add(t);
			}
		}
		return found;
	}

	private static void assertWiped(List<byte[]> secrets) {
		assertFalse(secrets.isEmpty());
		for (byte[] secret : secrets) assertTrue(allZero(secret));
	}

	private static void assertDerivedWiped(Party p, String label) {
		List<Call> derived = p.recorder.derivations(label);
		assertFalse(label, derived.isEmpty());
		for (Call c : derived) {
			assertNotNull(c.result);
			assertNotNull(c.derived);
			assertFalse(label, allZero(c.result));
			assertTrue(label, allZero(c.derived.getBytes()));
		}
	}

	private static IntroduceeSession requireSession(Party p)
			throws Exception {
		IntroduceeSession s = p.session();
		assertNotNull(s);
		return s;
	}

	@Test
	public void anAbortLeavesEverySecretOfTheSessionAtZero()
			throws Exception {
		for (int scenario = 0; scenario < 5; scenario++) {
			boolean earlierAlice = scenario == 3 || scenario == 4;
			boolean earlierBob = scenario == 4;
			IntroductionTestNet net = net(
					earlierAlice ? EARLIER_VERSION : CURRENT_VERSION,
					earlierBob ? EARLIER_VERSION : CURRENT_VERSION);
			Party alice = net.alice();
			Party bob = net.bob();
			int s = scenario;
			net.setRewrite((to, properties) -> {
				if (s == 0 && to == alice) {
					return KeySchedule.withoutAnnouncement(properties);
				}
				if (s == 1 && to == bob) {
					return KeySchedule.withoutAnnouncement(properties);
				}
				if (s == 2 || (s == 3 && to == alice)) {
					return KeySchedule.withoutAnnouncement(properties);
				}
				if (s == 4 && to == bob) {
					return KeySchedule.announce(properties, SEPARATED_VERSION);
				}
				return properties;
			});
			net.introduceAndAccept(alice);
			assertFailedWithoutAContact(net);
			for (Party p : net.parties()) {
				Transition t = only(abortsOf(p));
				assertEquals(AWAIT_AUTH, t.input.getState());
				assertTrue(t.inputSecrets.size() >= 3);
				assertWiped(t.inputSecrets);
				assertWiped(IntroductionTestNet.secrets(t.input));
				assertTrue(IntroductionTestNet.secrets(t.output).isEmpty());
			}
		}
	}

	@Test
	public void anAbortAfterTheKeysAreDerivedWipesTheRootAndTheActivateKeys()
			throws Exception {
		for (int version : new int[] {CURRENT_VERSION, EARLIER_VERSION}) {
			IntroductionTestNet net = net(version, version);
			net.setTime(1_000_000L);
			net.introduceAndAccept(net.alice());
			for (Party p : net.parties()) {
				boolean alice = p == net.alice();
				assertTrue(p.contactRoots.isEmpty());
				assertTrue(p.rotationRoots.isEmpty());
				assertTrue(p.sent(ACTIVATE).isEmpty());
				assertFalse(p.sent(ABORT).isEmpty());
				Transition t = only(abortsOf(p));
				assertEquals(AWAIT_AUTH, t.input.getState());
				assertWiped(t.inputSecrets);
				if (version == CURRENT_VERSION) {
					assertDerivedWiped(p, LABEL_ROOT_KEY_V2);
					assertDerivedWiped(p, LABEL_ALICE_ACTIVATE_KEY_V2);
					assertDerivedWiped(p, LABEL_BOB_ACTIVATE_KEY_V2);
				} else {
					Call root = only(p.recorder.calls("deriveKey",
							LABEL_MASTER_KEY));
					assertNotNull(root.derived);
					assertTrue(allZero(root.derived.getBytes()));
					assertDerivedWiped(p, LABEL_ALICE_ACTIVATE_KEY);
					assertDerivedWiped(p, LABEL_BOB_ACTIVATE_KEY);
					List<Call> preMasters =
							p.recorder.derivations(LABEL_PRE_MASTER_KEY);
					Call peerPreMaster = preMasters.get(preMasters.size() - 1);
					assertNotNull(peerPreMaster.derived);
					assertTrue(allZero(peerPreMaster.derived.getBytes()));
					List<Call> peerMacKeys = p.recorder.derivations(
							alice ? LABEL_BOB_MAC_KEY : LABEL_ALICE_MAC_KEY);
					Call peerMacKey = peerMacKeys.get(peerMacKeys.size() - 1);
					assertNotNull(peerMacKey.derived);
					assertTrue(allZero(peerMacKey.derived.getBytes()));
				}
			}
		}
	}

	@Test
	public void aDeclineWipesTheKeysOfTheSideThatAccepted() throws Exception {
		for (int version : new int[] {CURRENT_VERSION, EARLIER_VERSION}) {
			IntroductionTestNet net = net(version, version);
			net.introduce();
			net.alice().accept();
			net.run();
			net.bob().decline();
			net.run();
			Transition t = only(abortsOf(net.alice()));
			assertEquals(LOCAL_ACCEPTED, t.input.getState());
			assertEquals(2, t.inputSecrets.size());
			assertWiped(t.inputSecrets);
			assertTrue(net.alice().contactRoots.isEmpty());
		}
	}

	@Test
	public void keysMadeForAnAcceptAfterTheOtherDeclinedAreWiped()
			throws Exception {
		for (int version : new int[] {CURRENT_VERSION, EARLIER_VERSION}) {
			IntroductionTestNet net = net(version, version);
			net.introduce();
			net.bob().decline();
			net.run();
			net.alice().accept();
			net.run();
			List<Object> made = net.alice().recorder.returned;
			assertEquals(2, made.size());
			boolean agreement = false;
			boolean mlKem = false;
			for (Object o : made) {
				byte[] secret;
				if (o instanceof KeyPair) {
					secret = ((KeyPair) o).getPrivate().getEncoded();
					agreement = true;
				} else {
					secret = ((MlKemKeyPair) o).getDecapsulationKey();
					mlKem = true;
				}
				assertTrue(allZero(secret));
			}
			assertTrue(agreement && mlKem);
			assertEquals(START, requireSession(net.alice()).getState());
			assertTrue(net.alice().contactRoots.isEmpty());
		}
	}

	@Test
	public void theRootHandedToTheContactAndKeyManagersOutlivesTheWipe()
			throws Exception {
		for (int version : new int[] {CURRENT_VERSION, EARLIER_VERSION}) {
			IntroductionTestNet net = net(version, version);
			net.introduceAndAccept(net.bob());
			assertCompleted(net);
			for (Party p : net.parties()) {
				byte[] root = p.contactRoots.get(0);
				assertFalse(allZero(root));
				byte[] handedToContacts = only(p.contactRootKeys).getBytes();
				byte[] handedToKeys = only(p.rotationRootKeys).getBytes();
				assertArrayEquals(root, handedToContacts);
				assertArrayEquals(root, handedToKeys);
				assertFalse(handedToContacts == handedToKeys);
				Transition last =
						p.transitions.get(p.transitions.size() - 1);
				assertEquals(AWAIT_ACTIVATE, last.input.getState());
				assertEquals(2, last.inputSecrets.size());
				assertWiped(last.inputSecrets);
			}
		}
	}

	@Test
	public void wipingASessionZeroesEachOfItsSecrets() throws Exception {
		IntroductionTestNet net = net(EARLIER_VERSION, EARLIER_VERSION);
		int[] checked = {0};
		net.setHook((to, type) -> {
			if (to == null || type != AUTH) return;
			IntroduceeSession s = requireSession(to);
			List<byte[]> secrets = IntroductionTestNet.secrets(s);
			assertEquals(6, secrets.size());
			for (byte[] secret : secrets) assertFalse(allZero(secret));
			IntroduceeSession.wipeSecrets(s);
			assertWiped(secrets);
			checked[0]++;
		});
		net.introduceAndAccept(net.alice());
		assertEquals(2, checked[0]);
		assertCompleted(net);
	}
}
