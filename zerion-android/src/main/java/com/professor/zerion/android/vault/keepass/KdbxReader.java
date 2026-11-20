package com.professor.zerion.android.vault.keepass;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

import javax.annotation.Nullable;

@NotNullByDefault
public final class KdbxReader {

	public static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
	public static final int MAX_KEY_FILE_BYTES = 16 * 1024 * 1024;
	static final int MAX_BLOCK_BYTES = 16 * 1024 * 1024;
	static final long MAX_PAYLOAD_BYTES = 256L * 1024 * 1024;
	static final int MAX_INNER_BINARIES = 10_000;

	private static final int INNER_END = 0;
	private static final int INNER_STREAM_ID = 1;
	private static final int INNER_STREAM_KEY = 2;
	private static final int INNER_BINARY = 3;

	public static final class Document {
		public final List<KdbxEntry> entries;
		public final int historyEntries;
		public final int skippedEntries;

		Document(List<KdbxEntry> entries, int historyEntries,
				int skippedEntries) {
			this.entries = entries;
			this.historyEntries = historyEntries;
			this.skippedEntries = skippedEntries;
		}
	}

	private KdbxReader() {
	}

	public static byte[] readLimited(InputStream in, long declaredSize,
			int cap) throws KdbxException, IOException {
		try {
			return KdbxStreams.readAll(in, declaredSize, cap);
		} catch (KdbxStreams.KdbxIOException e) {
			throw e.reason;
		} catch (OutOfMemoryError e) {
			throw KdbxException.tooLarge("memory");
		}
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
		if (!KdbxBytes.constantTimeEquals(KdbxBytes.sha256(rawHeader),
				storedHash)) {
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
		try {
			byte[] headerHmacKey = KdbxBytes.sha512(KdbxBytes.u64le(-1L),
					hmacBase);
			byte[] headerHmac = KdbxBytes.hmacSha256(headerHmacKey, rawHeader);
			KdbxBytes.wipe(headerHmacKey);
			byte[] storedHmac = Arrays.copyOfRange(file, pos + 32, pos + 64);
			if (!KdbxBytes.constantTimeEquals(headerHmac, storedHmac)) {
				throw KdbxException.wrongKey("header hmac");
			}
			InputStream blocks = new KdbxStreams.HmacBlockInputStream(file,
					pos + 64, hmacBase);
			try (InputStream plain = payload(header, masterKey, blocks)) {
				return parseInnerHeaderAndXml(plain);
			} catch (IOException e) {
				throw KdbxStreams.unwrap(e, KdbxException.Reason.CORRUPTED,
						KdbxException.Reason.CORRUPTED);
			}
		} finally {
			KdbxBytes.wipe(masterKey);
			KdbxBytes.wipe(hmacBase);
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
		try (InputStream decrypted = KdbxStreams.decrypting(header, masterKey,
				KdbxStreams.bytes(file, pos))) {
			byte[] start;
			try {
				start = KdbxStreams.readFully(decrypted, 32);
			} catch (IOException e) {
				throw KdbxException.wrongKey("stream start");
			}
			if (!KdbxBytes.constantTimeEquals(start, streamStartBytes)) {
				throw KdbxException.wrongKey("stream start");
			}
			InputStream blocks = new KdbxStreams.HashedBlockInputStream(decrypted);
			InputStream xml = header.gzip ? gunzip(blocks) : blocks;
			KdbxInnerStream inner = KdbxInnerStream.create(
					header.innerRandomStreamId, protectedStreamKey);
			return parseXml(xml, inner, KdbxBytes.sha256(header.rawBytes(file)));
		} catch (IOException e) {
			throw KdbxStreams.unwrap(e, KdbxException.Reason.WRONG_KEY,
					KdbxException.Reason.CORRUPTED);
		} finally {
			KdbxBytes.wipe(masterKey);
		}
	}

	private static InputStream payload(KdbxHeader header, byte[] masterKey,
			InputStream blocks) throws IOException {
		InputStream decrypted = KdbxStreams.decrypting(header, masterKey, blocks);
		return header.gzip ? gunzip(decrypted) : decrypted;
	}

	private static InputStream gunzip(InputStream compressed) throws IOException {
		return new KdbxStreams.LimitedInputStream(
				new GZIPInputStream(compressed, 64 * 1024), MAX_PAYLOAD_BYTES);
	}

	private static Document parseInnerHeaderAndXml(InputStream in)
			throws KdbxException, IOException {
		int streamId = -1;
		byte[] streamKey = null;
		int binaries = 0;
		while (true) {
			byte[] head = KdbxStreams.readFully(in, 5);
			int id = head[0] & 0xFF;
			int len = KdbxBytes.i32(head, 1);
			if (len < 0) throw KdbxException.corrupted("inner field size");
			if (id == INNER_END) {
				KdbxStreams.skipFully(in, len);
				break;
			}
			switch (id) {
				case INNER_STREAM_ID: {
					if (streamId >= 0 || len != 4) {
						throw KdbxException.corrupted("inner stream id");
					}
					byte[] value = KdbxStreams.readFully(in, 4);
					streamId = (int) KdbxBytes.u32(value, 0);
					KdbxInnerStream.requireSupported(streamId);
					break;
				}
				case INNER_STREAM_KEY:
					if (streamKey != null || len < 16 || len > 64) {
						throw KdbxException.corrupted("inner stream key");
					}
					streamKey = KdbxStreams.readFully(in, len);
					break;
				case INNER_BINARY:
					if (len < 1) throw KdbxException.corrupted("inner binary");
					if (++binaries > MAX_INNER_BINARIES) {
						throw KdbxException.tooLarge("binaries");
					}
					KdbxStreams.skipFully(in, len);
					break;
				default:
					throw KdbxException.unsupported("inner field " + id);
			}
		}
		if (streamId < 0 || streamKey == null) {
			throw KdbxException.corrupted("inner header incomplete");
		}
		KdbxInnerStream inner = KdbxInnerStream.create(streamId, streamKey);
		KdbxBytes.wipe(streamKey);
		return parseXml(in, inner, null);
	}

	static Document parseXml(InputStream xml, KdbxInnerStream inner,
			@Nullable byte[] expectedHeaderHash)
			throws KdbxException, IOException {
		KdbxDocumentHandler handler = new KdbxDocumentHandler(inner);
		KdbxXml.parse(new KdbxStreams.DeclarationScanInputStream(xml), handler);
		String storedHash = handler.headerHash();
		if (expectedHeaderHash != null && storedHash != null) {
			checkHeaderHash(storedHash, expectedHeaderHash);
		}
		return new Document(handler.entries(), handler.historyEntries(),
				handler.skippedEntries());
	}

	static Document parseXml(byte[] xml, KdbxInnerStream inner)
			throws KdbxException {
		try {
			return parseXml(KdbxStreams.bytes(xml, 0), inner, null);
		} catch (IOException e) {
			throw KdbxStreams.unwrap(e, KdbxException.Reason.CORRUPTED,
					KdbxException.Reason.CORRUPTED);
		} finally {
			KdbxBytes.wipe(xml);
		}
	}

	static void checkHeaderHash(String storedBase64, byte[] expected)
			throws KdbxException {
		byte[] stored;
		try {
			stored = java.util.Base64.getDecoder().decode(storedBase64.trim());
		} catch (IllegalArgumentException e) {
			throw KdbxException.corrupted("header hash text");
		}
		if (!KdbxBytes.constantTimeEquals(stored, expected)) {
			throw KdbxException.corrupted("header hash");
		}
	}
}
