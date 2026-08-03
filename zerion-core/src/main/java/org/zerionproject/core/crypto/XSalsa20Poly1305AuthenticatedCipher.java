package org.zerionproject.core.crypto;

import org.bouncycastle.crypto.DataLengthException;
import org.bouncycastle.crypto.engines.XSalsa20Engine;
import org.bouncycastle.crypto.generators.Poly1305KeyGenerator;
import org.bouncycastle.crypto.macs.Poly1305;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import javax.annotation.concurrent.NotThreadSafe;

import static org.zerionproject.core.api.transport.TransportConstants.MAC_LENGTH;

@NotThreadSafe
@NotNullByDefault
public class XSalsa20Poly1305AuthenticatedCipher implements AuthenticatedCipher {

	private static final int SUBKEY_LENGTH = 32;

	private final XSalsa20Engine xSalsa20Engine;
	private final Poly1305 poly1305;

	private boolean encrypting;

	public XSalsa20Poly1305AuthenticatedCipher() {
		xSalsa20Engine = new XSalsa20Engine();
		poly1305 = new Poly1305();
	}

	@Override
	public void init(boolean encrypt, SecretKey key, byte[] iv)
			throws GeneralSecurityException {
		encrypting = encrypt;
		KeyParameter k = new KeyParameter(key.getBytes());
		ParametersWithIV params = new ParametersWithIV(k, iv);
		try {
			xSalsa20Engine.init(encrypt, params);
		} catch (IllegalArgumentException e) {
			throw new GeneralSecurityException(e.getMessage());
		}
	}

	@Override
	public int process(byte[] input, int inputOff, int len, byte[] output,
			int outputOff) throws GeneralSecurityException {
		if (!encrypting && len < MAC_LENGTH)
			throw new GeneralSecurityException("Invalid MAC");
		try {
			byte[] zero = new byte[SUBKEY_LENGTH];
			byte[] subKey = new byte[SUBKEY_LENGTH];
			xSalsa20Engine.processBytes(zero, 0, SUBKEY_LENGTH, subKey, 0);
			Poly1305KeyGenerator.clamp(subKey);
			KeyParameter k = new KeyParameter(subKey);
			poly1305.init(k);
			if (!encrypting) {
				byte[] mac = new byte[MAC_LENGTH];
				poly1305.update(input, inputOff + MAC_LENGTH, len - MAC_LENGTH);
				poly1305.doFinal(mac, 0);
				int cmp = 0;
				for (int i = 0; i < MAC_LENGTH; i++)
					cmp |= mac[i] ^ input[inputOff + i];
				if (cmp != 0)
					throw new GeneralSecurityException("Invalid MAC");
			}
			int processed = xSalsa20Engine.processBytes(
					input, encrypting ? inputOff : inputOff + MAC_LENGTH,
					encrypting ? len : len - MAC_LENGTH,
					output, encrypting ? outputOff + MAC_LENGTH : outputOff);
			if (encrypting) {
				poly1305.update(output, outputOff + MAC_LENGTH, len);
				poly1305.doFinal(output, outputOff);
			}

			return encrypting ? processed + MAC_LENGTH : processed;
		} catch (DataLengthException e) {
			throw new GeneralSecurityException(e.getMessage());
		}
	}

	@Override
	public int getMacBytes() {
		return MAC_LENGTH;
	}
}
