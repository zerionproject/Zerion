package com.professor.zerion.android.vault.wallet.btc;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.math.ec.ECPoint;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.List;

public final class TaprootSign {

	private static final ECDomainParameters CURVE = ECKey.CURVE;
	private static final BigInteger N = CURVE.getN();

	private TaprootSign() {
	}

	private static byte[] xOnly(ECPoint p) {
		return p.normalize().getAffineXCoord().getEncoded();
	}

	private static boolean hasEvenY(ECPoint p) {
		return !p.normalize().getAffineYCoord().toBigInteger().testBit(0);
	}

	private static ECPoint mulG(BigInteger k) {
		return CURVE.getG().multiply(k).normalize();
	}

	private static byte[] tagged(String tag, byte[] msg) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			byte[] t = sha.digest(tag.getBytes(
					java.nio.charset.StandardCharsets.US_ASCII));
			sha.reset();
			sha.update(t);
			sha.update(t);
			sha.update(msg);
			return sha.digest();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static byte[] sha256(byte[]... parts) {
		try {
			MessageDigest d = MessageDigest.getInstance("SHA-256");
			for (byte[] p : parts) {
				d.update(p);
			}
			return d.digest();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static byte[] concat(byte[]... parts) {
		int len = 0;
		for (byte[] p : parts) {
			len += p.length;
		}
		byte[] out = new byte[len];
		int off = 0;
		for (byte[] p : parts) {
			System.arraycopy(p, 0, out, off, p.length);
			off += p.length;
		}
		return out;
	}

	private static byte[] to32(BigInteger v) {
		byte[] b = v.toByteArray();
		if (b.length == 32) {
			return b;
		}
		byte[] out = new byte[32];
		if (b.length == 33 && b[0] == 0) {
			System.arraycopy(b, 1, out, 0, 32);
		} else if (b.length < 32) {
			System.arraycopy(b, 0, out, 32 - b.length, b.length);
		} else {
			System.arraycopy(b, b.length - 32, out, 0, 32);
		}
		return out;
	}

	private static byte[] le32(long v) {
		return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16),
				(byte) (v >>> 24)};
	}

	private static byte[] le64(long v) {
		byte[] b = new byte[8];
		long x = v;
		for (int i = 0; i < 8; i++) {
			b[i] = (byte) (x & 0xff);
			x >>= 8;
		}
		return b;
	}

	private static byte[] varInt(long v) {
		if (v < 0xfd) {
			return new byte[]{(byte) v};
		}
		if (v <= 0xffff) {
			return new byte[]{(byte) 0xfd, (byte) v, (byte) (v >>> 8)};
		}
		if (v <= 0xffffffffL) {
			return new byte[]{(byte) 0xfe, (byte) v, (byte) (v >>> 8),
					(byte) (v >>> 16), (byte) (v >>> 24)};
		}
		return concat(new byte[]{(byte) 0xff}, le64(v));
	}

	public static byte[] schnorrSign(BigInteger priv, byte[] msg, byte[] aux) {
		if (priv.signum() == 0 || priv.compareTo(N) >= 0) {
			throw new IllegalArgumentException("bad private key");
		}
		ECPoint p = mulG(priv);
		BigInteger d = hasEvenY(p) ? priv : N.subtract(priv);
		byte[] db = to32(d);
		byte[] a = tagged("BIP0340/aux", aux);
		byte[] t = new byte[32];
		for (int i = 0; i < 32; i++) {
			t[i] = (byte) (db[i] ^ a[i]);
		}
		byte[] rand = tagged("BIP0340/nonce", concat(t, xOnly(p), msg));
		BigInteger k0 = new BigInteger(1, rand).mod(N);
		if (k0.signum() == 0) {
			throw new IllegalStateException("nonce is zero");
		}
		ECPoint r = mulG(k0);
		BigInteger k = hasEvenY(r) ? k0 : N.subtract(k0);
		BigInteger e = new BigInteger(1, tagged("BIP0340/challenge",
				concat(xOnly(r), xOnly(p), msg))).mod(N);
		return concat(xOnly(r), to32(k.add(e.multiply(d)).mod(N)));
	}

	private static ECPoint liftX(byte[] x) {
		return CURVE.getCurve().decodePoint(concat(new byte[]{0x02}, x));
	}

