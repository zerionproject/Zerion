package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.sync.ClientId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * The wire records of the activation protocol, carried in this client's
 * per-contact group. A record is a list: type, protocol version, key
 * generation of the sender, then the type's fields. Android and iOS share
 * this layout; see docs/protocol/ONION_CLIENT_AUTH.md.
 */
@NotNullByDefault
public final class OnionAuthRecords {

	public static final ClientId CLIENT_ID =
			new ClientId("org.zerionproject.core.onionauth");
	public static final int MAJOR_VERSION = 0;
	public static final int MINOR_VERSION = 0;

	public static final int PROTOCOL_VERSION = 1;

	public static final int TYPE_OFFER = 0;
	public static final int TYPE_READY = 1;
	public static final int TYPE_PROBE_SUCCESS = 2;
	public static final int TYPE_COMMIT = 3;
	public static final int TYPE_ROTATE = 4;
	public static final int TYPE_ROTATE_ACK = 5;

	static final int ONION_LENGTH = 56;
	static final int KEY_LENGTH = 32;
	static final int FINGERPRINT_LENGTH = 32;

	/** A decoded record. Fields not carried by the type are null. */
	public static final class Record {

		public final int type;
		public final int version;
		public final long keyVersion;
		@Nullable
		public final String onion;
		@Nullable
		public final byte[] publicKey;
		@Nullable
		public final byte[] fingerprint;

		Record(int type, int version, long keyVersion, @Nullable String onion,
				@Nullable byte[] publicKey, @Nullable byte[] fingerprint) {
			this.type = type;
			this.version = version;
			this.keyVersion = keyVersion;
			this.onion = onion;
			this.publicKey = publicKey;
			this.fingerprint = fingerprint;
		}
	}

	private OnionAuthRecords() {
	}

	/**
	 * The offer carries the sender's client public key for the receiver's
	 * service and, once the sender has published its own authorized
	 * service, that address; before then the address is empty and a second
	 * offer follows with it.
	 */
	public static BdfList offer(long keyVersion, @Nullable String onion,
			byte[] publicKey) {
		return BdfList.of(TYPE_OFFER, PROTOCOL_VERSION, keyVersion,
				onion == null ? "" : onion, publicKey);
	}

	public static BdfList ready(long keyVersion) {
		return BdfList.of(TYPE_READY, PROTOCOL_VERSION, keyVersion);
	}

	public static BdfList probeSuccess(long keyVersion) {
		return BdfList.of(TYPE_PROBE_SUCCESS, PROTOCOL_VERSION, keyVersion);
	}

	/**
	 * The commit names the peer's service and the fingerprint of the
	 * peer's client key as this side knows them, so the receiver can check
	 * that both sides commit to the same generation.
	 */
	public static BdfList commit(long keyVersion, String peerOnion,
			byte[] peerKeyFingerprint) {
		return BdfList.of(TYPE_COMMIT, PROTOCOL_VERSION, keyVersion,
				peerOnion, peerKeyFingerprint);
	}

	public static BdfList rotate(long keyVersion, @Nullable String newOnion,
			@Nullable byte[] newPublicKey) {
		return BdfList.of(TYPE_ROTATE, PROTOCOL_VERSION, keyVersion,
				newOnion == null ? "" : newOnion,
				newPublicKey == null ? new byte[0] : newPublicKey);
	}

	public static BdfList rotateAck(long keyVersion) {
		return BdfList.of(TYPE_ROTATE_ACK, PROTOCOL_VERSION, keyVersion);
	}

	/** Parses and validates a record; every shape error is a FormatException. */
	public static Record parse(BdfList body) throws FormatException {
		if (body.size() < 3) throw new FormatException();
		long type = body.getLong(0);
		long version = body.getLong(1);
		long keyVersion = body.getLong(2);
		if (version < 1 || version > PROTOCOL_VERSION) {
			throw new FormatException();
		}
		if (keyVersion < 0) throw new FormatException();
		if (type == TYPE_OFFER) {
			if (body.size() != 5) throw new FormatException();
			String onion = body.getString(3);
			byte[] pub = body.getRaw(4);
			if (!onion.isEmpty()) requireOnion(onion);
			if (pub.length != KEY_LENGTH) throw new FormatException();
			return new Record((int) type, (int) version, keyVersion,
					onion.isEmpty() ? null : onion, pub, null);
		}
		if (type == TYPE_COMMIT) {
			if (body.size() != 5) throw new FormatException();
			String onion = body.getString(3);
			byte[] fp = body.getRaw(4);
			requireOnion(onion);
			if (fp.length != FINGERPRINT_LENGTH) throw new FormatException();
			return new Record((int) type, (int) version, keyVersion, onion,
					null, fp);
		}
		if (type == TYPE_ROTATE) {
			if (body.size() != 5) throw new FormatException();
			String onion = body.getString(3);
			byte[] pub = body.getRaw(4);
			if (!onion.isEmpty()) requireOnion(onion);
			if (pub.length != 0 && pub.length != KEY_LENGTH) {
				throw new FormatException();
			}
			if (onion.isEmpty() && pub.length == 0) {
				throw new FormatException();
			}
			return new Record((int) type, (int) version, keyVersion,
					onion.isEmpty() ? null : onion,
					pub.length == 0 ? null : pub, null);
		}
		if (type == TYPE_READY || type == TYPE_PROBE_SUCCESS
				|| type == TYPE_ROTATE_ACK) {
			if (body.size() != 3) throw new FormatException();
			return new Record((int) type, (int) version, keyVersion, null,
					null, null);
		}
		throw new FormatException();
	}

	static void requireOnion(String onion) throws FormatException {
		if (onion.length() != ONION_LENGTH) throw new FormatException();
		for (int i = 0; i < onion.length(); i++) {
			char ch = onion.charAt(i);
			boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '2' && ch <= '7');
			if (!ok) throw new FormatException();
		}
	}
}
