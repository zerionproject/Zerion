package com.professor.zerion.android.vault.keepass;

import org.briarproject.nullsafety.NotNullByDefault;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.engines.TwofishEngine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@NotNullByDefault
public final class KdbxReader {

	public static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
	public static final int MAX_KEY_FILE_BYTES = 16 * 1024 * 1024;
	static final int MAX_BLOCK_BYTES = 16 * 1024 * 1024;
	static final int MAX_PAYLOAD_BYTES = 256 * 1024 * 1024;
	static final int MAX_INNER_BINARIES = 10_000;

	private static final int INNER_END = 0;
	private static final int INNER_STREAM_ID = 1;
	private static final int INNER_STREAM_KEY = 2;
	private static final int INNER_BINARY = 3;

	public static final class Document {
		public final List<KdbxEntry> entries;
		public final int historyEntries;

		Document(List<KdbxEntry> entries, int historyEntries) {
			this.entries = entries;
			this.historyEntries = historyEntries;
		}
	}

	private static final class Buffer extends ByteArrayOutputStream {

		Buffer(int size) {
			super(size);
		}

		void wipe() {
			Arrays.fill(buf, (byte) 0);
			count = 0;
		}
	}

	private KdbxReader() {
	}

	public static Document read(byte[] file, @Nullable char[] password,
			@Nullable byte[] keyFile) throws KdbxException {
		if (file.length > MAX_FILE_BYTES) throw KdbxException.tooLarge("file");
		if (keyFile != null && keyFile.length > MAX_KEY_FILE_BYTES) {
			throw KdbxException.tooLarge("key file");
		}
		try {
			KdbxHeader header = KdbxHeader.parse(file);
			return header.isVersion4()
					? readVersion4(file, header, password, keyFile)
					: readVersion3(file, header, password, keyFile);
		} catch (OutOfMemoryError e) {
			throw KdbxException.tooLarge("memory");
		}
	}

	private static Document readVersion4(byte[] file, KdbxHeader header,
			@Nullable char[] password, @Nullable byte[] keyFile)
			throws KdbxException {
		int pos = header.headerLength;
		if (file.length < pos + 64) throw KdbxException.corrupted("file end");
		byte[] rawHeader = header.rawBytes(file);
		byte[] storedHash = Arrays.copyOfRange(file, pos, pos + 32);
		if (!KdbxBytes.constantTimeEquals(KdbxBytes.sha256(rawHeader), storedHash)) {
			throw KdbxException.corrupted("header hash");
		}
		KdbxKdf.Params kdf = header.kdf;
		if (kdf == null) throw KdbxException.corrupted("kdf");
		byte[] composite = KdbxKdf.compositeKey(password, keyFile);
		byte[] transformed;
		try {
			transformed = KdbxKdf.transform(composite, kdf);
		} finally {
			KdbxBytes.wipe(composite);
		}
		byte[] masterKey = KdbxBytes.sha256(header.masterSeed, transformed);
		byte[] hmacBase = KdbxBytes.sha512(header.masterSeed, transformed,
				new byte[] {1});
		KdbxBytes.wipe(transformed);
		byte[] plain = null;
		byte[] payload = null;
		try {
			byte[] headerHmacKey = KdbxBytes.sha512(KdbxBytes.u64le(-1L), hmacBase);
			byte[] headerHmac = KdbxBytes.hmacSha256(headerHmacKey, rawHeader);
			KdbxBytes.wipe(headerHmacKey);
			byte[] storedHmac = Arrays.copyOfRange(file, pos + 32, pos + 64);
			if (!KdbxBytes.constantTimeEquals(headerHmac, storedHmac)) {
				throw KdbxException.wrongKey("header hmac");
			}
			byte[] encrypted = readHmacBlocks(file, pos + 64, hmacBase);
			plain = decrypt(header, masterKey, encrypted, false);
			KdbxBytes.wipe(encrypted);
			payload = header.gzip ? gunzip(plain) : plain;
			return parseInnerHeaderAndXml(payload);
		} finally {
			KdbxBytes.wipe(masterKey);
			KdbxBytes.wipe(hmacBase);
			if (plain != null) KdbxBytes.wipe(plain);
			if (payload != null) KdbxBytes.wipe(payload);
		}
	}

