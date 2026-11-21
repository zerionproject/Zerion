package com.professor.zerion.android.vault.keepass;

import org.briarproject.nullsafety.NotNullByDefault;

import org.bouncycastle.crypto.BufferedBlockCipher;
import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.engines.TwofishEngine;
import org.bouncycastle.crypto.io.CipherInputStream;
import org.bouncycastle.crypto.io.InvalidCipherTextIOException;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import javax.annotation.Nullable;

@NotNullByDefault
final class KdbxStreams {

	private KdbxStreams() {
	}

	static final class KdbxIOException extends IOException {

		final KdbxException reason;

		KdbxIOException(KdbxException reason) {
			super(reason.getMessage());
			this.reason = reason;
		}
	}

	static KdbxException unwrap(IOException e, KdbxException.Reason onCipherText,
			KdbxException.Reason otherwise) {
		if (e instanceof KdbxIOException) return ((KdbxIOException) e).reason;
		if (e instanceof InvalidCipherTextIOException) {
			return new KdbxException(onCipherText, "cipher text");
		}
		return new KdbxException(otherwise, "stream " + e.getClass().getSimpleName()
				+ " " + e.getMessage());
	}

	static void readFully(InputStream in, byte[] buf, int off, int len)
			throws IOException {
		int done = 0;
		while (done < len) {
			int n = in.read(buf, off + done, len - done);
			if (n < 0) throw new EOFException(done + " of " + len);
			done += n;
		}
	}

	static byte[] readFully(InputStream in, int len) throws IOException {
		byte[] out = new byte[len];
		readFully(in, out, 0, len);
		return out;
	}

	static void skipFully(InputStream in, long len) throws IOException {
		byte[] sink = new byte[Math.min(64 * 1024, (int) Math.max(1, len))];
		long left = len;
		while (left > 0) {
			int n = in.read(sink, 0, (int) Math.min(sink.length, left));
			if (n < 0) throw new EOFException();
			left -= n;
		}
		Arrays.fill(sink, (byte) 0);
	}

	static void drain(InputStream in) throws IOException {
		byte[] sink = new byte[64 * 1024];
		while (in.read(sink) >= 0) {
		}
		Arrays.fill(sink, (byte) 0);
	}

	static final class WipingBuffer {

		private final int cap;
		private byte[] buf;
		private int count = 0;

		WipingBuffer(int initial, int cap) {
			this.cap = cap;
			this.buf = new byte[Math.min(initial, cap)];
		}

		int size() {
			return count;
		}

		void write(byte[] data, int off, int len) {
			int room = Math.min(len, cap - count);
			if (room <= 0) return;
			if (count + room > buf.length) {
				int grown = (int) Math.min(cap,
						Math.max((long) buf.length * 2, (long) count + room));
				byte[] bigger = new byte[grown];
				System.arraycopy(buf, 0, bigger, 0, count);
				Arrays.fill(buf, (byte) 0);
				buf = bigger;
			}
			System.arraycopy(data, off, buf, count, room);
			count += room;
		}

		byte[] toByteArray() {
			return Arrays.copyOf(buf, count);
		}

		void wipe() {
			Arrays.fill(buf, (byte) 0);
			count = 0;
		}
	}

	static byte[] readAll(InputStream in, long declaredSize, int cap)
			throws IOException {
		if (declaredSize > cap) {
			throw new KdbxIOException(KdbxException.tooLarge("declared size"));
		}
		int size = declaredSize > 0 ? (int) Math.min(cap, declaredSize + 1)
				: 64 * 1024;
		byte[] buf = new byte[size];
		int count = 0;
		try {
			while (true) {
				if (count == buf.length) {
					if (buf.length >= cap) {
						if (in.read() < 0) break;
						throw new KdbxIOException(KdbxException.tooLarge("read"));
					}
					int grown = (int) Math.min(cap, (long) buf.length * 2);
					byte[] bigger = new byte[grown];
					System.arraycopy(buf, 0, bigger, 0, count);
					Arrays.fill(buf, (byte) 0);
					buf = bigger;
				}
				int n = in.read(buf, count, buf.length - count);
				if (n < 0) break;
				count += n;
			}
			if (count == buf.length) return buf;
			byte[] exact = Arrays.copyOf(buf, count);
			Arrays.fill(buf, (byte) 0);
			return exact;
		} catch (IOException | RuntimeException | OutOfMemoryError e) {
			Arrays.fill(buf, (byte) 0);
			throw e;
		}
	}

