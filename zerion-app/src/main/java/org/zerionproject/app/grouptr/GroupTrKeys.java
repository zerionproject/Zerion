package org.zerionproject.app.grouptr;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.zerionproject.core.util.StringUtils.fromHexString;
import static org.zerionproject.core.util.StringUtils.toHexString;

@ThreadSafe
@NotNullByDefault
final class GroupTrKeys {

	static final String KEYS_PREFIX = "grouptr.k.";
	static final String BINDINGS_PREFIX = "grouptr.b.";
	static final String LABEL_BINDING = "org.zerionproject/GROUP_KEY_BINDING";
	static final String LABEL_PROBE = "org.zerionproject/GROUP_KEY_PROBE";
	static final String LABEL_ML_DSA = "org.zerionproject/GROUP_KEY_ML_DSA";
	static final String LABEL_REMOVED_TAG =
			"org.zerionproject/GROUP_REMOVED_TAG";
	static final String LABEL_PROBE_KEY =
			"org.zerionproject/GROUP_KEY_PROBE_KEY";
	static final String LABEL_SECRET = "org.zerionproject/GROUP_SECRET";
	static final String LABEL_DEPARTED = "org.zerionproject/GROUP_DEPARTED";
	static final byte SECRET_DOMAIN = 0x0D;
	static final int SECRET_LENGTH = 32;
	static final int MAX_DEPARTED = 16;
	static final int BINDING_BYTES = 32
			+ PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES + 8
			+ 2 * PostQuantumConstants.HYBRID_SIGNATURE_BYTES;
	static final int SEALED_BINDING_BYTES = BINDING_BYTES + 16;
	private static final String AES_GCM = "AES/GCM/NoPadding";
	static final byte BINDING_DOMAIN = 0x0B;
	static final int BINDING_INPUT_LENGTH = 32 * 4 + 8 + 1;
	static final int PROBE_NONCE_LENGTH = 32;
	static final long ANSWER_AGAIN_MS = 24L * 60L * 60L * 1000L;

	private static final String K_PUB = "pub";
	private static final String K_PRIVATE = "private";
	private static final String K_ML_DSA = "mlDsa";
	private static final String K_ML_DSA_PRIVATE = "mlDsaPrivate";
	private static final String K_CREATED = "created";
	private static final String K_PROBED_PREFIX = "probed.";
	private static final String K_PROBE_DUE = "probeDue";
	private static final String K_PENDING_PREFIX = "pending.";
	private static final String K_ANSWERED_PREFIX = "answered.";
	private static final String K_SECRET = "secret";
	private static final String K_SECRET_SEQ = "secretSeq";
	private static final String K_DEPARTED = "departed";
	private static final String K_PREVIOUS_SECRET = "previousSecret";
	private static final String K_PREVIOUS_UNTIL = "previousUntil";
	static final long PREVIOUS_SECRET_KEPT_MS = 14L * 24L * 60L * 60L * 1000L;

	static class Pub {

		final byte[] pub;
		final byte[] mlDsa;
		final long created;

		private Pub(byte[] pub, byte[] mlDsa, long created) {
			this.pub = pub;
			this.mlDsa = mlDsa;
			this.created = created;
		}
	}

	static final class Own extends Pub {

		private final byte[] privateKey;
		private final byte[] mlDsaPrivateKey;

		private Own(byte[] pub, byte[] mlDsa, long created, byte[] privateKey,
				byte[] mlDsaPrivateKey) {
			super(pub, mlDsa, created);
			this.privateKey = privateKey;
			this.mlDsaPrivateKey = mlDsaPrivateKey;
		}

		void clear() {
			Arrays.fill(privateKey, (byte) 0);
			Arrays.fill(mlDsaPrivateKey, (byte) 0);
		}
	}

	static final class Bound {

		final int contactId;
		final byte[] identity;
		final byte[] pub;
		final byte[] mlDsa;
		final long timestamp;

		Bound(int contactId, byte[] identity, byte[] pub, byte[] mlDsa,
				long timestamp) {
			this.contactId = contactId;
			this.identity = identity;
			this.pub = pub;
			this.mlDsa = mlDsa;
			this.timestamp = timestamp;
		}
	}

