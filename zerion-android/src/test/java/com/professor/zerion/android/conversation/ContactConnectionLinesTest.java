package com.professor.zerion.android.conversation;

import com.professor.zerion.R;
import com.professor.zerion.android.conversation.ContactConnectionLines.Line;

import org.junit.Test;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Authorization;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.OwnRotation;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.PeerAddress;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Reach;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Tor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.zerionproject.core.api.connection.ContactConnectionSnapshot.UNKNOWN;

public class ContactConnectionLinesTest {

	private static final long NEXT_IN = 40_000;
	private static final long AGO = 125_000;
	private static final long PEER_RETRY_IN = 300_000;
	private static final int REPAIRS = 3;

	private static final Map<Tor, Integer> TOR = new EnumMap<>(Tor.class);
	private static final Map<Reach, Integer> REACH =
			new EnumMap<>(Reach.class);
	private static final Map<PeerAddress, Integer> PEER =
			new EnumMap<>(PeerAddress.class);
	private static final Map<OwnRotation, Integer> OWN =
			new EnumMap<>(OwnRotation.class);
	private static final Map<Authorization, Integer> AUTH =
			new EnumMap<>(Authorization.class);

	static {
		TOR.put(Tor.CONNECTED, R.string.contact_connection_tor_connected);
		TOR.put(Tor.CONNECTING, R.string.contact_connection_tor_connecting);
		TOR.put(Tor.NO_INTERNET,
				R.string.contact_connection_tor_no_internet);
		TOR.put(Tor.CLOCK_WRONG, R.string.contact_connection_tor_clock);
		TOR.put(Tor.ENTRY_RELAY_FAILING,
				R.string.contact_connection_tor_entry_relay);
		TOR.put(Tor.OFF, R.string.contact_connection_tor_off);
		TOR.put(Tor.PAUSED, R.string.contact_connection_tor_paused);
		TOR.put(Tor.OFFLINE_MODE,
				R.string.contact_connection_tor_offline_mode);
		REACH.put(Reach.ONLINE, R.string.contact_connection_online);
		REACH.put(Reach.DIALING, R.string.contact_connection_dialing);
		REACH.put(Reach.RETRY_LATER, R.string.contact_connection_retry);
		REACH.put(Reach.RETRY_SOON, R.string.contact_connection_retry_soon);
		REACH.put(Reach.WAITING_FOR_CONTACT,
				R.string.contact_connection_incoming);
		REACH.put(Reach.NO_ADDRESS, R.string.contact_connection_no_address);
		REACH.put(Reach.NEEDS_TOR, R.string.contact_connection_needs_tor);
		PEER.put(PeerAddress.NEW_ANNOUNCED,
				R.string.contact_connection_peer_new);
		PEER.put(PeerAddress.NEW_RETRY_LATER,
				R.string.contact_connection_peer_retry);
		OWN.put(OwnRotation.PREPARING,
				R.string.contact_connection_rotation_preparing);
		OWN.put(OwnRotation.CONTACT_MOVED,
				R.string.contact_connection_rotation_moved);
		OWN.put(OwnRotation.CONTACT_NOT_MOVED,
				R.string.contact_connection_rotation_waiting);
		AUTH.put(Authorization.NOT_SUPPORTED,
				R.string.contact_connection_auth_unsupported);
		AUTH.put(Authorization.SETTING_UP,
				R.string.contact_connection_auth_setup);
		AUTH.put(Authorization.AWAITING_CONFIRMATION,
				R.string.contact_connection_auth_confirm);
		AUTH.put(Authorization.REPAIRING,
				R.string.contact_connection_auth_repair);
		AUTH.put(Authorization.ACTIVE, R.string.contact_connection_auth_active);
		AUTH.put(Authorization.RENEWING,
				R.string.contact_connection_auth_renewing);
		AUTH.put(Authorization.REACHING,
				R.string.contact_connection_auth_reaching);
		AUTH.put(Authorization.REVOKED,
				R.string.contact_connection_auth_revoked);
	}

	static ContactConnectionSnapshot snapshot(Tor tor, Reach reach,
			long lastAgo, PeerAddress peer, OwnRotation own,
			Authorization auth, long ownGen, long contactGen) {
		return new ContactConnectionSnapshot(tor, reach,
				reach == Reach.RETRY_LATER ? NEXT_IN : UNKNOWN, lastAgo, peer,
				peer == PeerAddress.NEW_RETRY_LATER ? PEER_RETRY_IN : UNKNOWN,
				own, auth, counted(auth) ? REPAIRS : 0, ownGen, contactGen);
	}

	private static boolean counted(Authorization auth) {
		return auth == Authorization.REPAIRING
				|| auth == Authorization.REACHING;
	}