	static boolean schnorrVerify(byte[] pubXOnly, byte[] msg, byte[] sig) {
		if (sig.length != 64) {
			return false;
		}
		try {
			ECPoint p = liftX(pubXOnly);
			byte[] rxBytes = new byte[32];
			byte[] sBytes = new byte[32];
			System.arraycopy(sig, 0, rxBytes, 0, 32);
			System.arraycopy(sig, 32, sBytes, 0, 32);
			BigInteger rx = new BigInteger(1, rxBytes);
			BigInteger s = new BigInteger(1, sBytes);
			if (s.compareTo(N) >= 0) {
				return false;
			}
			BigInteger e = new BigInteger(1, tagged("BIP0340/challenge",
					concat(rxBytes, pubXOnly, msg))).mod(N);
			ECPoint rPoint = mulG(s).add(p.multiply(N.subtract(e))).normalize();
			return !rPoint.isInfinity() && hasEvenY(rPoint)
					&& rPoint.getAffineXCoord().toBigInteger().equals(rx);
		} catch (Exception e) {
			return false;
		}
	}

	public static final class Prevout {
		public final byte[] scriptPubKey;
		public final long amountSat;

		public Prevout(byte[] scriptPubKey, long amountSat) {
			this.scriptPubKey = scriptPubKey;
			this.amountSat = amountSat;
		}
	}

