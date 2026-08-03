package org.zerionproject.core.rendezvous;

import org.bouncycastle.crypto.engines.Salsa20Engine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.rendezvous.KeyMaterialSource;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
class KeyMaterialSourceImpl implements KeyMaterialSource {

	@GuardedBy("this")
	private final Salsa20Engine cipher = new Salsa20Engine();

	KeyMaterialSourceImpl(SecretKey sourceKey) {
		KeyParameter k = new KeyParameter(sourceKey.getBytes());
		cipher.init(true, new ParametersWithIV(k, new byte[8]));
	}

	@Override
	public synchronized byte[] getKeyMaterial(int length) {
		byte[] in = new byte[length];
		byte[] out = new byte[length];
		cipher.processBytes(in, 0, length, out, 0);
		return out;
	}
}