	private static Document readVersion3(byte[] file, KdbxHeader header,
			@Nullable char[] password, @Nullable byte[] keyFile)
			throws KdbxException {
		byte[] transformSeed = header.transformSeed;
		byte[] streamStartBytes = header.streamStartBytes;
		byte[] protectedStreamKey = header.protectedStreamKey;
		if (transformSeed == null || streamStartBytes == null
				|| protectedStreamKey == null) {
			throw KdbxException.corrupted("header");
		}
		int pos = header.headerLength;
		if (file.length <= pos) throw KdbxException.corrupted("file end");
		byte[] composite = KdbxKdf.compositeKey(password, keyFile);
		byte[] transformed;
		try {
			transformed = KdbxKdf.transformAes(composite, transformSeed,
					header.transformRounds);
		} finally {
			KdbxBytes.wipe(composite);
		}
		byte[] masterKey = KdbxBytes.sha256(header.masterSeed, transformed);
		KdbxBytes.wipe(transformed);
		byte[] plain = null;
		byte[] blocks = null;
		byte[] payload = null;
		try {
			byte[] encrypted = Arrays.copyOfRange(file, pos, file.length);
			plain = decrypt(header, masterKey, encrypted, true);
			if (plain.length < 32) throw KdbxException.wrongKey("stream start");
			byte[] start = Arrays.copyOf(plain, 32);
			if (!KdbxBytes.constantTimeEquals(start, streamStartBytes)) {
				throw KdbxException.wrongKey("stream start");
			}
			blocks = readHashedBlocks(plain, 32);
			payload = header.gzip ? gunzip(blocks) : blocks;
			KdbxInnerStream inner = KdbxInnerStream.create(
					header.innerRandomStreamId, protectedStreamKey);
			return parseXml(payload, inner);
		} finally {
			KdbxBytes.wipe(masterKey);
			if (plain != null) KdbxBytes.wipe(plain);
			if (blocks != null) KdbxBytes.wipe(blocks);
			if (payload != null) KdbxBytes.wipe(payload);
		}
	}

	private static byte[] readHmacBlocks(byte[] file, int start, byte[] hmacBase)
			throws KdbxException {
		Buffer out = new Buffer(Math.max(16, file.length - start));
		int pos = start;
		long index = 0;
		while (true) {
			if (file.length - pos < 36) throw KdbxException.corrupted("block header");
			byte[] mac = Arrays.copyOfRange(file, pos, pos + 32);
			int len = KdbxBytes.i32(file, pos + 32);
			pos += 36;
			if (len < 0 || len > MAX_BLOCK_BYTES) {
				throw KdbxException.corrupted("block size");
			}
			if (len > file.length - pos) throw KdbxException.corrupted("block data");
			if (out.size() + (long) len > MAX_PAYLOAD_BYTES) {
				throw KdbxException.tooLarge("payload");
			}
			byte[] indexBytes = KdbxBytes.u64le(index);
			byte[] blockKey = KdbxBytes.sha512(indexBytes, hmacBase);
			byte[] data = Arrays.copyOfRange(file, pos, pos + len);
			byte[] expected = KdbxBytes.hmacSha256(blockKey, indexBytes,
					KdbxBytes.i32le(len), data);
			KdbxBytes.wipe(blockKey);
			if (!KdbxBytes.constantTimeEquals(expected, mac)) {
				out.wipe();
				throw KdbxException.corrupted("block hmac");
			}
			pos += len;
			if (len == 0) break;
			out.write(data, 0, len);
			KdbxBytes.wipe(data);
			index++;
		}
		if (pos != file.length) {
			out.wipe();
			throw KdbxException.corrupted("trailing data");
		}
		byte[] result = out.toByteArray();
		out.wipe();
		return result;
	}

	private static byte[] readHashedBlocks(byte[] plain, int start)
			throws KdbxException {
		Buffer out = new Buffer(Math.max(16, plain.length - start));
		int pos = start;
		long expectedIndex = 0;
		byte[] zeros = new byte[32];
		while (true) {
			if (plain.length - pos < 40) throw KdbxException.corrupted("block header");
			long index = KdbxBytes.u32(plain, pos);
			byte[] hash = Arrays.copyOfRange(plain, pos + 4, pos + 36);
			int len = KdbxBytes.i32(plain, pos + 36);
			pos += 40;
			if (index != expectedIndex) throw KdbxException.corrupted("block index");
			if (len < 0 || len > MAX_BLOCK_BYTES) {
				throw KdbxException.corrupted("block size");
			}
			if (len > plain.length - pos) throw KdbxException.corrupted("block data");
			if (out.size() + (long) len > MAX_PAYLOAD_BYTES) {
				throw KdbxException.tooLarge("payload");
			}
			if (len == 0) {
				if (!KdbxBytes.constantTimeEquals(hash, zeros)) {
					out.wipe();
					throw KdbxException.corrupted("final block");
				}
				break;
			}
			byte[] actual = KdbxBytes.sha256(Arrays.copyOfRange(plain, pos, pos + len));
			if (!KdbxBytes.constantTimeEquals(actual, hash)) {
				out.wipe();
				throw KdbxException.corrupted("block hash");
			}
			out.write(plain, pos, len);
			pos += len;
			expectedIndex++;
		}
		byte[] result = out.toByteArray();
		out.wipe();
		return result;
	}

