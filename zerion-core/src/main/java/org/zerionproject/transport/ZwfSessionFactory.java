package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

@NotNullByDefault
public class ZwfSessionFactory {

	private static final String A_ROOT = "org.zerionproject.transport/DIR_A_ROOT";
	private static final String B_ROOT = "org.zerionproject.transport/DIR_B_ROOT";
	private static final String A_TAG = "org.zerionproject.transport/DIR_A_TAG";
	private static final String B_TAG = "org.zerionproject.transport/DIR_B_TAG";
	private static final String A_HDR = "org.zerionproject.transport/DIR_A_HEADER";
	private static final String B_HDR = "org.zerionproject.transport/DIR_B_HEADER";

	private final CryptoComponent crypto;
	private final Mode3FullRatchet mode3FullRatchet;
	private final RootEvolutionCrypto evolutionCrypto;

	public ZwfSessionFactory(CryptoComponent crypto,
			Mode3FullRatchet mode3FullRatchet) {
		this.crypto = crypto;
		this.mode3FullRatchet = mode3FullRatchet;
		this.evolutionCrypto = new RootEvolutionCrypto(crypto);
	}

	public ZwfSession deriveSession(SecretKey rootKey, boolean alice) {
		return deriveSession(ContactRootKeys.atPairing(rootKey), 0, alice);
	}

	public ZwfSession deriveSession(ContactRootKeys keys, long sendEpoch,
			boolean alice) {
		SecretKey sendRoot = keys.getKey(sendEpoch);
		if (sendRoot == null) throw new IllegalArgumentException();
		Mode3FullState sharedM3f = mode3FullRatchet.createInitialState();
		SecretKey t = evolutionCrypto.transportRoot(sendRoot, sendEpoch);
		SecretKey sendRootKey = crypto.deriveKey(alice ? A_ROOT : B_ROOT, t);
		SecretKey sendTagKey = crypto.deriveKey(alice ? A_TAG : B_TAG, t);
		SecretKey sendHeaderKey = crypto.deriveKey(alice ? A_HDR : B_HDR, t);
		if (t != sendRoot) t.clear();
		PcsSessionState sendState = PcsSessionState.createInitialMode3Full(
				sendRootKey, sendRootKey, null, sharedM3f);
		List<ZwfSession.RecvKeys> recv = new ArrayList<>();
		recv.add(recvKeys(sendRoot, sendEpoch, alice, sharedM3f));
		long[] others = {keys.getEpoch(), keys.getPendingEpoch()};
		for (long e : others) {
			if (e == sendEpoch) continue;
			SecretKey root = keys.getKey(e);
			if (root != null) recv.add(recvKeys(root, e, alice, sharedM3f));
		}
		return new ZwfSession(sendEpoch, sendState, sendTagKey, sendHeaderKey,
				recv, alice);
	}

	public SecretKey deriveRecvTagKey(SecretKey rootKey, boolean alice) {
		return deriveRecvTagKey(rootKey, 0, alice);
	}

	public SecretKey deriveRecvTagKey(SecretKey rootKey, long epoch,
			boolean alice) {
		SecretKey t = evolutionCrypto.transportRoot(rootKey, epoch);
		try {
			return crypto.deriveKey(alice ? B_TAG : A_TAG, t);
		} finally {
			if (t != rootKey) t.clear();
		}
	}

	private ZwfSession.RecvKeys recvKeys(SecretKey root, long epoch,
			boolean alice, Mode3FullState sharedM3f) {
		SecretKey t = evolutionCrypto.transportRoot(root, epoch);
		SecretKey recvRootKey = crypto.deriveKey(alice ? B_ROOT : A_ROOT, t);
		SecretKey recvTagKey = crypto.deriveKey(alice ? B_TAG : A_TAG, t);
		SecretKey recvHeaderKey = crypto.deriveKey(alice ? B_HDR : A_HDR, t);
		if (t != root) t.clear();
		PcsSessionState recvState = PcsSessionState.createInitialMode3Full(
				recvRootKey, recvRootKey, null, sharedM3f);
		return new ZwfSession.RecvKeys(epoch, recvState, recvTagKey,
				recvHeaderKey);
	}
}
