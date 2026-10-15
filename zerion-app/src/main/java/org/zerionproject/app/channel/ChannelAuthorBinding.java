package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.db.DbException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nullable;

@NotNullByDefault
final class ChannelAuthorBinding {

	static final String LABEL_AUTHOR_KEY =
			"org.zerionproject/CHANNEL_AUTHOR_KEY";
	static final String LABEL_AUTHOR_BINDINGS =
			"org.zerionproject/CHANNEL_AUTHOR_BINDINGS";
	static final int ED25519_PUBLIC_KEY_BYTES = 32;
	static final int BINDING_BYTES = ED25519_PUBLIC_KEY_BYTES + 32;

	private static final byte[] UNBINDABLE = new byte[0];
	private static final int SINGLE_READS = 32;

	private ChannelAuthorBinding() {
	}

	static boolean wellFormed(@Nullable byte[] ed25519PubKey,
			@Nullable byte[] mlDsaPubKey) {
		return ed25519PubKey != null
				&& ed25519PubKey.length == ED25519_PUBLIC_KEY_BYTES
				&& mlDsaPubKey != null && mlDsaPubKey.length
				== PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES;
	}

	static boolean wellFormed(@Nullable byte[] ed25519PubKey,
			@Nullable byte[] mlDsaPubKey, @Nullable byte[] signature) {
		return wellFormed(ed25519PubKey, mlDsaPubKey) && signature != null
				&& signature.length
				== PostQuantumConstants.HYBRID_SIGNATURE_BYTES;
	}

	static byte[] keyHash(CryptoComponent crypto, byte[] mlDsaPubKey) {
		return crypto.hash(LABEL_AUTHOR_KEY, mlDsaPubKey);
	}

	static byte[] digest(CryptoComponent crypto,
			List<ChannelReaction> reactions, List<ChannelComment> comments) {
		Hashes hashes = new Hashes(crypto);
		TreeSet<byte[]> sorted = new TreeSet<>(ChannelAuthorBinding::compare);
		for (ChannelReaction r : reactions) {
			add(hashes, sorted, r.getSignerEd25519PubKey(),
					r.getSignerMlDsaPubKey());
		}
		for (ChannelComment c : comments) {
			add(hashes, sorted, c.getAuthorEd25519PubKey(),
					c.getAuthorMlDsaPubKey());
		}
		ByteBuffer buf = ByteBuffer.allocate(4 + sorted.size()
				* BINDING_BYTES);
		buf.putInt(sorted.size());
		for (byte[] binding : sorted) buf.put(binding);
		return crypto.hash(LABEL_AUTHOR_BINDINGS, buf.array());
	}

	private static int compare(byte[] a, byte[] b) {
		for (int i = 0; i < a.length && i < b.length; i++) {
			int d = (a[i] & 0xFF) - (b[i] & 0xFF);
			if (d != 0) return d;
		}
		return a.length - b.length;
	}

	private static void add(Hashes hashes, Set<byte[]> sorted,
			byte[] ed25519PubKey, @Nullable byte[] mlDsaPubKey) {
		if (mlDsaPubKey == null || !wellFormed(ed25519PubKey, mlDsaPubKey)) {
			return;
		}
		byte[] h = hashes.of(mlDsaPubKey);
		byte[] binding = new byte[BINDING_BYTES];
		System.arraycopy(ed25519PubKey, 0, binding, 0,
				ED25519_PUBLIC_KEY_BYTES);
		System.arraycopy(h, 0, binding, ED25519_PUBLIC_KEY_BYTES, h.length);
		sorted.add(binding);
	}

	private static final class Hashes {

		private final CryptoComponent crypto;
		private final Map<ByteBuffer, byte[]> byKey = new HashMap<>();

		private Hashes(CryptoComponent crypto) {
			this.crypto = crypto;
		}

		private byte[] of(byte[] mlDsaPubKey) {
			ByteBuffer key = ByteBuffer.wrap(mlDsaPubKey);
			byte[] h = byKey.get(key);
			if (h == null) {
				h = keyHash(crypto, mlDsaPubKey);
				byKey.put(key, h);
			}
			return h;
		}
	}

	@NotNullByDefault
	static final class Session {

		private final CryptoComponent crypto;
		private final ChannelAuthorPins pins;
		private final ChannelState state;
		private final Map<String, byte[]> expected = new HashMap<>();
		private final Map<String, byte[]> fresh = new LinkedHashMap<>();
		@Nullable
		private Map<String, byte[]> stored;
		private int reads;

		Session(CryptoComponent crypto, ChannelAuthorPins pins,
				ChannelState state) {
			this.crypto = crypto;
			this.pins = pins;
			this.state = state;
		}

		boolean admits(byte[] ed25519PubKey, @Nullable byte[] mlDsaPubKey)
				throws DbException {
			if (mlDsaPubKey == null
					|| !wellFormed(ed25519PubKey, mlDsaPubKey)) {
				return false;
			}
			byte[] want = expectedFor(ed25519PubKey);
			return want == null
					|| Arrays.equals(want, keyHash(crypto, mlDsaPubKey));
		}

		void bind(byte[] ed25519PubKey, @Nullable byte[] mlDsaPubKey)
				throws DbException {
			if (mlDsaPubKey == null
					|| !wellFormed(ed25519PubKey, mlDsaPubKey)
					|| expectedFor(ed25519PubKey) != null) {
				return;
			}
			String key = ChannelStore.hex(ed25519PubKey);
			byte[] h = keyHash(crypto, mlDsaPubKey);
			fresh.put(key, h);
			expected.put(key, h);
		}

		void commit(ChannelAuthorPins.Held held) throws DbException {
			if (fresh.isEmpty()) return;
			pins.put(state.getChannelId(), fresh, held);
			fresh.clear();
		}

		@Nullable
		private byte[] expectedFor(byte[] ed25519PubKey) throws DbException {
			String key = ChannelStore.hex(ed25519PubKey);
			if (expected.containsKey(key)) return expected.get(key);
			byte[] want = lookup(ed25519PubKey);
			expected.put(key, want);
			return want;
		}

		@Nullable
		private byte[] pinned(byte[] ed25519PubKey) throws DbException {
			if (stored == null && ++reads > SINGLE_READS) {
				stored = pins.getAll(state.getChannelId());
			}
			return stored != null
					? stored.get(ChannelStore.hex(ed25519PubKey))
					: pins.get(state.getChannelId(), ed25519PubKey);
		}

		@Nullable
		private byte[] lookup(byte[] ed25519PubKey) throws DbException {
			if (Arrays.equals(ed25519PubKey,
					state.getPublisherEd25519PubKey())) {
				byte[] ml = state.getPublisherMlDsaPubKey();
				return ml == null || !wellFormed(ed25519PubKey, ml)
						? UNBINDABLE : keyHash(crypto, ml);
			}
			byte[] pinned = pinned(ed25519PubKey);
			if (pinned != null) return pinned;
			byte[] certified = null;
			List<ChannelDelegationCert> certs = new ArrayList<>(
					state.getActiveDelegations());
			certs.addAll(state.getRetiredDelegations());
			for (ChannelDelegationCert c : certs) {
				if (!Arrays.equals(c.getDelegateeEd25519PubKey(),
						ed25519PubKey)) {
					continue;
				}
				byte[] ml = c.getDelegateeMlDsaPubKey();
				if (!wellFormed(ed25519PubKey, ml)) return UNBINDABLE;
				byte[] h = keyHash(crypto, ml);
				if (certified == null) {
					certified = h;
				} else if (!Arrays.equals(certified, h)) {
					return UNBINDABLE;
				}
			}
			return certified;
		}
	}
}
