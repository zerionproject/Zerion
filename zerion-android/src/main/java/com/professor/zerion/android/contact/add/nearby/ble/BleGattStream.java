package com.professor.zerion.android.contact.add.nearby.ble;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.annotation.concurrent.GuardedBy;

/**
 * A blocking byte stream over one GATT characteristic used by the offline
 * pairing key agreement. Bytes are sent in chunks through a supplied sender and
 * received bytes are fed in from the GATT callbacks. The volume is a few small
 * handshake messages, so a plain growing buffer is enough.
 */
@NotNullByDefault
class BleGattStream {

	interface ChunkSender {
		boolean send(byte[] chunk);
	}

	interface Closer {
		void close();
	}

	private static final long READ_TIMEOUT_MS = 60_000;
	private static final int MAX_BUFFERED = 64 * 1024;

	private final int chunkSize;
	private final ChunkSender sender;
	private final Closer closer;

	private final Object inLock = new Object();
	@GuardedBy("inLock")
	private byte[] inBuf = new byte[0];
	@GuardedBy("inLock")
	private int inPos = 0;
	private volatile boolean eof = false;
	private volatile boolean closed = false;

	BleGattStream(int chunkSize, ChunkSender sender, Closer closer) {
		this.chunkSize = Math.max(20, chunkSize);
		this.sender = sender;
		this.closer = closer;
	}

	void onReceive(byte[] data) {
		if (data.length == 0) return;
		synchronized (inLock) {
			int remaining = inBuf.length - inPos;
			if (remaining + data.length > MAX_BUFFERED) {
				eof = true;
				inLock.notifyAll();
				return;
			}
			byte[] grown = new byte[remaining + data.length];
			System.arraycopy(inBuf, inPos, grown, 0, remaining);
			System.arraycopy(data, 0, grown, remaining, data.length);
			inBuf = grown;
			inPos = 0;
			inLock.notifyAll();
		}
	}

	void setEof() {
		eof = true;
		synchronized (inLock) {
			inLock.notifyAll();
		}
	}

	void close() {
		if (closed) return;
		closed = true;
		setEof();
		closer.close();
	}

	InputStream getInputStream() {
		return new InputStream() {
			private final byte[] one = new byte[1];

			@Override
			public int read() throws IOException {
				int n = read(one, 0, 1);
				return n == -1 ? -1 : (one[0] & 0xFF);
			}

			@Override
			public int available() {
				synchronized (inLock) {
					int n = inBuf.length - inPos;
					if (n > 0) return n;
					return (eof || closed) ? 1 : 0;
				}
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				if (len == 0) return 0;
				synchronized (inLock) {
					long deadline = System.currentTimeMillis() + READ_TIMEOUT_MS;
					while (inPos >= inBuf.length && !eof && !closed) {
						long wait = deadline - System.currentTimeMillis();
						if (wait <= 0) throw new IOException("read timed out");
						try {
							inLock.wait(wait);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							throw new IOException("interrupted");
						}
					}
					if (inPos >= inBuf.length) return -1;
					int n = Math.min(len, inBuf.length - inPos);
					System.arraycopy(inBuf, inPos, b, off, n);
					inPos += n;
					return n;
				}
			}

			@Override
			public void close() {
				BleGattStream.this.close();
			}
		};
	}

	OutputStream getOutputStream() {
		return new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				write(new byte[] {(byte) b}, 0, 1);
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				for (int p = 0; p < len; p += chunkSize) {
					if (closed) throw new IOException("closed");
					int n = Math.min(chunkSize, len - p);
					byte[] chunk = new byte[n];
					System.arraycopy(b, off + p, chunk, 0, n);
					if (!sender.send(chunk)) {
						throw new IOException("send failed");
					}
				}
			}

			@Override
			public void close() {
				BleGattStream.this.close();
			}
		};
	}
}
