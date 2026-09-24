package com.professor.zerion.android.vault.wallet.btc;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.core.Utils;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Every signature the builder produces verifies against a BIP143 digest
 * computed here from the serialized transaction alone, without the library's
 * own sighash code: version, hashPrevouts, hashSequence, the outpoint, the
 * P2WPKH script code, the amount, the sequence, hashOutputs, the lock time
 * and SIGHASH_ALL. The witness also carries the signing key's compressed
 * public key, whose hash is the one in the script code.
 */
public class BtcTxBip143DigestTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final int SIGHASH_ALL = 1;

	@Test
	public void witnessSignaturesVerifyAgainstAnIndependentBip143Digest()
			throws Exception {
		ECKey k0 = TestKeys.receiveKey(MNEMONIC, 0, 0);
		ECKey k1 = TestKeys.receiveKey(MNEMONIC, 0, 1);
		List<BtcTx.Input> inputs = Arrays.asList(
				new BtcTx.Input("00000000000000000000000000000000000000000000000000000000000000aa",
						1, 70_000L, k0),
				new BtcTx.Input("00000000000000000000000000000000000000000000000000000000000000bb",
						0, 55_000L, k1));
		List<BtcTx.Output> outputs = Arrays.asList(
				new BtcTx.Output(TestKeys.address(MNEMONIC, 0, 5), 100_000L),
				new BtcTx.Output(TestKeys.changeAddress(MNEMONIC, 0, 0), 20_000L));
		Transaction tx = new Transaction(BtcKeys.PARAMS,
				Utils.HEX.decode(BtcTx.buildAndSign(inputs, outputs)));
		assertEquals(2, tx.getInputs().size());

		for (int i = 0; i < 2; i++) {
			TransactionInput in = tx.getInput(i);
			TransactionWitness witness = in.getWitness();
			assertEquals(2, witness.getPushCount());
			byte[] sigWithType = witness.getPush(0);
			byte[] pub = witness.getPush(1);
			assertEquals(33, pub.length);
			assertEquals(SIGHASH_ALL, sigWithType[sigWithType.length - 1]);
			byte[] der = Arrays.copyOf(sigWithType, sigWithType.length - 1);

			BtcTx.Input signed = inputFor(inputs, in);
			assertArrayEquals(signed.key.getPubKey(), pub);
			byte[] scriptCode = scriptCode(Utils.sha256hash160(pub));
			byte[] digest = bip143Digest(tx, i, scriptCode, signed.valueSat);
			assertTrue("input " + i, ECKey.verify(digest, der, pub));
			byte[] wrongAmount = bip143Digest(tx, i, scriptCode,
					signed.valueSat + 1);
			assertTrue(!ECKey.verify(wrongAmount, der, pub));
		}
	}

	private static BtcTx.Input inputFor(List<BtcTx.Input> inputs,
			TransactionInput in) {
		String hash = in.getOutpoint().getHash().toString();
		long index = in.getOutpoint().getIndex();
		for (BtcTx.Input candidate : inputs) {
			if (candidate.txHash.equals(hash) && candidate.txPos == index) {
				return candidate;
			}
		}
		throw new AssertionError("unknown input " + hash + ":" + index);
	}

	/** {@code 1976a914 <20-byte key hash> 88ac}, length prefix included. */
	private static byte[] scriptCode(byte[] keyHash) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		b.write(0x19);
		b.write(0x76);
		b.write(0xa9);
		b.write(0x14);
		b.write(keyHash, 0, 20);
		b.write(0x88);
		b.write(0xac);
		return b.toByteArray();
	}

	private static byte[] bip143Digest(Transaction tx, int index,
			byte[] scriptCode, long amount) {
		ByteArrayOutputStream prevouts = new ByteArrayOutputStream();
		ByteArrayOutputStream sequences = new ByteArrayOutputStream();
		for (TransactionInput in : tx.getInputs()) {
			write(prevouts, in.getOutpoint().getHash().getReversedBytes());
			uint32(prevouts, in.getOutpoint().getIndex());
			uint32(sequences, in.getSequenceNumber());
		}
		ByteArrayOutputStream outs = new ByteArrayOutputStream();
		for (TransactionOutput o : tx.getOutputs()) {
			uint64(outs, o.getValue().value);
			byte[] script = o.getScriptBytes();
			outs.write(script.length);
			write(outs, script);
		}
		TransactionInput in = tx.getInput(index);
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		uint32(b, tx.getVersion());
		write(b, Sha256Hash.hashTwice(prevouts.toByteArray()));
		write(b, Sha256Hash.hashTwice(sequences.toByteArray()));
		write(b, in.getOutpoint().getHash().getReversedBytes());
		uint32(b, in.getOutpoint().getIndex());
		write(b, scriptCode);
		uint64(b, amount);
		uint32(b, in.getSequenceNumber());
		write(b, Sha256Hash.hashTwice(outs.toByteArray()));
		uint32(b, tx.getLockTime());
		uint32(b, SIGHASH_ALL);
		return Sha256Hash.hashTwice(b.toByteArray());
	}

	private static void write(ByteArrayOutputStream b, byte[] bytes) {
		b.write(bytes, 0, bytes.length);
	}

	private static void uint32(ByteArrayOutputStream b, long v) {
		for (int i = 0; i < 4; i++) b.write((int) (v >>> (8 * i)) & 0xFF);
	}

	private static void uint64(ByteArrayOutputStream b, long v) {
		for (int i = 0; i < 8; i++) b.write((int) (v >>> (8 * i)) & 0xFF);
	}
}