	public static byte[] keyPathSigHash(Transaction tx, List<Prevout> prevouts,
			int inputIndex, int hashType) {
		List<TransactionInput> inputs = tx.getInputs();
		List<TransactionOutput> outputs = tx.getOutputs();

		ByteArrayOutputStream prevoutsBuf = new ByteArrayOutputStream();
		ByteArrayOutputStream seqBuf = new ByteArrayOutputStream();
		for (TransactionInput inp : inputs) {
			write(prevoutsBuf, inp.getOutpoint().getHash().getReversedBytes());
			write(prevoutsBuf, le32(inp.getOutpoint().getIndex()));
			write(seqBuf, le32(inp.getSequenceNumber()));
		}
		ByteArrayOutputStream amountsBuf = new ByteArrayOutputStream();
		ByteArrayOutputStream spkBuf = new ByteArrayOutputStream();
		for (Prevout po : prevouts) {
			write(amountsBuf, le64(po.amountSat));
			write(spkBuf, varInt(po.scriptPubKey.length));
			write(spkBuf, po.scriptPubKey);
		}
		ByteArrayOutputStream outputsBuf = new ByteArrayOutputStream();
		for (TransactionOutput o : outputs) {
			write(outputsBuf, le64(o.getValue().value));
			write(outputsBuf, varInt(o.getScriptBytes().length));
			write(outputsBuf, o.getScriptBytes());
		}

		byte[] shaPrevouts = sha256(prevoutsBuf.toByteArray());
		byte[] shaAmounts = sha256(amountsBuf.toByteArray());
		byte[] shaScriptPubKeys = sha256(spkBuf.toByteArray());
		byte[] shaSequences = sha256(seqBuf.toByteArray());
		byte[] shaOutputs = sha256(outputsBuf.toByteArray());

		boolean anyoneCanPay = (hashType & 0x80) != 0;
		int outputType = hashType & 0x03;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0x00);
		out.write(hashType);
		write(out, le32(tx.getVersion()));
		write(out, le32(tx.getLockTime()));
		if (!anyoneCanPay) {
			write(out, shaPrevouts);
			write(out, shaAmounts);
			write(out, shaScriptPubKeys);
			write(out, shaSequences);
		}
		if (outputType == 0x00) {
			write(out, shaOutputs);
		}
		out.write(0x00);
		if (anyoneCanPay) {
			TransactionInput inp = inputs.get(inputIndex);
			write(out, inp.getOutpoint().getHash().getReversedBytes());
			write(out, le32(inp.getOutpoint().getIndex()));
			write(out, le64(prevouts.get(inputIndex).amountSat));
			write(out, varInt(prevouts.get(inputIndex).scriptPubKey.length));
			write(out, prevouts.get(inputIndex).scriptPubKey);
			write(out, le32(inp.getSequenceNumber()));
		} else {
			write(out, le32(inputIndex));
		}
		if (outputType == 0x03) {
			TransactionOutput o = outputs.get(inputIndex);
			write(out, sha256(concat(le64(o.getValue().value),
					varInt(o.getScriptBytes().length), o.getScriptBytes())));
		}
		return tagged("TapSighash", out.toByteArray());
	}

	private static void write(ByteArrayOutputStream b, byte[] data) {
		b.write(data, 0, data.length);
	}

	private static byte[] hex(String s) {
		byte[] out = new byte[s.length() / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	private static String toHex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) {
			sb.append(Character.forDigit((x >> 4) & 0xF, 16));
			sb.append(Character.forDigit(x & 0xF, 16));
		}
		return sb.toString();
	}

	public static boolean selfTest() {
		try {
			BigInteger d = new BigInteger(
					"B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF",
					16);
			byte[] msg = hex("243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89");
			byte[] aux = hex("0000000000000000000000000000000000000000000000000000000000000001");
			byte[] sig = schnorrSign(d, msg, aux);
			String expSig = "6896bd60eeae296db48a229ff71dfe071bde413e6d43f917dc8dcf8c78de3341"
					+ "8906d11ac976abccb20b091292bff4ea897efcb639ea871cfa95f6de339e4b0a";
			byte[] pub = hex("DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659");
			boolean signOk = toHex(sig).equals(expSig)
					&& schnorrVerify(pub, msg, sig);

			byte[] rawTx = hex("02000000097de20cbff686da83a54981d2b9bab3586f4ca7e48f57f5b5596"
					+ "3115f3b334e9c010000000000000000d7b7cab57b1393ace2d064f4d4a2cb8af6def6127"
					+ "3e127517d44759b6dafdd990000000000fffffffff8e1f583384333689228c5d28eac133"
					+ "66be082dc57441760d957275419a418420000000000fffffffff0689180aa63b30cb162a"
					+ "73c6d2a38b7eeda2a83ece74310fda0843ad604853b0100000000feffffffaa5202bdf6d"
					+ "8ccd2ee0f0202afbbb7461d9264a25e5bfd3c5a52ee1239e0ba6c0000000000feffffff95"
					+ "6149bdc66faa968eb2be2d2faa29718acbfe3941215893a2a3446d32acd0500000000000"
					+ "00000000e664b9773b88c09c32cb70a2a3e4da0ced63b7ba3b22f848531bbb1d5d5f4c940"
					+ "10000000000000000e9aa6b8e6c9de67619e6a3924ae25696bb7b694bb677a632a74ef7e"
					+ "adfd4eabf0000000000ffffffffa778eb6a263dc090464cd125c466b5a99667720b1c110"
					+ "468831d058aa1b82af10100000000ffffffff0200ca9a3b000000001976a91406afd46bc"
					+ "dfd22ef94ac122aa11f241244a37ecc88ac807840cb0000000020ac9a87f5594be208f85"
					+ "32db38cff670c450ed2fea8fcdefcc9a663f78bab962b0065cd1d");
			String us = "512053a1f6e454df1aa2776a2814a721372d6258050de330b3c6d10ee8f4e0dda343,420000000;"
					+ "5120147c9c57132f6e7ecddba9800bb0c4449251c92a1e60371ee77557b6620f3ea3,462000000;"
					+ "76a914751e76e8199196d454941c45d1b3a323f1433bd688ac,294000000;"
					+ "5120e4d810fd50586274face62b8a807eb9719cef49c04177cc6b76a9a4251d5450e,504000000;"
					+ "512091b64d5324723a985170e4dc5a0f84c041804f2cd12660fa5dec09fc21783605,630000000;"
					+ "00147dd65592d0ab2fe0d0257d571abf032cd9db93dc,378000000;"
					+ "512075169f4001aa68f15bbed28b218df1d0a62cbbcf1188c6665110c293c907b831,672000000;"
					+ "5120712447206d7a5238acc7ff53fbe94a3b64539ad291c7cdbc490b7577e4b17df5,546000000;"
					+ "512077e30a5522dd9f894c3f8b8bd4c4b2cf82ca7da8a3ea6a239655c39c050ab220,588000000";
			Transaction tx = new Transaction(BtcKeys.PARAMS, rawTx);
			java.util.List<Prevout> prevouts = new java.util.ArrayList<>();
			for (String part : us.split(";")) {
				String[] p = part.split(",");
				prevouts.add(new Prevout(hex(p[0]), Long.parseLong(p[1])));
			}
			boolean sighashOk = toHex(keyPathSigHash(tx, prevouts, 0, 3)).equals(
					"2514a6272f85cfa0f45eb907fcb0d121b808ed37c6ea160a5a9046ed5526d555");
			return signOk && sighashOk;
		} catch (Exception e) {
			return false;
		}
	}
}
