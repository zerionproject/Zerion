package org.zerionproject.core.crypto;

import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
class Sec1KeyParser implements KeyParser {
	private final String keyType;
	private final ECDomainParameters params;
	private final BigInteger modulus;
	private final int keyBits, bytesPerInt, publicKeyBytes, privateKeyBytes;

	Sec1KeyParser(String keyType, ECDomainParameters params, int keyBits) {
		this.keyType = keyType;
		this.params = params;
		this.keyBits = keyBits;
		modulus = ((ECCurve.Fp) params.getCurve()).getQ();
		bytesPerInt = (keyBits + 7) / 8;
		publicKeyBytes = 1 + 2 * bytesPerInt;
		privateKeyBytes = bytesPerInt;
	}

	@Override
	public PublicKey parsePublicKey(byte[] encodedKey)
			throws GeneralSecurityException {
		if (encodedKey.length != publicKeyBytes)
			throw new GeneralSecurityException();
		if (encodedKey[0] != 4) throw new GeneralSecurityException();
		byte[] xBytes = new byte[bytesPerInt];
		System.arraycopy(encodedKey, 1, xBytes, 0, bytesPerInt);
		BigInteger x = new BigInteger(1, xBytes);
		if (x.compareTo(modulus) >= 0) throw new GeneralSecurityException();
		byte[] yBytes = new byte[bytesPerInt];
		System.arraycopy(encodedKey, 1 + bytesPerInt, yBytes, 0, bytesPerInt);
		BigInteger y = new BigInteger(1, yBytes);
		if (y.compareTo(modulus) >= 0) throw new GeneralSecurityException();
		ECCurve curve = params.getCurve();
		BigInteger a = curve.getA().toBigInteger();
		BigInteger b = curve.getB().toBigInteger();
		BigInteger lhs = y.multiply(y).mod(modulus);
		BigInteger rhs = x.multiply(x).add(a).multiply(x).add(b).mod(modulus);
		if (!lhs.equals(rhs)) throw new GeneralSecurityException();
		ECPoint pub = curve.createPoint(x, y).normalize();
		if (pub.isInfinity()) throw new GeneralSecurityException();
		if (!pub.multiply(params.getN()).isInfinity())
			throw new GeneralSecurityException();
		ECPublicKeyParameters k = new ECPublicKeyParameters(pub, params);
		PublicKey p = new Sec1PublicKey(keyType, k);
		return p;
	}

	@Override
	public PrivateKey parsePrivateKey(byte[] encodedKey)
			throws GeneralSecurityException {
		if (encodedKey.length != privateKeyBytes)
			throw new GeneralSecurityException();
		BigInteger d = new BigInteger(1, encodedKey);
		if (d.compareTo(params.getN()) >= 0)
			throw new GeneralSecurityException();
		ECPrivateKeyParameters k = new ECPrivateKeyParameters(d, params);
		PrivateKey p = new Sec1PrivateKey(keyType, k, keyBits);
		return p;
	}
}
