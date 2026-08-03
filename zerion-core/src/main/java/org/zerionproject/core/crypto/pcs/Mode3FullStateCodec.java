package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.crypto.pcs.KpId;
import org.zerionproject.core.api.crypto.pcs.MlKemKeyPair;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_DECAPSULATION_KEY_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_EK_SEED_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_EK_VECTOR_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_ENCAPSULATION_KEY_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_RECV_SK_LRU_SIZE;

@NotNullByDefault
final class Mode3FullStateCodec {

	private static final byte VERSION = 0x03;
	private static final byte FLAG_PRESENT = 0x01;
	private static final byte FLAG_ABSENT = 0x00;

	private Mode3FullStateCodec() {}

	static byte[] encode(Mode3FullState state) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(VERSION);
		byte[] theirPk = state.getTheirActivePqPk();
		if (theirPk == null) {
			out.write(FLAG_ABSENT);
		} else {
			if (theirPk.length != MLKEM_ENCAPSULATION_KEY_SIZE)
				throw new IllegalArgumentException();
			out.write(FLAG_PRESENT);
			out.write(theirPk, 0, theirPk.length);
		}
		writeKeyPair(out, state.getOurActiveKeyPair());
		Map<KpId, MlKemKeyPair> recent = state.getRecentKeyPairs();
		int size = recent.size();
		if (size > 65535) throw new IllegalStateException();
		ByteBuffer count = ByteBuffer.allocate(Short.BYTES);
		count.putShort((short) size);
		out.write(count.array(), 0, Short.BYTES);
		for (Map.Entry<KpId, MlKemKeyPair> e : recent.entrySet()) {
			out.write(e.getKey().getBytes(), 0, KpId.SIZE);
			writeKeyPair(out, e.getValue());
		}
		ByteBuffer counter = ByteBuffer.allocate(Long.BYTES);
		counter.putLong(state.getMessageCounter());
		out.write(counter.array(), 0, Long.BYTES);
		return out.toByteArray();
	}

	@Nullable
	static Mode3FullState decode(byte[] blob) {
		try {
			ByteBuffer buf = ByteBuffer.wrap(blob);
			byte version = buf.get();
			if (version != VERSION) return null;
			byte flag = buf.get();
			byte[] theirPk = null;
			if (flag == FLAG_PRESENT) {
				theirPk = new byte[MLKEM_ENCAPSULATION_KEY_SIZE];
				buf.get(theirPk);
			} else if (flag != FLAG_ABSENT) {
				return null;
			}
			MlKemKeyPair ourKp = readKeyPair(buf);
			int recentCount = Short.toUnsignedInt(buf.getShort());
			if (recentCount > MODE3_FULL_RECV_SK_LRU_SIZE) return null;
			LinkedHashMap<KpId, MlKemKeyPair> recent =
					new LinkedHashMap<>(recentCount);
			for (int i = 0; i < recentCount; i++) {
				byte[] kpIdBytes = new byte[KpId.SIZE];
				buf.get(kpIdBytes);
				KpId kpId = new KpId(kpIdBytes);
				MlKemKeyPair kp = readKeyPair(buf);
				recent.put(kpId, kp);
			}
			long counter = buf.getLong();
			if (buf.hasRemaining()) return null;
			return new Mode3FullState(theirPk, ourKp, recent, counter);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static void writeKeyPair(ByteArrayOutputStream out,
			MlKemKeyPair kp) {
		out.write(kp.getEkSeed(), 0, MLKEM_EK_SEED_SIZE);
		out.write(kp.getEkVector(), 0, MLKEM_EK_VECTOR_SIZE);
		out.write(kp.getDecapsulationKey(), 0, MLKEM_DECAPSULATION_KEY_SIZE);
	}

	private static MlKemKeyPair readKeyPair(ByteBuffer buf) {
		byte[] ekSeed = new byte[MLKEM_EK_SEED_SIZE];
		buf.get(ekSeed);
		byte[] ekVector = new byte[MLKEM_EK_VECTOR_SIZE];
		buf.get(ekVector);
		byte[] decapKey = new byte[MLKEM_DECAPSULATION_KEY_SIZE];
		buf.get(decapKey);
		return MlKemKeyPair.fromComponents(ekSeed, ekVector, decapKey);
	}
}
