package com.professor.zerion.android.conversation.voice;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AudioJitterBufferTest {

	private static byte[] bytes(int from, int count) {
		byte[] b = new byte[count];
		for (int i = 0; i < count; i++) b[i] = (byte) (from + i);
		return b;
	}

	@Test
	public void playoutStartsOnlyOnceTheThresholdIsBuffered() {
		AudioJitterBuffer jb = new AudioJitterBuffer(16, 6);
		jb.write(bytes(1, 5));
		assertFalse(jb.started());
		jb.write(bytes(6, 1));
		assertTrue(jb.started());
		byte[] out = new byte[4];
		assertTrue(jb.read(out));
		assertArrayEquals(bytes(1, 4), out);
		assertFalse("two bytes are not a frame", jb.read(out));
		assertTrue("playout stays started while draining", jb.started());
	}

	@Test
	public void bytesComeOutInOrderAcrossTheWrap() {
		AudioJitterBuffer jb = new AudioJitterBuffer(8, 1);
		byte[] out = new byte[4];
		jb.write(bytes(1, 6));
		assertTrue(jb.read(out));
		assertArrayEquals(bytes(1, 4), out);
		jb.write(bytes(7, 6));
		assertEquals(8, jb.buffered());
		assertTrue(jb.read(out));
		assertArrayEquals(bytes(5, 4), out);
		assertTrue(jb.read(out));
		assertArrayEquals(bytes(9, 4), out);
		assertEquals(0, jb.buffered());
	}

	@Test
	public void anOverflowDropsTheOldestBytes() {
		AudioJitterBuffer jb = new AudioJitterBuffer(8, 1);
		jb.write(bytes(1, 6));
		jb.write(bytes(7, 4));
		assertEquals(8, jb.buffered());
		byte[] out = new byte[8];
		assertTrue(jb.read(out));
		assertArrayEquals(bytes(3, 8), out);
		jb.write(bytes(11, 3));
		byte[] three = new byte[3];
		assertTrue(jb.read(three));
		assertArrayEquals(bytes(11, 3), three);
	}

	@Test
	public void aChunkLongerThanTheBufferKeepsItsNewestBytes() {
		AudioJitterBuffer jb = new AudioJitterBuffer(8, 1);
		jb.write(bytes(1, 3));
		jb.write(bytes(10, 10));
		byte[] out = new byte[8];
		assertTrue(jb.read(out));
		assertArrayEquals(bytes(12, 8), out);
		jb.write(bytes(30, 2));
		byte[] two = new byte[2];
		assertTrue(jb.read(two));
		assertArrayEquals(bytes(30, 2), two);
	}

	@Test
	public void anyMixOfWritesAndReadsBehavesLikeABoundedQueue() {
		java.util.Random random = new java.util.Random(7);
		for (int capacity : new int[] {1, 7, 640, 64000}) {
			AudioJitterBuffer jb = new AudioJitterBuffer(capacity, 1);
			java.util.ArrayDeque<Byte> model = new java.util.ArrayDeque<>();
			int next = 0;
			for (int step = 0; step < 3000; step++) {
				if (random.nextBoolean()) {
					byte[] chunk = new byte[random.nextInt(2 * capacity + 2)];
					for (int i = 0; i < chunk.length; i++) {
						chunk[i] = (byte) next++;
					}
					jb.write(chunk);
					for (byte b : chunk) {
						model.addLast(b);
						if (model.size() > capacity) model.removeFirst();
					}
				} else {
					byte[] out = new byte[random.nextInt(capacity + 1)];
					boolean read = jb.read(out);
					assertEquals(model.size() >= out.length, read);
					if (read) {
						for (byte b : out) {
							assertEquals((byte) model.removeFirst(), b);
						}
					}
				}
				assertEquals(model.size(), jb.buffered());
			}
		}
	}

	@Test
	public void resetEmptiesAndStopsPlayoutWipeOnlyEmpties() {
		AudioJitterBuffer jb = new AudioJitterBuffer(8, 2);
		jb.write(bytes(1, 4));
		assertTrue(jb.started());
		jb.wipe();
		assertEquals(0, jb.buffered());
		assertTrue(jb.started());
		jb.write(bytes(1, 4));
		jb.reset();
		assertEquals(0, jb.buffered());
		assertFalse(jb.started());
		assertFalse(jb.read(new byte[1]));
	}

	@Test
	public void aWriterAndAReaderOnTwoThreadsLoseNothing() throws Exception {
		int frame = 640;
		int frames = 400;
		AudioJitterBuffer jb = new AudioJitterBuffer(64000, 1920);
		ByteArrayOutputStream got = new ByteArrayOutputStream();
		AtomicReference<Throwable> error = new AtomicReference<>();
		Thread reader = new Thread(() -> {
			try {
				byte[] out = new byte[frame];
				int read = 0;
				long deadline = System.currentTimeMillis() + 60_000;
				while (read < frames && System.currentTimeMillis() < deadline) {
					if (jb.started() && jb.read(out)) {
						got.write(out, 0, out.length);
						read++;
					} else {
						Thread.yield();
					}
				}
			} catch (Throwable t) {
				error.set(t);
			}
		});
		reader.start();
		ByteArrayOutputStream want = new ByteArrayOutputStream();
		for (int k = 0; k < frames; k++) {
			byte[] chunk = bytes(k, frame);
			want.write(chunk, 0, chunk.length);
			while (jb.buffered() > 64000 - frame) Thread.yield();
			jb.write(chunk);
		}
		reader.join(120_000);
		assertNull(error.get());
		assertArrayEquals(want.toByteArray(), got.toByteArray());
	}
}
