package com.professor.zerion.android.vault.wallet.btc;

import org.junit.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Sender and receiver agree on several outputs to one silent payment
 * address in one transaction: the sender derives distinct outputs for
 * k = 0, 1, 2 and the receiver, scanning the transaction's outputs in any
 * order among decoys, finds exactly those with distinct tweaks, both from
 * the input key sum and from the precomputed tweak point. Nothing is found
 * with the wrong scan key, and a receiver that skips a k finds nothing past
 * the gap. The implementation has no label support, so label vectors do not
 * apply to it.
 */
public class SilentPaymentMultiOutputTest {

	private static final BigInteger N = new BigInteger(
			"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
	private static final int ROUNDS = 12;

	private final Random random = new Random(47);

	@Test
	public void senderOutputsAreFoundByTheReceiverInAnyOrderAmongDecoys() {
		for (int round = 0; round < ROUNDS; round++) {
			int count = 1 + random.nextInt(4);
			List<byte[]> inputPrivs = Arrays.asList(scalar(), scalar());
			List<byte[]> outpoints = Arrays.asList(bytes(36), bytes(36));
			byte[] scanPriv = scalar();
			byte[] spendPriv = scalar();
			byte[] scanPub = SilentPayment.pubFromPriv(scanPriv);
			byte[] spendPub = SilentPayment.pubFromPriv(spendPriv);

			List<byte[]> outputs = SilentPayment.deriveOutputs(inputPrivs,
					outpoints, scanPub, spendPub, count);
			assertEquals(count, outputs.size());
			assertEquals(count, distinct(outputs).size());

			List<byte[]> txOutputs = new ArrayList<>(outputs);
			txOutputs.add(bytes(32));
			txOutputs.add(bytes(32));
			Collections.shuffle(txOutputs, random);

			byte[] inputPubSum = SilentPayment.pubFromPriv(sum(inputPrivs));
			List<SilentPayment.Detected> found = SilentPayment.scan(inputPubSum,
					outpoints, scanPriv, spendPub, txOutputs);
			assertEquals(count, found.size());
			Set<String> foundOutputs = new HashSet<>();
			Set<String> tweaks = new HashSet<>();
			for (SilentPayment.Detected d : found) {
				foundOutputs.add(hex(d.outputXOnly));
				tweaks.add(hex(d.tweak));
			}
			assertEquals(distinct(outputs), foundOutputs);
			assertEquals(count, tweaks.size());

			byte[] tweakPoint = SilentPayment.tweakPoint(inputPubSum, outpoints);
			assertEquals(count, SilentPayment.scanWithTweak(tweakPoint, scanPriv,
					spendPub, txOutputs).size());

			assertTrue(SilentPayment.scan(inputPubSum, outpoints, scalar(),
					spendPub, txOutputs).isEmpty());

			List<byte[]> withoutFirst = new ArrayList<>(txOutputs);
			withoutFirst.remove(outputs.get(0));
			assertTrue(SilentPayment.scan(inputPubSum, outpoints, scanPriv,
					spendPub, withoutFirst).isEmpty());
		}
	}

	private byte[] scalar() {
		while (true) {
			BigInteger k = new BigInteger(1, bytes(32)).mod(N);
			if (k.signum() > 0) return to32(k);
		}
	}

	private static byte[] sum(List<byte[]> scalars) {
		BigInteger s = BigInteger.ZERO;
		for (byte[] k : scalars) s = s.add(new BigInteger(1, k)).mod(N);
		return to32(s);
	}

	private static byte[] to32(BigInteger k) {
		byte[] raw = k.toByteArray();
		byte[] out = new byte[32];
		int copy = Math.min(32, raw.length);
		System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
		return out;
	}

	private byte[] bytes(int n) {
		byte[] b = new byte[n];
		random.nextBytes(b);
		return b;
	}

	private static Set<String> distinct(List<byte[]> keys) {
		Set<String> s = new HashSet<>();
		for (byte[] k : keys) s.add(hex(k));
		return s;
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder();
		for (byte x : b) sb.append(String.format("%02x", x));
		return sb.toString();
	}
}