	static final class Pending {

		final Bound bound;
		final long received;
		final boolean answer;

		private Pending(Bound bound, long received, boolean answer) {
			this.bound = bound;
			this.received = received;
			this.answer = answer;
		}
	}

	static final class Binding {

		final byte[] pub;
		final byte[] mlDsa;
		final long timestamp;
		final byte[] identitySig;
		final byte[] groupSig;

		Binding(byte[] pub, byte[] mlDsa, long timestamp, byte[] identitySig,
				byte[] groupSig) {
			this.pub = pub;
			this.mlDsa = mlDsa;
			this.timestamp = timestamp;
			this.identitySig = identitySig;
			this.groupSig = groupSig;
		}

		static Binding parse(BdfList l) throws FormatException {
			if (l.size() != 5) throw new FormatException();
			byte[] pub = l.getRaw(0);
			byte[] mlDsa = l.getRaw(1);
			long timestamp = l.getLong(2);
			byte[] identitySig = l.getRaw(3);
			byte[] groupSig = l.getRaw(4);
			if (pub.length != 32
					|| mlDsa.length != PostQuantumConstants
					.ML_DSA_65_PUBLIC_KEY_BYTES
					|| timestamp < 0L
					|| identitySig.length != PostQuantumConstants
					.HYBRID_SIGNATURE_BYTES
					|| groupSig.length != PostQuantumConstants
					.HYBRID_SIGNATURE_BYTES) {
				throw new FormatException();
			}
			return new Binding(pub, mlDsa, timestamp, identitySig, groupSig);
		}

		BdfList toList() {
			return BdfList.of(pub, mlDsa, timestamp, identitySig, groupSig);
		}
	}

	private final SettingsManager settingsManager;
	private final CryptoComponent crypto;
	private final ClientHelper clientHelper;

	GroupTrKeys(SettingsManager settingsManager, CryptoComponent crypto,
			ClientHelper clientHelper) {
		this.settingsManager = settingsManager;
		this.crypto = crypto;
		this.clientHelper = clientHelper;
	}

	static String keysNamespace(byte[] groupId) {
		return KEYS_PREFIX + toHexString(groupId);
	}

	static String bindingsNamespace(byte[] groupId) {
		return BINDINGS_PREFIX + toHexString(groupId);
	}

	static List<String> namespaces(byte[] groupId) {
		return Arrays.asList(keysNamespace(groupId),
				bindingsNamespace(groupId));
	}

