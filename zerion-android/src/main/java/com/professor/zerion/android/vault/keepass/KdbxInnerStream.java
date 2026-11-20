package com.professor.zerion.android.vault.keepass;

import org.briarproject.nullsafety.NotNullByDefault;

import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.engines.Salsa20Engine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.util.Arrays;

@NotNullByDefault
final class KdbxInnerStream {

	static final int ID_NONE = 0;
	static final int ID_ARCFOUR = 1;
	static final int ID_SALSA20 = 2;
	static final int ID_CHACHA20 = 3;

	private static final byte[] SALSA20_NONCE = {
			(byte) 0xE8, 0x30, 0x09, 0x4B, (byte) 0x97, 0x20, 0x5D, 0x2A};

	private final StreamCipher cipher;

	private KdbxInnerStream(StreamCipher cipher) {
		this.cipher = cipher;
	}

	static void requireSupported(int id) throws KdbxException {
		if (id != ID_SALSA20 && id != ID_CHACHA20) {
			throw KdbxException.unsupported("inner stream " + id);
		}
	}

	static KdbxInnerStream create(int id, byte[] streamKey)
			throws KdbxException {
		requireSupported(id);
		if (streamKey.length == 0) throw KdbxException.corrupted("stream key");
		if (id == ID_SALSA20) {
			byte[] key = KdbxBytes.sha256(streamKey);
			Salsa20Engine engine = new Salsa20Engine();
			engine.init(true, new ParametersWithIV(new KeyParameter(key),
					SALSA20_NONCE));
			KdbxBytes.wipe(key);
			return new KdbxInnerStream(engine);
		}
		byte[] hash = KdbxBytes.sha512(streamKey);
		byte[] key = Arrays.copyOfRange(hash, 0, 32);
		byte[] nonce = Arrays.copyOfRange(hash, 32, 44);
		ChaCha7539Engine engine = new ChaCha7539Engine();
		engine.init(true, new ParametersWithIV(new KeyParameter(key), nonce));
		KdbxBytes.wipe(hash);
		KdbxBytes.wipe(key);
		return new KdbxInnerStream(engine);
	}

	byte[] process(byte[] data) {
		byte[] out = new byte[data.length];
		cipher.processBytes(data, 0, data.length, out, 0);
		return out;
	}
}