	static InputStream decrypting(KdbxHeader header, byte[] key, InputStream in) {
		KeyParameter keyParameter = new KeyParameter(key);
		ParametersWithIV params = new ParametersWithIV(keyParameter,
				header.encryptionIv);
		switch (header.cipher) {
			case CHACHA20: {
				StreamCipher engine = new ChaCha7539Engine();
				engine.init(false, params);
				return new CipherInputStream(in, engine);
			}
			case TWOFISH_CBC: {
				BufferedBlockCipher cipher = new PaddedBufferedBlockCipher(
						CBCBlockCipher.newInstance(new TwofishEngine()),
						new PKCS7Padding());
				cipher.init(false, params);
				return new CipherInputStream(in, cipher);
			}
			default: {
				BufferedBlockCipher cipher = new PaddedBufferedBlockCipher(
						CBCBlockCipher.newInstance(AESEngine.newInstance()),
						new PKCS7Padding());
				cipher.init(false, params);
				return new CipherInputStream(in, cipher);
			}
		}
	}

	static final class LimitedInputStream extends FilterInputStream {

		private final long limit;
		private long count = 0;

		LimitedInputStream(InputStream in, long limit) {
			super(in);
			this.limit = limit;
		}

		@Override
		public int read() throws IOException {
			int b = in.read();
			if (b >= 0) count(1);
			return b;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			int n = in.read(b, off, len);
			if (n > 0) count(n);
			return n;
		}

		private void count(int n) throws IOException {
			count += n;
			if (count > limit) {
				throw new KdbxIOException(KdbxException.tooLarge("payload"));
			}
		}

		@Override
		public long skip(long n) throws IOException {
			long skipped = in.skip(n);
			if (skipped > 0) count((int) Math.min(Integer.MAX_VALUE, skipped));
			return skipped;
		}

		@Override
		public boolean markSupported() {
			return false;
		}
	}

	static final class DeclarationScanInputStream extends FilterInputStream {

		private static final byte[] DOCTYPE = "<!DOCTYPE".getBytes();
		private static final byte[] ENTITY = "<!ENTITY".getBytes();
		private final byte[] window = new byte[DOCTYPE.length];
		private final byte[] head = new byte[4];
		private int filled = 0;
		private int headCount = 0;

		DeclarationScanInputStream(InputStream in) {
			super(in);
		}

		@Override
		public int read() throws IOException {
			int b = in.read();
			if (b >= 0) scan((byte) b);
			return b;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			int n = in.read(b, off, len);
			for (int i = 0; i < n; i++) scan(b[off + i]);
			return n;
		}

		private void scan(byte b) throws IOException {
			if (headCount < head.length) {
				head[headCount++] = b;
				if (headCount == head.length) checkHead();
			}
			System.arraycopy(window, 1, window, 0, window.length - 1);
			window[window.length - 1] = b;
			if (filled < window.length) filled++;
			if (filled >= ENTITY.length && endsWith(ENTITY)) refuse();
			if (filled >= DOCTYPE.length && endsWith(DOCTYPE)) refuse();
		}

		private boolean endsWith(byte[] needle) {
			int start = window.length - needle.length;
			for (int i = 0; i < needle.length; i++) {
				int a = window[start + i] & 0xFF;
				if (a >= 'a' && a <= 'z') a -= 32;
				if (a != (needle[i] & 0xFF)) return false;
			}
			return true;
		}

		private void checkHead() throws IOException {
			int start = 0;
			if ((head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB
					&& (head[2] & 0xFF) == 0xBF) {
				start = 3;
			}
			if (head[start] != '<') refuse();
			if (start + 1 < head.length && head[start + 1] == 0) refuse();
		}

		private static void refuse() throws IOException {
			throw new KdbxIOException(KdbxException.corrupted("xml declaration"));
		}

		@Override
		public void close() {
		}

		@Override
		public long skip(long n) throws IOException {
			long left = n;
			while (left > 0 && read() >= 0) left--;
			return n - left;
		}

		@Override
		public boolean markSupported() {
			return false;
		}
	}

	static final class HmacBlockInputStream extends InputStream {

		private final byte[] file;
		private final byte[] hmacBase;
		private int pos;
		private long index = 0;
		private long total = 0;
		private int blockEnd = 0;
		private boolean finished = false;

		/**
		 * The first failure of this stream. A reader above it can swallow the
		 * exception, as the gzip reader does when it probes for a second
		 * member after the compressed data, so every later read throws the
		 * same failure again and the drain after parsing still sees it.
		 */
		@Nullable
		private IOException failure = null;

		HmacBlockInputStream(byte[] file, int start, byte[] hmacBase) {
			this.file = file;
			this.pos = start;
			this.hmacBase = hmacBase;
		}

		private boolean nextBlock() throws IOException {
			if (failure != null) throw failure;
			try {
				return readNextBlock();
			} catch (IOException e) {
				failure = e;
				throw e;
			}
		}