	private static byte[] decrypt(KdbxHeader header, byte[] key, byte[] data,
			boolean keyCheck) throws KdbxException {
		KdbxException.Reason onFailure = keyCheck
				? KdbxException.Reason.WRONG_KEY : KdbxException.Reason.CORRUPTED;
		switch (header.cipher) {
			case CHACHA20: {
				ChaCha7539Engine engine = new ChaCha7539Engine();
				engine.init(false, new ParametersWithIV(new KeyParameter(key),
						header.encryptionIv));
				byte[] out = new byte[data.length];
				engine.processBytes(data, 0, data.length, out, 0);
				return out;
			}
			case AES_CBC: {
				if (data.length == 0 || data.length % 16 != 0) {
					throw new KdbxException(onFailure, "cipher text length");
				}
				try {
					Cipher aes = Cipher.getInstance("AES/CBC/NoPadding");
					aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
							new IvParameterSpec(header.encryptionIv));
					byte[] padded = aes.doFinal(data);
					return unpad(padded, onFailure);
				} catch (GeneralSecurityException e) {
					throw new KdbxException(onFailure, "aes");
				}
			}
			case TWOFISH_CBC: {
				if (data.length == 0 || data.length % 16 != 0) {
					throw new KdbxException(onFailure, "cipher text length");
				}
				BlockCipher cbc = CBCBlockCipher.newInstance(new TwofishEngine());
				cbc.init(false, new ParametersWithIV(new KeyParameter(key),
						header.encryptionIv));
				byte[] padded = new byte[data.length];
				for (int off = 0; off < data.length; off += 16) {
					cbc.processBlock(data, off, padded, off);
				}
				return unpad(padded, onFailure);
			}
			default:
				throw KdbxException.unsupported("cipher");
		}
	}

	private static byte[] unpad(byte[] padded, KdbxException.Reason onFailure)
			throws KdbxException {
		int pad = padded[padded.length - 1] & 0xFF;
		int bad = 0;
		if (pad < 1 || pad > 16 || pad > padded.length) bad = 1;
		int check = bad == 1 ? 1 : pad;
		for (int i = 0; i < 16; i++) {
			int idx = padded.length - 1 - i;
			int inRange = i < check ? 1 : 0;
			int differs = idx >= 0 && (padded[idx] & 0xFF) != pad ? 1 : 0;
			bad |= inRange & differs;
		}
		if (bad != 0) {
			KdbxBytes.wipe(padded);
			throw new KdbxException(onFailure, "padding");
		}
		byte[] out = Arrays.copyOf(padded, padded.length - pad);
		KdbxBytes.wipe(padded);
		return out;
	}

	private static byte[] gunzip(byte[] compressed) throws KdbxException {
		Buffer out = new Buffer(Math.min(MAX_PAYLOAD_BYTES,
				Math.max(1024, compressed.length * 4)));
		try (GZIPInputStream in = new GZIPInputStream(
				new ByteArrayInputStream(compressed), 64 * 1024)) {
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = in.read(buf)) != -1) {
				if (out.size() + (long) n > MAX_PAYLOAD_BYTES) {
					out.wipe();
					throw KdbxException.tooLarge("decompressed payload");
				}
				out.write(buf, 0, n);
			}
			KdbxBytes.wipe(buf);
		} catch (IOException e) {
			out.wipe();
			throw KdbxException.corrupted("gzip");
		}
		byte[] result = out.toByteArray();
		out.wipe();
		return result;
	}

	private static Document parseInnerHeaderAndXml(byte[] payload)
			throws KdbxException {
		int pos = 0;
		int streamId = -1;
		byte[] streamKey = null;
		int binaries = 0;
		while (true) {
			if (payload.length - pos < 5) throw KdbxException.corrupted("inner header");
			int id = payload[pos] & 0xFF;
			int len = KdbxBytes.i32(payload, pos + 1);
			pos += 5;
			if (len < 0 || len > payload.length - pos) {
				throw KdbxException.corrupted("inner field size");
			}
			if (id == INNER_END) {
				pos += len;
				break;
			}
			switch (id) {
				case INNER_STREAM_ID:
					if (streamId >= 0 || len != 4) {
						throw KdbxException.corrupted("inner stream id");
					}
					streamId = (int) KdbxBytes.u32(payload, pos);
					KdbxInnerStream.requireSupported(streamId);
					break;
				case INNER_STREAM_KEY:
					if (streamKey != null || len < 16 || len > 64) {
						throw KdbxException.corrupted("inner stream key");
					}
					streamKey = Arrays.copyOfRange(payload, pos, pos + len);
					break;
				case INNER_BINARY:
					if (len < 1) throw KdbxException.corrupted("inner binary");
					if (++binaries > MAX_INNER_BINARIES) {
						throw KdbxException.tooLarge("binaries");
					}
					break;
				default:
					throw KdbxException.unsupported("inner field " + id);
			}
			pos += len;
		}
		if (streamId < 0 || streamKey == null) {
			throw KdbxException.corrupted("inner header incomplete");
		}
		KdbxInnerStream inner = KdbxInnerStream.create(streamId, streamKey);
		KdbxBytes.wipe(streamKey);
		return parseXml(Arrays.copyOfRange(payload, pos, payload.length), inner);
	}

	static Document parseXml(byte[] xml, KdbxInnerStream inner)
			throws KdbxException {
		KdbxDocumentHandler handler = new KdbxDocumentHandler(inner);
		try {
			KdbxXml.parse(xml, handler);
		} finally {
			KdbxBytes.wipe(xml);
		}
		return new Document(handler.entries(), handler.historyEntries());
	}
}
