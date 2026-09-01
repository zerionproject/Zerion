package com.professor.zerion.android.vault.wallet.btc;

import org.bitcoinj.core.ECKey;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class SilentPayment {

	private static final ECDomainParameters CURVE = ECKey.CURVE;
	private static final BigInteger N = CURVE.getN();
	private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
	private static final int BECH32M = 0x2bc830a3;

	private SilentPayment() {
	}

	private static ECPoint decodePoint(byte[] bytes) {
		return CURVE.getCurve().decodePoint(bytes);
	}

	private static byte[] serP(ECPoint p) {
		return p.getEncoded(true);
	}

	private static byte[] xOnly(ECPoint p) {
		return p.normalize().getAffineXCoord().getEncoded();
	}

	private static ECPoint mulG(BigInteger k) {
		return CURVE.getG().multiply(k).normalize();
	}

	private static byte[] taggedHash(String tag, byte[] msg) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			byte[] t = sha.digest(tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
			sha.reset();
			sha.update(t);
			sha.update(t);
			sha.update(msg);
			return sha.digest();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static byte[] ser32(int k) {
		return new byte[]{(byte) (k >>> 24), (byte) (k >>> 16),
				(byte) (k >>> 8), (byte) k};
	}

	private static int unsignedCompare(byte[] a, byte[] b) {
		int n = Math.min(a.length, b.length);
		for (int i = 0; i < n; i++) {
			int d = (a[i] & 0xff) - (b[i] & 0xff);
			if (d != 0) {
				return d;
			}
		}
		return a.length - b.length;
	}

	private static byte[] smallest(List<byte[]> outpoints) {
		byte[] s = outpoints.get(0);
		for (byte[] o : outpoints) {
			if (unsignedCompare(o, s) < 0) {
				s = o;
			}
		}
		return s;
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] out = new byte[a.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	private static byte[] to32(BigInteger v) {
		byte[] b = v.toByteArray();
		if (b.length == 32) {
			return b;
		}
		if (b.length == 33 && b[0] == 0) {
			byte[] out = new byte[32];
			System.arraycopy(b, 1, out, 0, 32);
			return out;
		}
		byte[] out = new byte[32];
		if (b.length < 32) {
			System.arraycopy(b, 0, out, 32 - b.length, b.length);
		} else {
			System.arraycopy(b, b.length - 32, out, 0, 32);
		}
		return out;
	}

	public static byte[] pubFromPriv(byte[] priv) {
		return serP(mulG(new BigInteger(1, priv).mod(N)));
	}

	public static List<byte[]> deriveOutputs(List<byte[]> inputPrivKeys,
			List<byte[]> outpoints, byte[] scanPub, byte[] spendPub, int count) {
		if (inputPrivKeys.isEmpty() || outpoints.size() != inputPrivKeys.size()) {
			throw new IllegalArgumentException("bad inputs");
		}
		BigInteger a = BigInteger.ZERO;
		for (byte[] pk : inputPrivKeys) {
			a = a.add(new BigInteger(1, pk)).mod(N);
		}
		if (a.signum() == 0) {
			throw new IllegalArgumentException("input key sum is zero");
		}
		ECPoint aG = mulG(a);
		byte[] inputHashBytes = taggedHash("BIP0352/Inputs",
				concat(smallest(outpoints), serP(aG)));
		BigInteger inputHash = new BigInteger(1, inputHashBytes).mod(N);
		ECPoint bScan = decodePoint(scanPub);
		ECPoint bSpend = decodePoint(spendPub);
		ECPoint ecdh = bScan.multiply(inputHash.multiply(a).mod(N)).normalize();
		byte[] ecdhSer = serP(ecdh);
		List<byte[]> out = new ArrayList<>(count);
		for (int k = 0; k < count; k++) {
			BigInteger tk = new BigInteger(1, taggedHash("BIP0352/SharedSecret",
					concat(ecdhSer, ser32(k)))).mod(N);
			if (tk.signum() == 0 || tk.compareTo(N) >= 0) {
				throw new IllegalStateException("bad tweak");
			}
			out.add(xOnly(bSpend.add(mulG(tk)).normalize()));
		}
		return out;
	}

	public static final class Detected {
		public final byte[] outputXOnly;
		public final byte[] tweak;

		Detected(byte[] outputXOnly, byte[] tweak) {
			this.outputXOnly = outputXOnly;
			this.tweak = tweak;
		}
	}

	public static List<Detected> scan(byte[] inputPubKeySum,
			List<byte[]> outpoints, byte[] scanPriv, byte[] spendPub,
			List<byte[]> txOutputs) {
		ECPoint a = decodePoint(inputPubKeySum);
		BigInteger inputHash = new BigInteger(1, taggedHash("BIP0352/Inputs",
				concat(smallest(outpoints), serP(a)))).mod(N);
		BigInteger bScan = new BigInteger(1, scanPriv).mod(N);
		ECPoint ecdh = a.multiply(inputHash.multiply(bScan).mod(N)).normalize();
		return matchOutputs(serP(ecdh), spendPub, txOutputs);
	}

	public static List<Detected> scanWithTweak(byte[] tweakPoint,
			byte[] scanPriv, byte[] spendPub, List<byte[]> txOutputs) {
		ECPoint ecdh = decodePoint(tweakPoint)
				.multiply(new BigInteger(1, scanPriv).mod(N)).normalize();
		return matchOutputs(serP(ecdh), spendPub, txOutputs);
	}

	private static List<Detected> matchOutputs(byte[] ecdhSer, byte[] spendPub,
			List<byte[]> txOutputs) {
		ECPoint bSpend = decodePoint(spendPub);
		List<byte[]> remaining = new ArrayList<>(txOutputs);
		List<Detected> found = new ArrayList<>();
		int k = 0;
		while (true) {
			BigInteger tk = new BigInteger(1, taggedHash("BIP0352/SharedSecret",
					concat(ecdhSer, ser32(k)))).mod(N);
			byte[] xk = xOnly(bSpend.add(mulG(tk)).normalize());
			int idx = -1;
			for (int i = 0; i < remaining.size(); i++) {
				if (java.util.Arrays.equals(remaining.get(i), xk)) {
					idx = i;
					break;
				}
			}
			if (idx < 0) {
				break;
			}
			found.add(new Detected(xk, to32(tk)));
			remaining.remove(idx);
			k++;
		}
		return found;
	}

	public static byte[] tweakPoint(byte[] inputPubKeySum,
			List<byte[]> outpoints) {
		ECPoint a = decodePoint(inputPubKeySum);
		BigInteger inputHash = new BigInteger(1, taggedHash("BIP0352/Inputs",
				concat(smallest(outpoints), serP(a)))).mod(N);
		return serP(a.multiply(inputHash).normalize());
	}

	public static boolean isSilentAddress(String s) {
		String a = s.trim().toLowerCase(java.util.Locale.US);
		return a.startsWith("sp1") || a.startsWith("tsp1");
	}

	public static final class Address {
		public final boolean mainnet;
		public final byte[] scanPub;
		public final byte[] spendPub;

		Address(boolean mainnet, byte[] scanPub, byte[] spendPub) {
			this.mainnet = mainnet;
			this.scanPub = scanPub;
			this.spendPub = spendPub;
		}
	}

	public static String encodeAddress(byte[] scanPub, byte[] spendPub,
			boolean mainnet) {
		if (scanPub.length != 33 || spendPub.length != 33) {
			throw new IllegalArgumentException("bad key length");
		}
		String hrp = mainnet ? "sp" : "tsp";
		int[] prog = convertBits(toInts(concat(scanPub, spendPub)), 8, 5, true);
		int[] data = new int[prog.length + 1];
		data[0] = 0;
		System.arraycopy(prog, 0, data, 1, prog.length);
		return bech32mEncode(hrp, data);
	}

	@javax.annotation.Nullable
	public static Address decodeAddress(String addr) {
		String a = addr.trim();
		String lower = a.toLowerCase(java.util.Locale.US);
		if (!(lower.startsWith("sp1") || lower.startsWith("tsp1"))) {
			return null;
		}
		Object[] dec = bech32mDecode(lower);
		if (dec == null) {
			return null;
		}
		String hrp = (String) dec[0];
		int[] data = (int[]) dec[1];
		if (data.length == 0 || data[0] != 0) {
			return null;
		}
		int[] rest = new int[data.length - 1];
		System.arraycopy(data, 1, rest, 0, rest.length);
		int[] payload = convertBits(rest, 5, 8, false);
		if (payload == null || payload.length != 66) {
			return null;
		}
		byte[] bytes = new byte[66];
		for (int i = 0; i < 66; i++) {
			bytes[i] = (byte) payload[i];
		}
		byte[] scan = new byte[33];
		byte[] spend = new byte[33];
		System.arraycopy(bytes, 0, scan, 0, 33);
		System.arraycopy(bytes, 33, spend, 0, 33);
		return new Address("sp".equals(hrp), scan, spend);
	}

	private static int[] toInts(byte[] b) {
		int[] out = new int[b.length];
		for (int i = 0; i < b.length; i++) {
			out[i] = b[i] & 0xff;
		}
		return out;
	}

	private static int polymod(int[] values) {
		int[] gen = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
		int chk = 1;
		for (int v : values) {
			int b = chk >>> 25;
			chk = ((chk & 0x1ffffff) << 5) ^ v;
			for (int i = 0; i < 5; i++) {
				if (((b >>> i) & 1) != 0) {
					chk ^= gen[i];
				}
			}
		}
		return chk;
	}

	private static int[] hrpExpand(String hrp) {
		int[] out = new int[hrp.length() * 2 + 1];
		for (int i = 0; i < hrp.length(); i++) {
			out[i] = hrp.charAt(i) >>> 5;
		}
		for (int i = 0; i < hrp.length(); i++) {
			out[hrp.length() + 1 + i] = hrp.charAt(i) & 31;
		}
		return out;
	}

	private static String bech32mEncode(String hrp, int[] data) {
		int[] exp = hrpExpand(hrp);
		int[] values = new int[exp.length + data.length];
		System.arraycopy(exp, 0, values, 0, exp.length);
		System.arraycopy(data, 0, values, exp.length, data.length);
		int[] withPad = new int[values.length + 6];
		System.arraycopy(values, 0, withPad, 0, values.length);
		int poly = polymod(withPad) ^ BECH32M;
		StringBuilder sb = new StringBuilder(hrp).append('1');
		for (int d : data) {
			sb.append(CHARSET.charAt(d));
		}
		for (int i = 0; i < 6; i++) {
			sb.append(CHARSET.charAt((poly >>> (5 * (5 - i))) & 31));
		}
		return sb.toString();
	}

	@javax.annotation.Nullable
	private static Object[] bech32mDecode(String s) {
		int pos = s.lastIndexOf('1');
		if (pos < 1 || pos + 7 > s.length()) {
			return null;
		}
		String hrp = s.substring(0, pos);
		int[] data = new int[s.length() - pos - 1];
		for (int i = 0; i < data.length; i++) {
			int c = CHARSET.indexOf(s.charAt(pos + 1 + i));
			if (c < 0) {
				return null;
			}
			data[i] = c;
		}
		int[] exp = hrpExpand(hrp);
		int[] all = new int[exp.length + data.length];
		System.arraycopy(exp, 0, all, 0, exp.length);
		System.arraycopy(data, 0, all, exp.length, data.length);
		if (polymod(all) != BECH32M) {
			return null;
		}
		int[] payload = new int[data.length - 6];
		System.arraycopy(data, 0, payload, 0, payload.length);
		return new Object[]{hrp, payload};
	}

	@javax.annotation.Nullable
	private static int[] convertBits(int[] data, int from, int to, boolean pad) {
		int acc = 0;
		int bits = 0;
		List<Integer> out = new ArrayList<>();
		int maxv = (1 << to) - 1;
		for (int value : data) {
			if (value < 0 || (value >>> from) != 0) {
				return null;
			}
			acc = (acc << from) | value;
			bits += from;
			while (bits >= to) {
				bits -= to;
				out.add((acc >>> bits) & maxv);
			}
		}
		if (pad) {
			if (bits > 0) {
				out.add((acc << (to - bits)) & maxv);
			}
		} else if (bits >= from || ((acc << (to - bits)) & maxv) != 0) {
			return null;
		}
		int[] r = new int[out.size()];
		for (int i = 0; i < r.length; i++) {
			r[i] = out.get(i);
		}
		return r;
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

	private static byte[] reversed(byte[] b) {
		byte[] out = new byte[b.length];
		for (int i = 0; i < b.length; i++) {
			out[i] = b[b.length - 1 - i];
		}
		return out;
	}

	public static boolean selfTest() {
		try {
			List<byte[]> privs = java.util.Arrays.asList(
					hex("eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1"),
					hex("93f5ed907ad5b2bdbbdcb5d9116ebc0a4e1f92f910d5260237fa45a9408aad16"));
			List<byte[]> outpoints = java.util.Arrays.asList(
					concat(reversed(hex("f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16")),
							new byte[]{0, 0, 0, 0}),
					concat(reversed(hex("a1075db55d416d3ca199f55b6084e2115b9345e16c5cf302fc80e9d5fbf5d48d")),
							new byte[]{0, 0, 0, 0}));
			byte[] scan = hex("0220bcfac5b99e04ad1a06ddfb016ee13582609d60b6291e98d01a9bc9a16c96d4");
			byte[] spend = hex("025cc9856d6f8375350e123978daac200c260cb5b5ae83106cab90484dcd8fcf36");
			List<byte[]> out = deriveOutputs(privs, outpoints, scan, spend, 1);
			String expected = "3e9fce73d4e77a4809908e3c3a2e54ee147b9312dc5044a193d1fc85de46e3c1";
			String addr = "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuex"
					+ "zk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv";
			boolean derivedOk = out.size() == 1 && toHex(out.get(0)).equals(expected);
			boolean codecOk = encodeAddress(scan, spend, true).equals(addr);
			Address rt = decodeAddress(addr);
			boolean roundTrip = rt != null
					&& toHex(rt.scanPub).equals(toHex(scan))
					&& toHex(rt.spendPub).equals(toHex(spend));
			byte[] scanPriv = hex("0f694e068028a717f8af6b9411f9a133dd3565258714cc226594b34db90c1f2c");
			byte[] spendPriv = hex("9d6ad855ce3417ef84e836892e5a56392bfba05fa5d97ccea30e266f540e08b3");
			byte[] inputSum = hex("032562c1ab2d6bd45d7ca4d78f569999e5333dffd3ac5263924fd00d00dedc4bee");
			List<Detected> detected = scan(inputSum, outpoints, scanPriv,
					pubFromPriv(spendPriv), java.util.Arrays.asList(hex(expected)));
			boolean recvOk = detected.size() == 1
					&& toHex(detected.get(0).outputXOnly).equals(expected)
					&& toHex(detected.get(0).tweak).equals(
							"f438b40179a3c4262de12986c0e6cce0634007cdc79c1dcd3e20b9ebc2e7eef6");
			byte[] tw = tweakPoint(inputSum, outpoints);
			List<Detected> detected2 = scanWithTweak(tw, scanPriv,
					pubFromPriv(spendPriv), java.util.Arrays.asList(hex(expected)));
			boolean tweakScanOk = detected2.size() == 1
					&& toHex(detected2.get(0).tweak).equals(toHex(detected.get(0).tweak));
			return derivedOk && codecOk && roundTrip && recvOk && tweakScanOk;
		} catch (Exception e) {
			return false;
		}
	}
}