		private boolean readNextBlock() throws IOException {
			if (finished) return false;
			if (file.length - pos < 36) throw corrupted("block header");
			byte[] mac = Arrays.copyOfRange(file, pos, pos + 32);
			int len = KdbxBytes.i32(file, pos + 32);
			pos += 36;
			if (len < 0 || len > KdbxReader.MAX_BLOCK_BYTES) {
				throw corrupted("block size");
			}
			if (len > file.length - pos) throw corrupted("block data");
			total += len;
			if (total > KdbxReader.MAX_PAYLOAD_BYTES) {
				throw new KdbxIOException(KdbxException.tooLarge("payload"));
			}
			byte[] indexBytes = KdbxBytes.u64le(index);
			byte[] blockKey = KdbxBytes.sha512(indexBytes, hmacBase);
			byte[] expected = KdbxBytes.hmacSha256Range(blockKey, indexBytes,
					KdbxBytes.i32le(len), file, pos, len);
			KdbxBytes.wipe(blockKey);
			if (!KdbxBytes.constantTimeEquals(expected, mac)) {
				throw corrupted("block hmac");
			}
			index++;
			if (len == 0) {
				finished = true;
				if (pos != file.length) throw corrupted("trailing data");
				return false;
			}
			blockEnd = pos + len;
			return true;
		}

		private static KdbxIOException corrupted(String what) {
			return new KdbxIOException(KdbxException.corrupted(what));
		}

		@Override
		public int read() throws IOException {
			while (pos >= blockEnd) {
				if (!nextBlock()) return -1;
			}
			return file[pos++] & 0xFF;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (len == 0) return 0;
			while (pos >= blockEnd) {
				if (!nextBlock()) return -1;
			}
			int n = Math.min(len, blockEnd - pos);
			System.arraycopy(file, pos, b, off, n);
			pos += n;
			return n;
		}
	}

	static final class HashedBlockInputStream extends InputStream {

		private final InputStream in;
		private long expectedIndex = 0;
		private long total = 0;
		@Nullable
		private byte[] block = null;
		private int blockPos = 0;
		private boolean finished = false;

		/**
		 * The first failure of this stream. A reader above it can swallow the
		 * exception, as the gzip reader does when it probes for a second
		 * member after the compressed data, so every later read throws the
		 * same failure again and the drain after parsing still sees it.
		 */
		@Nullable
		private IOException failure = null;

		HashedBlockInputStream(InputStream in) {
			this.in = in;
		}

		private boolean nextBlock() throws IOException {
			if (failure != null) throw failure;
			try {
				return readNextBlock();
			} catch (IOException e) {
				failure = e;
				throw e;
			}
		}

		private boolean readNextBlock() throws IOException {
			if (finished) return false;
			if (block != null) KdbxBytes.wipe(block);
			block = null;
			byte[] head = readFully(in, 40);
			long index = KdbxBytes.u32(head, 0);
			byte[] hash = Arrays.copyOfRange(head, 4, 36);
			int len = KdbxBytes.i32(head, 36);
			if (index != expectedIndex) throw corrupted("block index");
			if (len < 0 || len > KdbxReader.MAX_BLOCK_BYTES) {
				throw corrupted("block size");
			}
			total += len;
			if (total > KdbxReader.MAX_PAYLOAD_BYTES) {
				throw new KdbxIOException(KdbxException.tooLarge("payload"));
			}
			if (len == 0) {
				if (!KdbxBytes.constantTimeEquals(hash, new byte[32])) {
					throw corrupted("final block");
				}
				finished = true;
				return false;
			}
			byte[] data = readFully(in, len);
			if (!KdbxBytes.constantTimeEquals(KdbxBytes.sha256(data), hash)) {
				KdbxBytes.wipe(data);
				throw corrupted("block hash");
			}
			expectedIndex++;
			block = data;
			blockPos = 0;
			return true;
		}

		private static KdbxIOException corrupted(String what) {
			return new KdbxIOException(KdbxException.corrupted(what));
		}

		@Override
		public int read() throws IOException {
			while (block == null || blockPos >= block.length) {
				if (!nextBlock()) return -1;
			}
			return block[blockPos++] & 0xFF;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (len == 0) return 0;
			while (block == null || blockPos >= block.length) {
				if (!nextBlock()) return -1;
			}
			int n = Math.min(len, block.length - blockPos);
			System.arraycopy(block, blockPos, b, off, n);
			blockPos += n;
			return n;
		}

		@Override
		public void close() throws IOException {
			if (block != null) KdbxBytes.wipe(block);
			block = null;
			in.close();
		}
	}

	static InputStream bytes(byte[] data, int from) {
		return new ByteArrayInputStream(data, from, data.length - from);
	}
}
