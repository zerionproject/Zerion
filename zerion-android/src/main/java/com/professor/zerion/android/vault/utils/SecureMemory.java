package com.professor.zerion.android.vault.utils;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@NotNullByDefault
public class SecureMemory {

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();
	private static final int SHRED_PASSES = 3;
	private static final Executor GC_EXECUTOR =
			Executors.newSingleThreadExecutor();

	public static void shred(byte[] data) {
		if (data == null || data.length == 0) return;

		try {
			SECURE_RANDOM.nextBytes(data);

			Arrays.fill(data, (byte) 0);

			Arrays.fill(data, (byte) 0xFF);

			Arrays.fill(data, (byte) 0);

		} catch (Exception e) {
		}
	}

	public static void shred(char[] data) {
		if (data == null || data.length == 0) return;

		try {
			for (int i = 0; i < data.length; i++) {
				data[i] = (char) SECURE_RANDOM.nextInt(65536);
			}

			Arrays.fill(data, '\0');

			Arrays.fill(data, (char) 0xFFFF);

			Arrays.fill(data, '\0');

		} catch (Exception e) {
		}
	}

	public static void shred(ByteBuffer buffer) {
		if (buffer == null) return;

		try {
			buffer.rewind();
			byte[] random = new byte[Math.min(buffer.remaining(), 8192)];

			while (buffer.hasRemaining()) {
				int chunk = Math.min(buffer.remaining(), random.length);
				SECURE_RANDOM.nextBytes(random);
				buffer.put(random, 0, chunk);
			}

			buffer.rewind();
			Arrays.fill(random, (byte) 0);
			while (buffer.hasRemaining()) {
				int chunk = Math.min(buffer.remaining(), random.length);
				buffer.put(random, 0, chunk);
			}

			buffer.rewind();
			Arrays.fill(random, (byte) 0xFF);
			while (buffer.hasRemaining()) {
				int chunk = Math.min(buffer.remaining(), random.length);
				buffer.put(random, 0, chunk);
			}

			buffer.rewind();
			Arrays.fill(random, (byte) 0);
			while (buffer.hasRemaining()) {
				int chunk = Math.min(buffer.remaining(), random.length);
				buffer.put(random, 0, chunk);
			}

			Arrays.fill(random, (byte) 0);

		} catch (Exception e) {
		}
	}

	public static void shred(CharBuffer buffer) {
		if (buffer == null) return;

		try {
			buffer.rewind();

			while (buffer.hasRemaining()) {
				buffer.put((char) SECURE_RANDOM.nextInt(65536));
			}

			buffer.rewind();
			while (buffer.hasRemaining()) {
				buffer.put('\0');
			}

			buffer.rewind();
			while (buffer.hasRemaining()) {
				buffer.put((char) 0xFFFF);
			}

			buffer.rewind();
			while (buffer.hasRemaining()) {
				buffer.put('\0');
			}

		} catch (Exception e) {
		}
	}

	public static void shredAll(byte[]... arrays) {
		for (byte[] array : arrays) {
			shred(array);
		}
	}

	public static void shredAll(char[]... arrays) {
		for (char[] array : arrays) {
			shred(array);
		}
	}

	public static void forceGarbageCollection() {
		GC_EXECUTOR.execute(() -> {
			try {
				System.gc();
				System.runFinalization();
				System.gc();
			} catch (Exception e) {
			}
		});
	}

	public static class SecureByteArray implements AutoCloseable {
		private final byte[] data;

		public SecureByteArray(int size) {
			this.data = new byte[size];
		}

		public SecureByteArray(byte[] source) {
			this.data = Arrays.copyOf(source, source.length);
		}

		public byte[] get() {
			return data;
		}

		public int length() {
			return data.length;
		}

		@Override
		public void close() {
			shred(data);
		}
	}

	public static class SecureCharArray implements AutoCloseable {
		private final char[] data;

		public SecureCharArray(int size) {
			this.data = new char[size];
		}

		public SecureCharArray(char[] source) {
			this.data = Arrays.copyOf(source, source.length);
		}

		public char[] get() {
			return data;
		}

		public int length() {
			return data.length;
		}

		@Override
		public void close() {
			shred(data);
		}
	}
}
