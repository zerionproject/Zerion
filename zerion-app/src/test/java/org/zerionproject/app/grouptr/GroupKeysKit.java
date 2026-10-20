package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.GroupTrPostAcceptedEvent;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.data.BdfList;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.util.StringUtils.toHexString;

final class GroupKeysKit {

	private GroupKeysKit() {
	}

	static byte[] key(InviteTestNet.Node n, byte[] groupId) throws Exception {
		return n.manager.getLocalMemberKey(groupId);
	}

	static byte[] mlOf(byte[] ed25519) {
		byte[] ml = new byte[PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES];
		for (int i = 0; i < ml.length; i++) ml[i] = ed25519[i % ed25519.length];
		ml[0] ^= 0x5A;
		return ml;
	}

	static byte[][] freshGroupKey() {
		HybridSignaturePublicKey pub = (HybridSignaturePublicKey)
				InviteTestNet.groupKeyPair().getPublic();
		return new byte[][] {pub.getEd25519PublicKey(), pub.getMlDsaPublicKey()};
	}

	static byte[] signAs(byte[] ed25519, String label, byte[] signed) {
		return InviteTestNet.signature(label, signed, ed25519, mlOf(ed25519));
	}

	static void join(InviteTestNet net, InviteTestNet.Node creator,
			byte[] groupId, InviteTestNet.Node n) throws Exception {
		creator.manager.inviteContactToGroup(groupId, creator.contactIdOf(n),
				n.pub, n.name);
		net.pump();
		n.manager.acceptInvite(groupId);
		net.pump();
		assertTrue(creator.manager.isMember(groupId, key(n, groupId)));
	}

	static byte[] bindingInput(byte[] groupId, byte[] identity, byte[] pub,
			byte[] mlDsa, long timestamp) {
		ByteBuffer b = ByteBuffer.allocate(GroupTrKeys.BINDING_INPUT_LENGTH);
		b.put(groupId);
		b.put(identity);
		b.put(pub);
		b.put(InviteTestNet.hash(GroupTrKeys.LABEL_ML_DSA,
				new byte[][] {mlDsa}));
		b.putLong(timestamp);
		b.put(GroupTrKeys.BINDING_DOMAIN);
		return b.array();
	}

	static BdfList binding(byte[] signedGroupId, InviteTestNet.Node identity,
			byte[] pub, long timestamp, boolean identitySigned,
			boolean groupSigned) {
		byte[] ml = mlOf(pub);
		byte[] signed = bindingInput(signedGroupId, identity.pub, pub, ml,
				timestamp);
		byte[] identitySig = InviteTestNet.sign(identity,
				GroupTrKeys.LABEL_BINDING, signed);
		byte[] groupSig = signAs(pub, GroupTrKeys.LABEL_BINDING, signed);
		if (!identitySigned) identitySig[0] ^= 1;
		if (!groupSigned) groupSig[0] ^= 1;
		return BdfList.of(pub, ml, timestamp, identitySig, groupSig);
	}

	static byte[] postSignedInput(byte[] groupId, int epoch, byte[] sender,
			String name, byte[] body, long timestamp, long ttl) {
		ByteBuffer b = ByteBuffer.allocate(32 + 4 + 32 + 32 + 32 + 8 + 8);
		b.put(groupId);
		b.putInt(epoch);
		b.put(sender);
		b.put(InviteTestNet.hash("org.zerionproject/GROUP_POST_NAME",
				new byte[][] {name.getBytes(StandardCharsets.UTF_8)}));
		b.put(InviteTestNet.hash(MessagingManager.GROUP_POST_BODY_HASH_LABEL,
				new byte[][] {body}));
		b.putLong(timestamp);
		b.putLong(ttl);
		return b.array();
	}

	static BdfList post(byte[] groupId, long epoch, byte[] sender,
			byte[] signerEd, byte[] signerMl, byte[] body, long timestamp) {
		byte[] signed = postSignedInput(groupId, (int) epoch, sender, "Name",
				body, timestamp, 0L);
		byte[] sig = InviteTestNet.signature("org.zerionproject/GROUP_POST",
				signed, signerEd, signerMl);
		return BdfList.of(32L, groupId, epoch, sender, "Name", body, sig);
	}

	static int remotePostsAccepted(InviteTestNet.Node n, byte[] groupId) {
		int count = 0;
		for (Object o : n.broadcasts) {
			if (o instanceof GroupTrPostAcceptedEvent
					&& Arrays.equals(((GroupTrPostAcceptedEvent) o)
					.getGroupId(), groupId)
					&& !((GroupTrPostAcceptedEvent) o).isLocal()) {
				count++;
			}
		}
		return count;
	}

	static List<InviteTestNet.Packet> deliveredTo(InviteTestNet net,
			InviteTestNet.Node n) {
		List<InviteTestNet.Packet> out = new ArrayList<>();
		for (InviteTestNet.Packet p : net.delivered) {
			if (p.to == n) out.add(p);
		}
		return out;
	}

	static List<InviteTestNet.Packet> sentOfType(InviteTestNet.Node n,
			int type) {
		List<InviteTestNet.Packet> out = new ArrayList<>();
		for (InviteTestNet.Packet p : n.sent()) {
			if (p.type() == type) out.add(p);
		}
		return out;
	}

	static Set<String> raw(Object o) {
		Set<String> out = new HashSet<>();
		collect(o, out);
		return out;
	}

	private static void collect(Object o, Set<String> out) {
		if (o instanceof byte[]) {
			out.add(toHexString((byte[]) o));
		} else if (o instanceof List) {
			for (Object item : (List<?>) o) collect(item, out);
		} else if (o instanceof Map) {
			for (Object item : ((Map<?, ?>) o).values()) collect(item, out);
		}
	}

	static boolean carries(Object o, byte[] value) {
		return raw(o).contains(toHexString(value));
	}

	static byte[] secretOf(InviteTestNet.Node n, byte[] groupId)
			throws Exception {
		String hex = n.namespace(GroupTrKeys.keysNamespace(groupId))
				.get("secret");
		return hex == null ? new byte[0]
				: org.zerionproject.core.util.StringUtils.fromHexString(hex);
	}

	static long secretSeqOf(InviteTestNet.Node n, byte[] groupId) {
		return n.namespace(GroupTrKeys.keysNamespace(groupId))
				.getLong("secretSeq", -1L);
	}

	static byte[] seal(byte[] secret, byte[] nonce, BdfList binding)
			throws Exception {
		byte[] key = InviteTestNet.hash(GroupTrKeys.LABEL_PROBE_KEY,
				new byte[][] {secret, nonce});
		javax.crypto.Cipher c =
				javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
		c.init(javax.crypto.Cipher.ENCRYPT_MODE,
				new javax.crypto.spec.SecretKeySpec(key, "AES"),
				new javax.crypto.spec.GCMParameterSpec(128, new byte[12]));
		return c.doFinal(GroupTrKeys.encodeBinding(
				GroupTrKeys.Binding.parse(binding)));
	}

	static BdfList probeEntry(byte[] secret, byte[] nonce, BdfList binding)
			throws Exception {
		return BdfList.of(nonce, InviteTestNet.hash(GroupTrKeys.LABEL_PROBE,
				new byte[][] {secret, nonce}), seal(secret, nonce, binding));
	}

	static boolean containsKey(Map<String, ?> map, byte[] key) {
		return map.containsKey(toHexString(key));
	}
}
