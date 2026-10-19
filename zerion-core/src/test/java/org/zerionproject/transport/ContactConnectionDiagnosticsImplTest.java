package org.zerionproject.transport;

import org.zerionproject.core.api.UniqueId;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Authorization;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.OwnRotation;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.PeerAddress;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Reach;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Tor;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.network.NetworkManager;
import org.zerionproject.core.api.network.NetworkStatus;
import org.zerionproject.core.api.plugin.OnionClientAuthManager.State;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.core.api.plugin.event.ContactDisconnectedEvent;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import org.zerionproject.core.plugin.tor.B4OnionRotation.PeerRotationState;
import org.zerionproject.core.plugin.tor.B4OnionRotation.RotationPhase;
import org.zerionproject.core.plugin.tor.B4RotationDiagnostics;
import org.zerionproject.core.plugin.tor.auth.OnionAuthDiagnostics;
import org.zerionproject.core.plugin.tor.auth.OnionClientAuthManagerImpl;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.TestUtils;
import org.jmock.Expectations;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import static org.zerionproject.core.api.connection.ContactConnectionSnapshot.UNKNOWN;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContactConnectionDiagnosticsImplTest extends BrambleMockTestCase {

	private static final String CONTACT_ONION =
			"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx";
	private static final long NOW = 1_700_000_000_000L;

	private static final B4RotationDiagnostics NO_ROTATION =
			new B4RotationDiagnostics(RotationPhase.IDLE, false,
					PeerRotationState.CURRENT, false, 0L);
	private static final OnionAuthDiagnostics LEGACY =
			new OnionAuthDiagnostics(State.LEGACY, false, 0, false, 0, 0, 0,
					0);

	public ContactConnectionDiagnosticsImplTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
	}

	private static ContactConnectionSnapshot build(Tor tor, boolean online,
			boolean hasAddress, boolean dialer,
			@Nullable ZtpDialDiagnostics dial) {
		return ContactConnectionDiagnosticsImpl.build(tor, online, hasAddress,
				dialer, dial, UNKNOWN, NO_ROTATION, LEGACY, NOW);
	}

	@Test
	public void torStatusFollowsTheSameOrderAsTheNetworkScreen() {
		assertEquals(Tor.OFFLINE_MODE, ContactConnectionDiagnosticsImpl
				.torStatus(true, true, Plugin.State.ACTIVE, 0, true));
		assertEquals(Tor.PAUSED, ContactConnectionDiagnosticsImpl
				.torStatus(false, true, Plugin.State.ACTIVE, 0, true));
		assertEquals(Tor.OFF, ContactConnectionDiagnosticsImpl
				.torStatus(false, false, null, 0, true));
		assertEquals(Tor.OFF, ContactConnectionDiagnosticsImpl
				.torStatus(false, false, Plugin.State.DISABLED, 0, true));
		assertEquals(Tor.CLOCK_WRONG, ContactConnectionDiagnosticsImpl
				.torStatus(false, false, Plugin.State.ACTIVE, 3600, true));
		assertEquals(Tor.CONNECTED, ContactConnectionDiagnosticsImpl
				.torStatus(false, false, Plugin.State.ACTIVE, 0, false));
		assertEquals(Tor.NO_INTERNET, ContactConnectionDiagnosticsImpl
				.torStatus(false, false, Plugin.State.INACTIVE, 0, false));
		assertEquals(Tor.CONNECTING, ContactConnectionDiagnosticsImpl
				.torStatus(false, false, Plugin.State.ENABLING, 0, true));
		assertEquals(Tor.CONNECTING, ContactConnectionDiagnosticsImpl
				.torStatus(false, false, Plugin.State.STARTING_STOPPING, 0,
						true));
	}

	@Test
	public void aFailingEntryRelayIsReportedWhileTorIsUp() {
		assertEquals(Tor.ENTRY_RELAY_FAILING, ContactConnectionDiagnosticsImpl
				.torStatus(false, false, Plugin.State.ACTIVE, 0, true, true));
		assertEquals("a wrong clock comes first", Tor.CLOCK_WRONG,
				ContactConnectionDiagnosticsImpl.torStatus(false, false,
						Plugin.State.ACTIVE, 3600, true, true));
		assertEquals(Tor.CONNECTING, ContactConnectionDiagnosticsImpl
				.torStatus(false, false, Plugin.State.ENABLING, 0, true,
						true));
		assertTrue(Tor.ENTRY_RELAY_FAILING.isUp());
		assertTrue(Tor.CONNECTED.isUp());
		assertFalse(Tor.CONNECTING.isUp());
	}

	@Test
	public void reachCoversEveryState() {
		ZtpDialDiagnostics idle = new ZtpDialDiagnostics(false, 0L);
		ZtpDialDiagnostics dialing = new ZtpDialDiagnostics(true, 0L);
		ZtpDialDiagnostics backingOff =
				new ZtpDialDiagnostics(false, NOW + 40_000);
		ZtpDialDiagnostics expired = new ZtpDialDiagnostics(false, NOW - 1);

		assertEquals(Reach.ONLINE,
				build(Tor.OFFLINE_MODE, true, false, false, null).getReach());
		assertEquals(Reach.NEEDS_TOR,
				build(Tor.CONNECTING, false, true, true, idle).getReach());
		assertEquals(Reach.DIALING,
				build(Tor.CONNECTED, false, true, false, dialing).getReach());
		assertEquals(Reach.WAITING_FOR_CONTACT,
				build(Tor.CONNECTED, false, true, false, idle).getReach());
		assertEquals(Reach.NO_ADDRESS,
				build(Tor.CONNECTED, false, false, true, idle).getReach());
		ContactConnectionSnapshot retry =
				build(Tor.CONNECTED, false, true, true, backingOff);
		assertEquals(Reach.RETRY_LATER, retry.getReach());
		assertEquals(40_000, retry.getNextAttemptInMs());
		assertEquals(Reach.RETRY_SOON,
				build(Tor.CONNECTED, false, true, true, expired).getReach());
		assertEquals(Reach.RETRY_SOON,
				build(Tor.CONNECTED, false, true, true, null).getReach());
		assertEquals(UNKNOWN, build(Tor.CONNECTED, false, true, true, idle)
				.getNextAttemptInMs());
	}

	@Test
	public void contactAddressChangeMapsToItsState() {
		assertEquals(PeerAddress.CURRENT, ContactConnectionDiagnosticsImpl
				.peerAddress(NO_ROTATION, NOW));
		assertEquals(PeerAddress.NEW_ANNOUNCED,
				ContactConnectionDiagnosticsImpl.peerAddress(
						new B4RotationDiagnostics(RotationPhase.IDLE, false,
								PeerRotationState.CURRENT, true, 0L), NOW));
		B4RotationDiagnostics suppressed = new B4RotationDiagnostics(
				RotationPhase.IDLE, false, PeerRotationState.CURRENT, true,
				NOW + 120_000);
		assertEquals(PeerAddress.NEW_RETRY_LATER,
				ContactConnectionDiagnosticsImpl.peerAddress(suppressed, NOW));
		ContactConnectionSnapshot s = ContactConnectionDiagnosticsImpl.build(
				Tor.CONNECTED, true, true, true, null, UNKNOWN, suppressed,
				LEGACY, NOW);
		assertEquals(120_000, s.getPeerRetryInMs());
		assertEquals(UNKNOWN, build(Tor.CONNECTED, true, true, true, null)
				.getPeerRetryInMs());
	}

	@Test
	public void ownRotationMapsToItsState() {
		assertEquals(OwnRotation.IDLE,
				ContactConnectionDiagnosticsImpl.ownRotation(NO_ROTATION));
		assertEquals(OwnRotation.PREPARING,
				ContactConnectionDiagnosticsImpl.ownRotation(
						new B4RotationDiagnostics(RotationPhase.ANNOUNCING,
								false, PeerRotationState.CURRENT, false, 0L)));
		assertEquals(OwnRotation.CONTACT_NOT_MOVED,
				ContactConnectionDiagnosticsImpl.ownRotation(
						new B4RotationDiagnostics(RotationPhase.ANNOUNCING,
								true, PeerRotationState.PRE_ANNOUNCED, false,
								0L)));
		assertEquals(OwnRotation.CONTACT_MOVED,
				ContactConnectionDiagnosticsImpl.ownRotation(
						new B4RotationDiagnostics(RotationPhase.ANNOUNCING,
								true, PeerRotationState.MIGRATED, false, 0L)));
	}

	@Test
	public void aContactHoldingThePreviousAuthorizedAddressIsShownAsNotMoved() {
		OnionAuthDiagnostics holding = new OnionAuthDiagnostics(
				State.AUTH_REQUIRED, true, 0, false, 0, 0, 3, 4, true);
		OnionAuthDiagnostics moved = new OnionAuthDiagnostics(
				State.AUTH_REQUIRED, true, 0, false, 0, 0, 3, 4, false);
		assertEquals(OwnRotation.CONTACT_NOT_MOVED,
				ContactConnectionDiagnosticsImpl.build(Tor.CONNECTED, true,
						true, true, null, UNKNOWN, NO_ROTATION, holding, NOW)
						.getOwnRotation());
		assertEquals(OwnRotation.IDLE,
				ContactConnectionDiagnosticsImpl.build(Tor.CONNECTED, true,
						true, true, null, UNKNOWN, NO_ROTATION, moved, NOW)
						.getOwnRotation());
		assertEquals(OwnRotation.CONTACT_NOT_MOVED,
				ContactConnectionDiagnosticsImpl.ownRotation(
						new B4RotationDiagnostics(RotationPhase.ANNOUNCING,
								true, PeerRotationState.MIGRATED, false, 0L),
						holding));
	}

	@Test
	public void authorizationMapsEveryState() {
		assertEquals(Authorization.NOT_SUPPORTED,
				ContactConnectionDiagnosticsImpl.authorization(LEGACY));
		assertEquals(Authorization.SETTING_UP, authorization(
				State.AUTH_NEGOTIATING, false, 0));
		assertEquals(Authorization.SETTING_UP, authorization(
				State.AUTH_CONFIRMED, false, 0));
		assertEquals(Authorization.AWAITING_CONFIRMATION, authorization(
				State.AUTH_CONFIRMED, true, 0));
		assertEquals(Authorization.REPAIRING, authorization(
				State.AUTH_CONFIRMED, true, 3));
		assertEquals(Authorization.AWAITING_CONFIRMATION, authorization(
				State.AUTH_CONFIRMED, true, 10));
		assertEquals(Authorization.ACTIVE, authorization(
				State.AUTH_REQUIRED, true, 2));
		assertEquals(Authorization.REVOKED, authorization(
				State.REVOKED, false, 0));
	}

	@Test
	public void aLockedInContactShowsRenewalAndRepairDials() {
		assertEquals(Authorization.ACTIVE,
				lockedIn(false, false, 0, 0));
		assertEquals(Authorization.RENEWING,
				lockedIn(true, false, 0, 0));
		assertEquals(Authorization.RENEWING,
				lockedIn(true, true, 0, 3));
		assertEquals(Authorization.REACHING,
				lockedIn(false, false, 1, 0));
		assertEquals(Authorization.REACHING,
				lockedIn(false, false, 0, 2));
		assertEquals(Authorization.REACHING,
				lockedIn(false, true, 4, 3));
	}

	private static Authorization lockedIn(boolean rekeying,
			boolean commitSent, int silenceRepairs, int openDials) {
		return ContactConnectionDiagnosticsImpl.authorization(
				new OnionAuthDiagnostics(State.AUTH_REQUIRED, commitSent, 0,
						rekeying, silenceRepairs, openDials, 2, 5));
	}

	@Test
	public void repairStatesOutsideTheLockedInStateAreIgnored() {
		assertEquals(Authorization.AWAITING_CONFIRMATION,
				ContactConnectionDiagnosticsImpl.authorization(
						new OnionAuthDiagnostics(State.AUTH_CONFIRMED, true,
								0, true, 3, 3, 2, 5)));
		assertEquals(Authorization.SETTING_UP,
				ContactConnectionDiagnosticsImpl.authorization(
						new OnionAuthDiagnostics(State.AUTH_NEGOTIATING,
								false, 0, true, 2, 2, 2, 5)));
		assertEquals(Authorization.NOT_SUPPORTED,
				ContactConnectionDiagnosticsImpl.authorization(
						new OnionAuthDiagnostics(State.LEGACY, false, 0,
								false, 2, 2, 0, 0)));
	}

	private static Authorization authorization(State state,
			boolean commitSent, int repairs) {
		return ContactConnectionDiagnosticsImpl.authorization(
				new OnionAuthDiagnostics(state, commitSent, repairs, false, 0,
						0, 2, 5));
	}

	@Test
	public void generationsAndRepairsAreShownOnlyWhenTheyApply() {
		ContactConnectionSnapshot repairing = withAuth(
				new OnionAuthDiagnostics(State.AUTH_CONFIRMED, true, 3, false,
						0, 0, 2, 5));
		assertEquals(3, repairing.getRepairAttempts());
		assertEquals(2, repairing.getOwnGeneration());
		assertEquals(5, repairing.getContactGeneration());
		ContactConnectionSnapshot active = withAuth(
				new OnionAuthDiagnostics(State.AUTH_REQUIRED, true, 3, false,
						0, 0, 2, 5));
		assertEquals(0, active.getRepairAttempts());
		assertEquals(2, active.getOwnGeneration());
		ContactConnectionSnapshot reaching = withAuth(
				new OnionAuthDiagnostics(State.AUTH_REQUIRED, true, 3, false,
						2, 1, 2, 5));
		assertEquals(Authorization.REACHING, reaching.getAuthorization());
		assertEquals(3, reaching.getRepairAttempts());
		assertEquals(5, reaching.getContactGeneration());
		ContactConnectionSnapshot renewing = withAuth(
				new OnionAuthDiagnostics(State.AUTH_REQUIRED, true, 3, true,
						0, 2, 2, 6));
		assertEquals(Authorization.RENEWING, renewing.getAuthorization());
		assertEquals(0, renewing.getRepairAttempts());
		assertEquals(6, renewing.getContactGeneration());
		ContactConnectionSnapshot legacy = withAuth(
				new OnionAuthDiagnostics(State.LEGACY, false, 0, false, 0, 0,
						7, 0));
		assertEquals(0, legacy.getOwnGeneration());
		assertEquals(0, legacy.getContactGeneration());
		ContactConnectionSnapshot revoked = withAuth(
				new OnionAuthDiagnostics(State.REVOKED, false, 0, false, 0, 0,
						7, 4));
		assertEquals(0, revoked.getOwnGeneration());
		assertEquals(0, revoked.getContactGeneration());
	}

	@Test
	public void aContactReachedThroughItsAuthorizedAddressShowsNoOpenAddressChange() {
		B4RotationDiagnostics pending = new B4RotationDiagnostics(
				RotationPhase.IDLE, false, PeerRotationState.CURRENT, true, 0L);
		OnionAuthDiagnostics active = new OnionAuthDiagnostics(
				State.AUTH_REQUIRED, false, 0, false, 0, 0, 2, 2);
		assertEquals(PeerAddress.CURRENT, ContactConnectionDiagnosticsImpl
				.build(Tor.CONNECTED, true, true, true, null, UNKNOWN,
						pending, active, NOW).getPeerAddress());
		assertEquals(PeerAddress.NEW_ANNOUNCED, ContactConnectionDiagnosticsImpl
				.build(Tor.CONNECTED, true, true, true, null, UNKNOWN,
						pending, LEGACY, NOW).getPeerAddress());
	}

	private static ContactConnectionSnapshot withAuth(OnionAuthDiagnostics a) {
		return ContactConnectionDiagnosticsImpl.build(Tor.CONNECTED, true,
				true, true, null, UNKNOWN, NO_ROTATION, a, NOW);
	}

	@Test
	public void valueObjectsCarryNoAddressesOrKeys() {
		for (Class<?> c : Arrays.asList(ContactConnectionSnapshot.class,
				ZtpDialDiagnostics.class, B4RotationDiagnostics.class,
				OnionAuthDiagnostics.class)) {
			for (Field f : c.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers())) continue;
				Class<?> t = f.getType();
				assertTrue(c.getSimpleName() + "." + f.getName(),
						t.isPrimitive() || t.isEnum());
			}
		}
	}

	@Test
	public void theContactsHoldingAnAddressChangeComeFromBothRotations()
			throws Exception {
		B4OnionRotation rotation = context.mock(B4OnionRotation.class);
		OnionClientAuthManagerImpl auth =
				context.mock(OnionClientAuthManagerImpl.class);
		ContactId one = new ContactId(1);
		ContactId two = new ContactId(2);
		ContactId three = new ContactId(3);
		context.checking(new Expectations() {{
			allowing(rotation).getContactsHoldingRotation();
			will(returnValue(Arrays.asList(one, two)));
			allowing(auth).getContactsHoldingOldAddress();
			will(returnValue(Arrays.asList(two, three)));
			allowing(auth).getLastContactMs(one);
			will(returnValue(NOW - 3_600_000L));
			allowing(auth).getLastContactMs(two);
			will(returnValue(0L));
			allowing(auth).getLastContactMs(three);
			will(returnValue(0L));
			allowing(rotation).getLastContactMs(one);
			will(returnValue(NOW - 600_000L));
			allowing(rotation).getLastContactMs(two);
			will(returnValue(0L));
			allowing(rotation).getLastContactMs(three);
			will(returnValue(NOW - 7_200_000L));
		}});
		ContactConnectionDiagnosticsImpl d =
				new ContactConnectionDiagnosticsImpl(() -> null, () -> null,
						() -> null, () -> null, () -> null, () -> null,
						() -> rotation, () -> auth, fixedClock());
		assertEquals(Arrays.asList(one, two, three),
				d.getContactsHoldingAddressChange());
		assertEquals("the newer of both records", 600_000L,
				d.getLastConnectedAgoMs(one));
		assertEquals(UNKNOWN, d.getLastConnectedAgoMs(two));
		assertEquals("the record the rotation holds for a legacy contact",
				7_200_000L, d.getLastConnectedAgoMs(three));
	}

	@Test
	public void snapshotReadsEverySourceWithoutKeepingTheAddress()
			throws Exception {
		ConnectionRegistry registry = context.mock(ConnectionRegistry.class);
		PluginManager pluginManager = context.mock(PluginManager.class);
		Plugin tor = context.mock(Plugin.class);
		NetworkManager networkManager = context.mock(NetworkManager.class);
		ContactManager contactManager = context.mock(ContactManager.class);
		TransportPropertyManager tpm =
				context.mock(TransportPropertyManager.class);
		B4OnionRotation rotation = context.mock(B4OnionRotation.class);
		OnionClientAuthManagerImpl auth =
				context.mock(OnionClientAuthManagerImpl.class);
		ContactId c = new ContactId(1);
		Contact contact = dialerContact(c);
		TransportProperties remote = new TransportProperties();
		remote.put(TorConstants.PROP_ONION_V3, CONTACT_ONION);
		B4RotationDiagnostics rot = new B4RotationDiagnostics(
				RotationPhase.ANNOUNCING, true, PeerRotationState.MIGRATED,
				true, NOW + 300_000);
		OnionAuthDiagnostics authDiag =
				new OnionAuthDiagnostics(State.AUTH_REQUIRED, true, 0, false,
						0, 0, 3, 4);
		context.checking(new Expectations() {{
			allowing(contactManager).getContact(c);
			will(returnValue(contact));
			allowing(registry).isConnected(c);
			will(returnValue(false));
			allowing(pluginManager).isOfflineMode();
			will(returnValue(false));
			allowing(pluginManager).isConnectionsPaused();
			will(returnValue(false));
			allowing(pluginManager).getPlugin(TorConstants.ID);
			will(returnValue(tor));
			allowing(tor).getState();
			will(returnValue(Plugin.State.ACTIVE));
			allowing(networkManager).getNetworkStatus();
			will(returnValue(new NetworkStatus(true, true, false)));
			allowing(tpm).getRemoteProperties(c, TorConstants.ID);
			will(returnValue(remote));
			allowing(rotation).diagnostics(c);
			will(returnValue(rot));
			allowing(auth).diagnostics(c);
			will(returnValue(authDiag));
		}});
		ZtpPollerFactory pollers = new ZtpPollerFactory(Runnable::run,
				context.mock(TaskScheduler.class), contactManager, tpm,
				context.mock(EventBus.class), () -> rotation);
		pollers.create(new IdleTorTransport());
		ContactConnectionDiagnosticsImpl diagnostics =
				new ContactConnectionDiagnosticsImpl(() -> registry,
						() -> pluginManager, () -> networkManager,
						() -> contactManager, () -> tpm, () -> pollers,
						() -> rotation, () -> auth, fixedClock());
		AtomicLong uptime = new AtomicLong(1_000);
		diagnostics.uptimeClock = uptime::get;

		ContactConnectionSnapshot never = diagnostics.getSnapshot(c);
		assertEquals(Tor.CONNECTED, never.getTor());
		assertEquals(Reach.RETRY_SOON, never.getReach());
		assertEquals(UNKNOWN, never.getLastConnectedAgoMs());
		assertEquals(PeerAddress.CURRENT, never.getPeerAddress());
		assertEquals(UNKNOWN, never.getPeerRetryInMs());
		assertEquals(OwnRotation.CONTACT_MOVED, never.getOwnRotation());
		assertEquals(Authorization.ACTIVE, never.getAuthorization());
		assertEquals(3, never.getOwnGeneration());
		assertEquals(4, never.getContactGeneration());

		diagnostics.eventOccurred(new ContactConnectedEvent(c));
		uptime.addAndGet(20_000);
		diagnostics.eventOccurred(new ContactDisconnectedEvent(c));
		uptime.addAndGet(90_000);
		assertEquals(90_000,
				diagnostics.getSnapshot(c).getLastConnectedAgoMs());

		diagnostics.eventOccurred(new ContactRemovedEvent(c));
		assertEquals(UNKNOWN, diagnostics.lastConnectedAgoMs(c));
	}

	@Test
	public void withoutAnAddressTheDesignatedDialerWaitsForIt()
			throws Exception {
		ContactId c = new ContactId(2);
		TransportProperties remote = new TransportProperties();
		remote.put(TorConstants.PROP_ONION_V3, "not-an-onion");
		ContactConnectionDiagnosticsImpl diagnostics =
				activeTorWithRemote(c, remote);
		assertEquals(Reach.NO_ADDRESS, diagnostics.getSnapshot(c).getReach());
	}

	private ContactConnectionDiagnosticsImpl activeTorWithRemote(ContactId c,
			TransportProperties remote) throws Exception {
		ConnectionRegistry registry = context.mock(ConnectionRegistry.class);
		PluginManager pluginManager = context.mock(PluginManager.class);
		Plugin tor = context.mock(Plugin.class);
		NetworkManager networkManager = context.mock(NetworkManager.class);
		ContactManager contactManager = context.mock(ContactManager.class);
		TransportPropertyManager tpm =
				context.mock(TransportPropertyManager.class);
		B4OnionRotation rotation = context.mock(B4OnionRotation.class);
		OnionClientAuthManagerImpl auth =
				context.mock(OnionClientAuthManagerImpl.class);
		Contact contact = dialerContact(c);
		context.checking(new Expectations() {{
			allowing(contactManager).getContact(c);
			will(returnValue(contact));
			allowing(registry).isConnected(c);
			will(returnValue(false));
			allowing(pluginManager).isOfflineMode();
			will(returnValue(false));
			allowing(pluginManager).isConnectionsPaused();
			will(returnValue(false));
			allowing(pluginManager).getPlugin(TorConstants.ID);
			will(returnValue(tor));
			allowing(tor).getState();
			will(returnValue(Plugin.State.ACTIVE));
			allowing(networkManager).getNetworkStatus();
			will(returnValue(new NetworkStatus(true, true, false)));
			allowing(tpm).getRemoteProperties(c, TorConstants.ID);
			will(returnValue(remote));
			allowing(rotation).diagnostics(c);
			will(returnValue(NO_ROTATION));
			allowing(auth).diagnostics(c);
			will(returnValue(LEGACY));
		}});
		ZtpPollerFactory pollers = new ZtpPollerFactory(Runnable::run,
				context.mock(TaskScheduler.class), contactManager, tpm,
				context.mock(EventBus.class), () -> rotation);
		return new ContactConnectionDiagnosticsImpl(() -> registry,
				() -> pluginManager, () -> networkManager,
				() -> contactManager, () -> tpm, () -> pollers,
				() -> rotation, () -> auth, fixedClock());
	}

	private static Contact dialerContact(ContactId c) {
		byte[] low = new byte[UniqueId.LENGTH];
		byte[] high = new byte[UniqueId.LENGTH];
		Arrays.fill(high, (byte) 0xFF);
		Author remote = new Author(new AuthorId(high), Author.FORMAT_VERSION,
				"them", TestUtils.getAuthor().getPublicKey());
		return getContact(c, remote, new AuthorId(low), true);
	}

	private static Clock fixedClock() {
		return new Clock() {
			@Override
			public long currentTimeMillis() {
				return NOW;
			}

			@Override
			public void sleep(long milliseconds) {
			}
		};
	}

	private static final class IdleTorTransport implements OverlayTransport {

		@Override
		public TransportId getTransportId() {
			return TorConstants.ID;
		}

		@Override
		public String getAddressPropertyKey() {
			return TorConstants.PROP_ONION_V3;
		}

		@Override
		public boolean isValidAddress(String address) {
			return true;
		}

		@Override
		public DialResult dial(int contactId, String peerAddress,
				boolean fast) {
			return DialResult.NOT_CONNECTED;
		}

		@Override
		public void setNetworkEnabled(boolean enabled) {
		}
	}
}
