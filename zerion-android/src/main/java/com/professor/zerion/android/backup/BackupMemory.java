package com.professor.zerion.android.backup;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static com.professor.zerion.android.backup.BackupException.Reason.TOO_LARGE;

@NotNullByDefault
final class BackupMemory {

	static final int WORKING_COPIES = 3;
	static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
	private static final int FIRST_BUFFER = 64 * 1024;

	private BackupMemory() {
	}

	static long limit(int copies, long maxMemory) {
		long budget = maxMemory - maxMemory / 4;
		return Math.min(MAX_FILE_BYTES, budget / Math.max(1, copies));
	}

	static long limit() {
		return limit(WORKING_COPIES, Runtime.getRuntime().maxMemory());
	}

	static void check(long bytes) throws BackupException {
		check(bytes, Runtime.getRuntime().maxMemory());
	}

	static void check(long bytes, long maxMemory) throws BackupException {
		if (bytes > limit(WORKING_COPIES, maxMemory)) {
			throw new BackupException(TOO_LARGE);
		}
	}

	static byte[] read(InputStream in, long declared, long limit)
			throws IOException, BackupException {
		if (declared > limit) throw new BackupException(TOO_LARGE);
		if (declared >= 0) return readExactly(in, (int) declared);
		return readUnknown(in, limit);
	}

	private static byte[] readExactly(InputStream in, int length)
			throws IOException {
		byte[] data = new byte[length];
		int off = 0;
		while (off < length) {
			int n = in.read(data, off, length - off);
			if (n < 0) throw new IOException("Short read");
			off += n;
		}
		if (in.read() != -1) throw new IOException("File changed");
		return data;
	}

	private static byte[] readUnknown(InputStream in, long limit)
			throws IOException, BackupException {
		byte[] buf = new byte[(int) Math.min(FIRST_BUFFER, limit + 1)];
		int count = 0;
		while (true) {
			if (count == buf.length) {
				if (count > limit) {
					Arrays.fill(buf, (byte) 0);
					throw new BackupException(TOO_LARGE);
				}
				long grown = Math.min((long) buf.length * 2, limit + 1);
				byte[] bigger = Arrays.copyOf(buf, (int) grown);
				Arrays.fill(buf, (byte) 0);
				buf = bigger;
			}
			int n = in.read(buf, count, buf.length - count);
			if (n < 0) break;
			count += n;
		}
		if (count > limit) {
			Arrays.fill(buf, (byte) 0);
			throw new BackupException(TOO_LARGE);
		}
		if (count == buf.length) return buf;
		byte[] exact = Arrays.copyOf(buf, count);
		Arrays.fill(buf, (byte) 0);
		return exact;
	}
}
