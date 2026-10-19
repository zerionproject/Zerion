package org.zerionproject.transport;

import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.connection.ContactConnectionDiagnostics;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Authorization;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.OwnRotation;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.PeerAddress;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Reach;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Tor;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.network.NetworkManager;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorClockSkewStatus;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.core.api.plugin.event.ContactDisconnectedEvent;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import org.zerionproject.core.plugin.tor.B4OnionRotation.PeerRotationState;
import org.zerionproject.core.plugin.tor.B4OnionRotation.RotationPhase;
import org.zerionproject.core.plugin.tor.B4RotationDiagnostics;
import org.zerionproject.core.plugin.tor.auth.OnionAuthDiagnostics;
import org.zerionproject.core.plugin.tor.auth.OnionClientAuthManagerImpl;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Provider;

import static org.zerionproject.core.api.connection.ContactConnectionSnapshot.UNKNOWN;

@ThreadSafe
@NotNullByDefault
public class ContactConnectionDiagnosticsImpl
		implements ContactConnectionDiagnostics, EventListener {

	private static final Pattern ONION_V3 = Pattern.compile("[a-z2-7]{56}");

	private final Provider<ConnectionRegistry> connectionRegistry;
	private final Provider<PluginManager> pluginManager;
	private final Provider<NetworkManager> networkManager;
	private final Provider<ContactManager> contactManager;
	private final Provider<TransportPropertyManager> transportPropertyManager;
	private final Provider<ZtpPollerFactory> pollerFactory;
	private final Provider<B4OnionRotation> rotation;
	private final Provider<OnionClientAuthManagerImpl> onionClientAuth;
	private final Clock clock;
	private final Map<ContactId, Long> lastConnected =
			new ConcurrentHashMap<>();
	volatile LongSupplier uptimeClock = () -> System.nanoTime() / 1_000_000;

	@Inject
	ContactConnectionDiagnosticsImpl(
			Provider<ConnectionRegistry> connectionRegistry,
			Provider<PluginManager> pluginManager,
			Provider<NetworkManager> networkManager,
			Provider<ContactManager> contactManager,
			Provider<TransportPropertyManager> transportPropertyManager,
			Provider<ZtpPollerFactory> pollerFactory,
			Provider<B4OnionRotation> rotation,
			Provider<OnionClientAuthManagerImpl> onionClientAuth,
			Clock clock) {
		this.connectionRegistry = connectionRegistry;
		this.pluginManager = pluginManager;
		this.networkManager = networkManager;
		this.contactManager = contactManager;
		this.transportPropertyManager = transportPropertyManager;
		this.pollerFactory = pollerFactory;
		this.rotation = rotation;
		this.onionClientAuth = onionClientAuth;
		this.clock = clock;
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactConnectedEvent) {
			lastConnected.put(((ContactConnectedEvent) e).getContactId(),
					uptimeClock.getAsLong());
		} else if (e instanceof ContactDisconnectedEvent) {
			lastConnected.put(((ContactDisconnectedEvent) e).getContactId(),
					uptimeClock.getAsLong());
		} else if (e instanceof ContactRemovedEvent) {
			lastConnected.remove(((ContactRemovedEvent) e).getContactId());
		}
	}

	@Override
	public ContactConnectionSnapshot getSnapshot(ContactId c)
			throws DbException {
		boolean dialer = ZtpPoller.isDesignatedDialer(
				contactManager.get().getContact(c));
		boolean online = connectionRegistry.get().isConnected(c);
		ZtpPoller poller = pollerFactory.get().getPoller(TorConstants.ID);
		ZtpDialDiagnostics dial =
				poller == null ? null : poller.diagnostics(c.getInt());
		B4RotationDiagnostics rot = rotation.get().diagnostics(c);
		OnionAuthDiagnostics auth = onionClientAuth.get().diagnostics(c);
		return build(torStatus(), online, hasAddress(c), dialer, dial,
				lastConnectedAgoMs(c), rot, auth, clock.currentTimeMillis());
	}

	@Override
	public List<ContactId> getContactsHoldingAddressChange()
			throws DbException {
		Set<ContactId> out = new LinkedHashSet<>(
				rotation.get().getContactsHoldingRotation());
		out.addAll(onionClientAuth.get().getContactsHoldingOldAddress());
		return new ArrayList<>(out);
	}

	@Override
	public long getLastConnectedAgoMs(ContactId c) throws DbException {
		long ago = lastConnectedAgoMs(c);
		if (ago != UNKNOWN) return ago;
		long last = Math.max(onionClientAuth.get().getLastContactMs(c),
				rotation.get().getLastContactMs(c));
		if (last <= 0) return UNKNOWN;
		return Math.max(0, clock.currentTimeMillis() - last);
	}

	long lastConnectedAgoMs(ContactId c) {
		Long at = lastConnected.get(c);
		if (at == null) return UNKNOWN;
		return Math.max(0, uptimeClock.getAsLong() - at);
	}

	private boolean hasAddress(ContactId c) throws DbException {
		String a = transportPropertyManager.get()
				.getRemoteProperties(c, TorConstants.ID)
				.get(TorConstants.PROP_ONION_V3);
		return a != null && ONION_V3.matcher(a).matches();
	}

	private Tor torStatus() {
		PluginManager pm = pluginManager.get();
		Plugin p = pm.getPlugin(TorConstants.ID);
		long skew = p instanceof TorClockSkewStatus
				? ((TorClockSkewStatus) p).getCurrentClockSkewSeconds() : 0;
		boolean relayFailing = p instanceof org.zerionproject.core.api.plugin
				.TorEntryRelayStatus && ((org.zerionproject.core.api.plugin
				.TorEntryRelayStatus) p).isEntryRelayFailing();
		return torStatus(pm.isOfflineMode(), pm.isConnectionsPaused(),
				p == null ? null : p.getState(), skew, relayFailing,
				networkManager.get().getNetworkStatus().isConnected());
	}

	static Tor torStatus(boolean offlineMode, boolean paused,
			@Nullable Plugin.State state, long clockSkewSeconds,
			boolean internet) {
		return torStatus(offlineMode, paused, state, clockSkewSeconds, false,
				internet);
	}

	static Tor torStatus(boolean offlineMode, boolean paused,
			@Nullable Plugin.State state, long clockSkewSeconds,
			boolean entryRelayFailing, boolean internet) {
		if (offlineMode) return Tor.OFFLINE_MODE;
		if (paused) return Tor.PAUSED;
		if (state == null || state == Plugin.State.DISABLED) return Tor.OFF;
		if (clockSkewSeconds != 0) return Tor.CLOCK_WRONG;
		if (state == Plugin.State.ACTIVE && entryRelayFailing) {
			return Tor.ENTRY_RELAY_FAILING;
		}
		if (state == Plugin.State.ACTIVE) return Tor.CONNECTED;
		if (!internet) return Tor.NO_INTERNET;
		return Tor.CONNECTING;
	}

	static ContactConnectionSnapshot build(Tor tor, boolean online,
			boolean hasAddress, boolean dialer,
			@Nullable ZtpDialDiagnostics dial, long lastConnectedAgoMs,
			B4RotationDiagnostics rot, OnionAuthDiagnostics auth, long now) {
		Reach reach = reach(tor, online, hasAddress, dialer, dial, now);
		long nextIn = reach == Reach.RETRY_LATER && dial != null
				? dial.getNextDialAtMs() - now : UNKNOWN;
		Authorization a = authorization(auth);
		PeerAddress peer = dialsAuthorizedAddress(a) ? PeerAddress.CURRENT
				: peerAddress(rot, now);
		long peerRetryIn = peer == PeerAddress.NEW_RETRY_LATER
				? rot.getContactRetryAtMs() - now : UNKNOWN;
		boolean negotiated = a != Authorization.NOT_SUPPORTED
				&& a != Authorization.REVOKED;
		return new ContactConnectionSnapshot(tor, reach, nextIn,
				lastConnectedAgoMs, peer, peerRetryIn, ownRotation(rot, auth), a,
				repairAttempts(a, auth),
				negotiated ? auth.getOwnGeneration() : 0,
				negotiated ? auth.getContactGeneration() : 0);
	}

	static boolean dialsAuthorizedAddress(Authorization a) {
		return a == Authorization.ACTIVE || a == Authorization.RENEWING
				|| a == Authorization.REACHING
				|| a == Authorization.REPAIRING
				|| a == Authorization.AWAITING_CONFIRMATION;
	}

	static int repairAttempts(Authorization a, OnionAuthDiagnostics auth) {
		if (a == Authorization.REPAIRING) return auth.getRepairs();
		if (a == Authorization.REACHING) return auth.getReachRepairs();
		return 0;
	}

	static Reach reach(Tor tor, boolean online, boolean hasAddress,
			boolean dialer, @Nullable ZtpDialDiagnostics dial, long now) {
		if (online) return Reach.ONLINE;
		if (!tor.isUp()) return Reach.NEEDS_TOR;
		if (dial != null && dial.isDialing()) return Reach.DIALING;
		if (!dialer) return Reach.WAITING_FOR_CONTACT;
		if (!hasAddress) return Reach.NO_ADDRESS;
		if (dial != null && dial.getNextDialAtMs() > now) {
			return Reach.RETRY_LATER;
		}
		return Reach.RETRY_SOON;
	}

	static PeerAddress peerAddress(B4RotationDiagnostics r, long now) {
		if (!r.isContactAddressPending()) return PeerAddress.CURRENT;
		if (r.getContactRetryAtMs() > now) return PeerAddress.NEW_RETRY_LATER;
		return PeerAddress.NEW_ANNOUNCED;
	}

	static OwnRotation ownRotation(B4RotationDiagnostics r,
			OnionAuthDiagnostics auth) {
		if (auth.isHoldingPreviousAddress()) {
			return OwnRotation.CONTACT_NOT_MOVED;
		}
		return ownRotation(r);
	}

	static OwnRotation ownRotation(B4RotationDiagnostics r) {
		if (r.getPhase() != RotationPhase.ANNOUNCING) return OwnRotation.IDLE;
		if (r.getPeerState() == PeerRotationState.MIGRATED) {
			return OwnRotation.CONTACT_MOVED;
		}
		if (!r.isAnnounced()) return OwnRotation.PREPARING;
		return OwnRotation.CONTACT_NOT_MOVED;
	}

	static Authorization authorization(OnionAuthDiagnostics a) {
		switch (a.getState()) {
			case AUTH_REQUIRED:
				if (a.isRenewing()) return Authorization.RENEWING;
				return a.isReaching() ? Authorization.REACHING
						: Authorization.ACTIVE;
			case REVOKED:
				return Authorization.REVOKED;
			case AUTH_CONFIRMED:
				if (!a.isCommitSent()) return Authorization.SETTING_UP;
				return a.isRepairing() ? Authorization.REPAIRING
						: Authorization.AWAITING_CONFIRMATION;
			case AUTH_NEGOTIATING:
				return Authorization.SETTING_UP;
			default:
				return Authorization.NOT_SUPPORTED;
		}
	}
}
