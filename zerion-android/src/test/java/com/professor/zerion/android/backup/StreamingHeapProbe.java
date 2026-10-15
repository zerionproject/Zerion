package com.professor.zerion.android.backup;

import com.professor.zerion.android.vault.crypto.Argon2;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.SequenceInputStream;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicReference;

public final class StreamingHeapProbe {

	static final int OK = 0;
	static final int MISMATCH = 1;
	static final int FAILED = 3;
	static final int OUT_OF_MEMORY = 4;

	private static final char[] PASSPHRASE = "probe passphrase".toCharArray();

	private StreamingHeapProbe() {
	}

	public static void main(String[] args) {
		System.exit(run(Long.parseLong(args[0]) << 20,
				args.length > 1 && args[1].equals("buffer")));
	}

	static int run(long dbLength, boolean buffer) {
		try {
			if (buffer) {
				byte[] whole = new byte[(int) dbLength];
				return whole.length == dbLength ? OK : MISMATCH;
			}
			return streamed(dbLength);
		} catch (OutOfMemoryError e) {
			return OUT_OF_MEMORY;
		} catch (Exception e) {
			return FAILED;
		}
	}

	private static int streamed(long dbLength) throws Exception {
		Argon2.Argon2Params params = new Argon2.Argon2Params(
				Argon2.LOW_MEMORY_KB, 1, 1, Argon2.DEFAULT_HASH_LENGTH);
		byte[] prefix = BackupBundle.prefix("Probe", new byte[32], dbLength);
		Generated db = new Generated(dbLength);
		InputStream bundle = new SequenceInputStream(new SequenceInputStream(
				new ByteArrayInputStream(prefix), db),
				new ByteArrayInputStream(BackupBundle.suffix()));
		long length = prefix.length + dbLength + 4;
		PipedOutputStream sealed = new PipedOutputStream();
		PipedInputStream in = new PipedInputStream(sealed, 4 << 20);
		AtomicReference<Throwable> sealFailure = new AtomicReference<>();
		Thread writer = new Thread(() -> {
			try (OutputStream out = sealed) {
				BackupStream.seal(bundle, length, PASSPHRASE, (byte) 0,
						params, BackupStream.DEFAULT_CHUNK, out);
			} catch (Throwable t) {
				sealFailure.set(t);
			}
		});
		writer.start();
		byte[] encoded = new byte[BackupStream.HEADER_LENGTH];
		byte[] mac = new byte[BackupStream.MAC_LENGTH];
		if (!BackupStream.readFully(in, encoded, encoded.length)
				|| !BackupStream.readFully(in, mac, mac.length)) {
			return FAILED;
		}
		BackupStream.Header header = BackupStream.parse(encoded);
		BackupStream.Keys keys = BackupStream.derive(PASSPHRASE, header);
		if (!BackupStream.verify(keys, mac)) return FAILED;
		DigestSink restored = new DigestSink();
		BundleStreamParser parser = new BundleStreamParser(restored);
		BackupStream.readChunks(in, header, keys.enc, parser);
		parser.finish();
		writer.join();
		Throwable t = sealFailure.get();
		if (t instanceof OutOfMemoryError) return OUT_OF_MEMORY;
		if (t != null) return FAILED;
		return MessageDigest.isEqual(db.digest(), restored.digest())
				&& restored.count == dbLength ? OK : MISMATCH;
	}

	private static final class Generated extends InputStream {

		private final MessageDigest digest;
		private long remaining;
		private long state = 0x9E3779B97F4A7C15L;

		Generated(long length) throws Exception {
			remaining = length;
			digest = MessageDigest.getInstance("SHA-256");
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
		}

		@Override
		public int read(byte[] b, int off, int len) {
			if (remaining == 0) return -1;
			int n = (int) Math.min(len, remaining);
			for (int i = 0; i < n; i++) {
				state ^= state << 13;
				state ^= state >>> 7;
				state ^= state << 17;
				b[off + i] = (byte) state;
			}
			digest.update(b, off, n);
			remaining -= n;
			return n;
		}

		byte[] digest() {
			return digest.digest();
		}
	}

	private static final class DigestSink extends OutputStream {

		private final MessageDigest digest;
		long count;

		DigestSink() throws Exception {
			digest = MessageDigest.getInstance("SHA-256");
		}

		@Override
		public void write(int b) {
			write(new byte[] {(byte) b}, 0, 1);
		}

		@Override
		public void write(byte[] b, int off, int len) {
			digest.update(b, off, len);
			count += len;
		}

		byte[] digest() {
			return digest.digest();
		}
	}
}