	Own generate(long now) {
		KeyPair pair = crypto.generateHybridSignatureKeyPair();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) pair.getPublic();
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) pair.getPrivate();
		try {
			return new Own(pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(),
					now, priv.getEd25519PrivateKey(),
					priv.getMlDsaPrivateKey());
		} finally {
			priv.clear();
		}
	}

	void store(Transaction txn, byte[] groupId, Own own) throws DbException {
		Settings out = new Settings();
		out.put(K_PUB, toHexString(own.pub));
		out.put(K_PRIVATE, toHexString(own.privateKey));
		out.put(K_ML_DSA, toHexString(own.mlDsa));
		out.put(K_ML_DSA_PRIVATE, toHexString(own.mlDsaPrivateKey));
		out.putLong(K_CREATED, own.created);
		settingsManager.mergeSettings(txn, out, keysNamespace(groupId));
	}

	@Nullable
	Own own(byte[] groupId) throws DbException {
		return parseOwn(settingsManager.getSettings(keysNamespace(groupId)));
	}

	@Nullable
	Pub pub(byte[] groupId) throws DbException {
		return parsePub(settingsManager.getSettings(keysNamespace(groupId)));
	}

	@Nullable
	Pub pub(Transaction txn, byte[] groupId) throws DbException {
		return parsePub(settingsManager.getSettings(txn,
				keysNamespace(groupId)));
	}

	@Nullable
	private static Pub parsePub(Settings s) {
		String pub = s.get(K_PUB);
		String mlDsa = s.get(K_ML_DSA);
		if (pub == null || mlDsa == null || pub.isEmpty() || mlDsa.isEmpty()) {
			return null;
		}
		try {
			Pub p = new Pub(fromHexString(pub), fromHexString(mlDsa),
					s.getLong(K_CREATED, 0L));
			if (p.pub.length != 32 || p.mlDsa.length
					!= PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES) {
				return null;
			}
			return p;
		} catch (FormatException ex) {
			return null;
		}
	}

	@Nullable
	private static Own parseOwn(Settings s) {
		Pub p = parsePub(s);
		String priv = s.get(K_PRIVATE);
		String mlDsaPriv = s.get(K_ML_DSA_PRIVATE);
		if (p == null || priv == null || mlDsaPriv == null || priv.isEmpty()
				|| mlDsaPriv.isEmpty()) {
			return null;
		}
		try {
			Own own = new Own(p.pub, p.mlDsa, p.created, fromHexString(priv),
					fromHexString(mlDsaPriv));
			if (own.privateKey.length != 32
					|| own.mlDsaPrivateKey.length != PostQuantumConstants
					.ML_DSA_65_PRIVATE_KEY_BYTES) {
				own.clear();
				return null;
			}
			return own;
		} catch (FormatException ex) {
			return null;
		}
	}

	byte[] sign(Own own, String label, byte[] signed) throws DbException {
		HybridSignaturePrivateKey key = new HybridSignaturePrivateKey(
				own.privateKey, own.mlDsaPrivateKey);
		try {
			return crypto.hybridSign(label, signed, key);
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		} finally {
			key.clear();
		}
	}

	byte[] mlDsaHash(byte[] mlDsa) {
		return crypto.hash(LABEL_ML_DSA, mlDsa);
	}

	byte[] removalTag(byte[] groupId, byte[] identity) {
		return crypto.hash(LABEL_REMOVED_TAG, groupId, identity);
	}

	byte[] bindingInput(byte[] groupId, byte[] identity, byte[] pub,
			byte[] mlDsa, long timestamp) {
		ByteBuffer b = ByteBuffer.allocate(BINDING_INPUT_LENGTH);
		b.put(groupId);
		b.put(identity);
		b.put(pub);
		b.put(mlDsaHash(mlDsa));
		b.putLong(timestamp);
		b.put(BINDING_DOMAIN);
		return b.array();
	}

	Binding bind(byte[] groupId, Own own, byte[] identity,
			HybridSignaturePrivateKey identityKey, long timestamp)
			throws DbException {
		byte[] signed = bindingInput(groupId, identity, own.pub, own.mlDsa,
				timestamp);
		byte[] identitySig;
		try {
			identitySig = crypto.hybridSign(LABEL_BINDING, signed, identityKey);
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		}
		byte[] groupSig = sign(own, LABEL_BINDING, signed);
		return new Binding(own.pub, own.mlDsa, timestamp, identitySig,
				groupSig);
	}

	boolean verify(byte[] groupId, Binding b, byte[] identity,
			@Nullable byte[] identityMlDsa) {
		if (identity.length != 32 || identityMlDsa == null
				|| identityMlDsa.length != PostQuantumConstants
				.ML_DSA_65_PUBLIC_KEY_BYTES) {
			return false;
		}
		if (Arrays.equals(identity, b.pub)) return false;
		byte[] signed = bindingInput(groupId, identity, b.pub, b.mlDsa,
				b.timestamp);
		return verifyHybrid(b.identitySig, LABEL_BINDING, signed, identity,
				identityMlDsa)
				&& verifyHybrid(b.groupSig, LABEL_BINDING, signed, b.pub,
				b.mlDsa);
	}

	boolean verifyHybrid(byte[] sig, String label, byte[] signed,
			byte[] ed25519, byte[] mlDsa) {
		if (signed.length == 0 || ed25519.length != 32
				|| mlDsa.length != PostQuantumConstants
				.ML_DSA_65_PUBLIC_KEY_BYTES
				|| sig.length != PostQuantumConstants.HYBRID_SIGNATURE_BYTES) {
			return false;
		}
		try {
			return crypto.verifyHybridSignature(sig, label, signed,
					new HybridSignaturePublicKey(ed25519, mlDsa));
		} catch (GeneralSecurityException | IllegalArgumentException ex) {
			return false;
		}
	}

	Map<Integer, Bound> bindings(byte[] groupId) throws DbException {
		return parseBindings(settingsManager.getSettings(
				bindingsNamespace(groupId)));
	}

	Map<Integer, Bound> bindings(Transaction txn, byte[] groupId)
			throws DbException {
		return parseBindings(settingsManager.getSettings(txn,
				bindingsNamespace(groupId)));
	}

	private Map<Integer, Bound> parseBindings(Settings s) {
		Map<Integer, Bound> out = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : s.entrySet()) {
			int contactId;
			try {
				contactId = Integer.parseInt(e.getKey());
			} catch (NumberFormatException ex) {
				continue;
			}
			Bound b = parseBound(contactId, e.getValue());
			if (b != null) out.put(contactId, b);
		}
		return out;
	}

	@Nullable
	private Bound parseBound(int contactId, @Nullable String value) {
		if (value == null || value.isEmpty()) return null;
		try {
			BdfList l = clientHelper.toList(fromHexString(value));
			Bound b = new Bound(contactId, l.getRaw(0), l.getRaw(1),
					l.getRaw(2), l.getLong(3));
			if (b.identity.length != 32 || b.pub.length != 32) return null;
			return b;
		} catch (FormatException ex) {
			return null;
		}
	}

	private String encodeBound(byte[] identity, byte[] pub, byte[] mlDsa,
			long timestamp) throws DbException {
		try {
			return toHexString(clientHelper.toByteArray(BdfList.of(identity,
					pub, mlDsa, timestamp)));
		} catch (FormatException ex) {
			throw new DbException(ex);
		}
	}

	void storeBinding(Transaction txn, byte[] groupId, int contactId,
			byte[] identity, byte[] pub, byte[] mlDsa, long timestamp)
			throws DbException {
		Settings out = new Settings();
		out.put(Integer.toString(contactId),
				encodeBound(identity, pub, mlDsa, timestamp));
		settingsManager.mergeSettings(txn, out, bindingsNamespace(groupId));
	}

	Map<Integer, Pending> pending(byte[] groupId) throws DbException {
		Settings s = settingsManager.getSettings(bindingsNamespace(groupId));
		Map<Integer, Pending> out = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : s.entrySet()) {
			if (!e.getKey().startsWith(K_PENDING_PREFIX)) continue;
			String value = e.getValue();
			if (value == null || value.isEmpty()) continue;
			String[] parts = value.split(":");
			if (parts.length != 3) continue;
			try {
				int contactId = Integer.parseInt(
						e.getKey().substring(K_PENDING_PREFIX.length()));
				long received = Long.parseLong(parts[0]);
				Bound b = parseBound(contactId, parts[2]);
				if (b != null) {
					out.put(contactId, new Pending(b, received,
							parts[1].equals("1")));
				}
			} catch (NumberFormatException ex) {
			}
		}
		return out;
	}

	void storePending(Transaction txn, byte[] groupId, int contactId,
			byte[] identity, Binding b, long received, boolean answer)
			throws DbException {
		Settings out = new Settings();
		out.put(K_PENDING_PREFIX + contactId, received + ":"
				+ (answer ? "1" : "0") + ":"
				+ encodeBound(identity, b.pub, b.mlDsa, b.timestamp));
		settingsManager.mergeSettings(txn, out, bindingsNamespace(groupId));
	}

	void dropPending(Transaction txn, byte[] groupId,
			java.util.Collection<Integer> contactIds) throws DbException {
		if (contactIds.isEmpty()) return;
		List<String> keys = new ArrayList<>(contactIds.size());
		for (int id : contactIds) keys.add(K_PENDING_PREFIX + id);
		settingsManager.deleteSettings(txn, bindingsNamespace(groupId), keys);
	}

	boolean answeredRecently(byte[] groupId, int contactId, byte[] pub,
			long now) throws DbException {
		String value = settingsManager.getSettings(bindingsNamespace(groupId))
				.get(K_ANSWERED_PREFIX + contactId);
		if (value == null || value.isEmpty()) return false;
		int at = value.indexOf(':');
		if (at < 0) return false;
		if (!value.substring(at + 1).equals(toHexString(pub))) return false;
		try {
			long when = Long.parseLong(value.substring(0, at));
			return when <= now && now - when < ANSWER_AGAIN_MS;
		} catch (NumberFormatException ex) {
			return false;
		}
	}

	void markAnswered(Transaction txn, byte[] groupId, int contactId,
			byte[] pub, long now) throws DbException {
		Settings out = new Settings();
		out.put(K_ANSWERED_PREFIX + contactId, now + ":" + toHexString(pub));
		settingsManager.mergeSettings(txn, out, bindingsNamespace(groupId));
	}

	boolean probed(Transaction txn, byte[] groupId, int contactId)
			throws DbException {
		String v = settingsManager.getSettings(txn, keysNamespace(groupId))
				.get(K_PROBED_PREFIX + contactId);
		return v != null && !v.isEmpty();
	}

	void markProbed(Transaction txn, byte[] groupId, int contactId, long now)
			throws DbException {
		Settings out = new Settings();
		out.putLong(K_PROBED_PREFIX + contactId, now);
		settingsManager.mergeSettings(txn, out, keysNamespace(groupId));
	}

	long probeDue(byte[] groupId) throws DbException {
		return settingsManager.getSettings(keysNamespace(groupId))
				.getLong(K_PROBE_DUE, -1L);
	}

	void setProbeDue(Transaction txn, byte[] groupId, long due)
			throws DbException {
		Settings out = new Settings();
		out.putLong(K_PROBE_DUE, due);
		settingsManager.mergeSettings(txn, out, keysNamespace(groupId));
	}

	void clearProbeDue(Transaction txn, byte[] groupId) throws DbException {
		settingsManager.deleteSettings(txn, keysNamespace(groupId),
				Collections.singletonList(K_PROBE_DUE));
	}

	static final class Secret {

		final long seq;
		final byte[] secret;
		final List<byte[]> departed;

		Secret(long seq, byte[] secret, List<byte[]> departed) {
			this.seq = seq;
			this.secret = secret;
			this.departed = departed;
		}
	}

	@Nullable
	Secret secret(byte[] groupId) throws DbException {
		Settings s = settingsManager.getSettings(keysNamespace(groupId));
		String secret = s.get(K_SECRET);
		long seq = s.getLong(K_SECRET_SEQ, -1L);
		if (secret == null || secret.isEmpty() || seq < 0L) return null;
		try {
			byte[] raw = fromHexString(secret);
			if (raw.length != SECRET_LENGTH) return null;
			List<byte[]> departed = new ArrayList<>();
			String d = s.get(K_DEPARTED);
			if (d != null && !d.isEmpty()) {
				for (String item : d.split(",")) {
					byte[] entry = fromHexString(item);
					if (entry.length == 33) departed.add(entry);
				}
			}
			return new Secret(seq, raw, departed);
		} catch (FormatException ex) {
			return null;
		}
	}

	void storeSecret(Transaction txn, byte[] groupId, Secret secret, long now)
			throws DbException {
		List<String> items = new ArrayList<>();
		for (byte[] d : secret.departed) items.add(toHexString(d));
		String next = toHexString(secret.secret);
		String held = settingsManager.getSettings(txn, keysNamespace(groupId))
				.get(K_SECRET);
		Settings out = new Settings();
		if (held != null && !held.isEmpty() && !held.equals(next)) {
			out.put(K_PREVIOUS_SECRET, held);
			out.putLong(K_PREVIOUS_UNTIL, now + PREVIOUS_SECRET_KEPT_MS);
		}
		out.put(K_SECRET, next);
		out.putLong(K_SECRET_SEQ, secret.seq);
		out.put(K_DEPARTED, String.join(",", items));
		settingsManager.mergeSettings(txn, out, keysNamespace(groupId));
	}

	@Nullable
	byte[] previousSecret(byte[] groupId, long now) throws DbException {
		Settings s = settingsManager.getSettings(keysNamespace(groupId));
		String held = s.get(K_PREVIOUS_SECRET);
		long until = s.getLong(K_PREVIOUS_UNTIL, -1L);
		if (held == null || held.isEmpty() || now >= until
				|| until > now + PREVIOUS_SECRET_KEPT_MS) {
			return null;
		}
		try {
			byte[] raw = fromHexString(held);
			return raw.length == SECRET_LENGTH ? raw : null;
		} catch (FormatException ex) {
			return null;
		}
	}

	byte[] departedHash(List<byte[]> departed) {
		return crypto.hash(LABEL_DEPARTED, departed.toArray(new byte[0][]));
	}

	byte[] secretInput(byte[] groupId, long epoch, Secret secret) {
		ByteBuffer b = ByteBuffer.allocate(32 + 8 + 8 + SECRET_LENGTH + 32 + 1);
		b.put(groupId);
		b.putLong(secret.seq);
		b.putLong(epoch);
		b.put(secret.secret);
		b.put(departedHash(secret.departed));
		b.put(SECRET_DOMAIN);
		return b.array();
	}

	static BdfList secretList(Secret secret, byte[] sig) {
		BdfList departed = new BdfList();
		for (byte[] d : secret.departed) departed.add(d);
		return BdfList.of(secret.seq, secret.secret, departed, sig);
	}

	static Secret parseSecret(BdfList l) throws FormatException {
		if (l.size() != 4) throw new FormatException();
		long seq = l.getLong(0);
		byte[] secret = l.getRaw(1);
		BdfList departed = l.getList(2);
		if (seq < 0L || secret.length != SECRET_LENGTH
				|| departed.size() > MAX_DEPARTED) {
			throw new FormatException();
		}
		List<byte[]> out = new ArrayList<>(departed.size());
		for (int i = 0; i < departed.size(); i++) {
			byte[] d = departed.getRaw(i);
			if (d.length != 33) throw new FormatException();
			out.add(d);
		}
		return new Secret(seq, secret, out);
	}

	byte[] probeTag(byte[] secret, byte[] nonce) {
		return crypto.hash(LABEL_PROBE, secret, nonce);
	}

	static byte[] encodeBinding(Binding b) {
		ByteBuffer out = ByteBuffer.allocate(BINDING_BYTES);
		out.put(b.pub);
		out.put(b.mlDsa);
		out.putLong(b.timestamp);
		out.put(b.identitySig);
		out.put(b.groupSig);
		return out.array();
	}

	static Binding decodeBinding(byte[] encoded) throws FormatException {
		if (encoded.length != BINDING_BYTES) throw new FormatException();
		ByteBuffer in = ByteBuffer.wrap(encoded);
		byte[] pub = new byte[32];
		byte[] mlDsa =
				new byte[PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES];
		byte[] identitySig =
				new byte[PostQuantumConstants.HYBRID_SIGNATURE_BYTES];
		byte[] groupSig = new byte[PostQuantumConstants.HYBRID_SIGNATURE_BYTES];
		in.get(pub);
		in.get(mlDsa);
		long timestamp = in.getLong();
		in.get(identitySig);
		in.get(groupSig);
		if (timestamp < 0L) throw new FormatException();
		return new Binding(pub, mlDsa, timestamp, identitySig, groupSig);
	}

	byte[] seal(byte[] secret, byte[] nonce, Binding b) throws DbException {
		return cipher(Cipher.ENCRYPT_MODE, secret, nonce, encodeBinding(b));
	}

	@Nullable
	Binding open(byte[] secret, byte[] nonce, byte[] sealed) {
		if (sealed.length != SEALED_BINDING_BYTES) return null;
		try {
			return decodeBinding(cipher(Cipher.DECRYPT_MODE, secret, nonce,
					sealed));
		} catch (DbException | FormatException ex) {
			return null;
		}
	}

	private byte[] cipher(int mode, byte[] secret, byte[] nonce, byte[] in)
			throws DbException {
		byte[] key = crypto.hash(LABEL_PROBE_KEY, secret, nonce);
		try {
			Cipher cipher = Cipher.getInstance(AES_GCM);
			cipher.init(mode, new SecretKeySpec(key, "AES"),
					new GCMParameterSpec(128, new byte[12]));
			return cipher.doFinal(in);
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		} finally {
			Arrays.fill(key, (byte) 0);
		}
	}
}