	@Test
	public void everyStateCombinationMapsToItsText() {
		int checked = 0;
		for (Tor tor : Tor.values()) {
			for (Reach reach : Reach.values()) {
				for (PeerAddress peer : PeerAddress.values()) {
					for (OwnRotation own : OwnRotation.values()) {
						for (Authorization auth : Authorization.values()) {
							for (boolean published : new boolean[] {true,
									false}) {
								for (long ago : new long[] {UNKNOWN, AGO}) {
									ContactConnectionSnapshot s = snapshot(tor,
											reach, ago, peer, own, auth, 0, 0);
									assertEquals(expected(s, published),
											ContactConnectionLines.lines(s,
													published));
									checked++;
								}
							}
						}
					}
				}
			}
		}
		assertEquals(Tor.values().length * Reach.values().length
				* PeerAddress.values().length * OwnRotation.values().length
				* Authorization.values().length * 4, checked);
	}

	private static List<Line> expected(ContactConnectionSnapshot s,
			boolean published) {
		List<Line> out = new ArrayList<>();
		out.add(Line.of(TOR.get(s.getTor())));
		if (s.getTor() == Tor.CONNECTED
				|| s.getTor() == Tor.ENTRY_RELAY_FAILING) {
			out.add(Line.of(published ? R.string.contact_connection_published
					: R.string.contact_connection_publishing));
		}
		int reach = REACH.get(s.getReach());
		out.add(s.getReach() == Reach.RETRY_LATER
				? Line.after(reach, NEXT_IN) : Line.of(reach));
		if (s.getReach() != Reach.ONLINE) {
			out.add(s.getLastConnectedAgoMs() == UNKNOWN
					? Line.of(R.string.contact_connection_never)
					: Line.after(R.string.contact_connection_last, AGO));
		}
		if (s.getPeerAddress() == PeerAddress.NEW_RETRY_LATER) {
			out.add(Line.after(PEER.get(s.getPeerAddress()), PEER_RETRY_IN));
		} else if (PEER.containsKey(s.getPeerAddress())) {
			out.add(Line.of(PEER.get(s.getPeerAddress())));
		}
		if (OWN.containsKey(s.getOwnRotation())) {
			out.add(Line.of(OWN.get(s.getOwnRotation())));
		}
		int auth = AUTH.get(s.getAuthorization());
		out.add(counted(s.getAuthorization())
				? Line.counting(auth, REPAIRS) : Line.of(auth));
		return out;
	}

	@Test
	public void everyEnumValueHasItsOwnText() {
		assertEquals(Tor.values().length, TOR.size());
		assertEquals(Reach.values().length, REACH.size());
		assertEquals(PeerAddress.values().length - 1, PEER.size());
		assertEquals(OwnRotation.values().length - 1, OWN.size());
		assertEquals(Authorization.values().length, AUTH.size());
		assertEquals(TOR.size(), new java.util.HashSet<>(TOR.values()).size());
		assertEquals(REACH.size(),
				new java.util.HashSet<>(REACH.values()).size());
		assertEquals(AUTH.size(),
				new java.util.HashSet<>(AUTH.values()).size());
	}

	@Test
	public void generationsAreShownWhenEitherSideHasOne() {
		List<Line> both = ContactConnectionLines.lines(snapshot(Tor.CONNECTED,
				Reach.ONLINE, UNKNOWN, PeerAddress.CURRENT, OwnRotation.IDLE,
				Authorization.ACTIVE, 2, 5), true);
		Line last = both.get(both.size() - 1);
		assertEquals(R.string.contact_connection_generation, last.text);
		assertArrayEquals(new long[] {2, 5}, last.getNumbers());

		List<Line> ownOnly = ContactConnectionLines.lines(snapshot(
				Tor.CONNECTED, Reach.ONLINE, UNKNOWN, PeerAddress.CURRENT,
				OwnRotation.IDLE, Authorization.SETTING_UP, 1, 0), true);
		assertEquals(R.string.contact_connection_generation,
				ownOnly.get(ownOnly.size() - 1).text);

		List<Line> none = ContactConnectionLines.lines(snapshot(
				Tor.CONNECTED, Reach.ONLINE, UNKNOWN, PeerAddress.CURRENT,
				OwnRotation.IDLE, Authorization.SETTING_UP, 0, 0), true);
		assertEquals(R.string.contact_connection_auth_setup,
				none.get(none.size() - 1).text);
	}

	@Test
	public void negativeDurationsAreShownAsNow() {
		ContactConnectionSnapshot s = new ContactConnectionSnapshot(
				Tor.CONNECTED, Reach.RETRY_LATER, -500, UNKNOWN,
				PeerAddress.CURRENT, UNKNOWN, OwnRotation.IDLE,
				Authorization.NOT_SUPPORTED, 0, 0, 0);
		Line retry = ContactConnectionLines.lines(s, true).get(2);
		assertEquals(R.string.contact_connection_retry, retry.text);
		assertEquals(0, retry.durationMs);
	}
}
