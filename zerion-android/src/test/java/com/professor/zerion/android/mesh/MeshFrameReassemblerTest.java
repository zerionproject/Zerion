package com.professor.zerion.android.mesh;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The BLE frame reassembler under every chunking a link can produce and
 * under hostile length prefixes: frames come back whole and in order however
 * the bytes were split, an oversized or negative length discards the buffer
 * instead of allocating, and the reassembler recovers on the next well formed
 * frame.
 */
public class MeshFrameReassemblerTest {

	private static final int MAX_FRAME = 16384;
	private static final int ROUNDS = 400;

	private final Random random = new Random(71);

	@Test
	public void framesSurviveEveryChunkingOfTheStream() {
		for (int round = 0; round < ROUNDS; round++) {
			List<byte[]> frames = new ArrayList<>();
			ByteArrayOutputStream stream = new ByteArrayOutputStream();
			int count = 1 + random.nextInt(6);
			for (int i = 0; i < count; i++) {
				byte[] frame = new byte[random.nextInt(4) == 0
						? random.nextInt(MAX_FRAME + 1)
						: random.nextInt(64)];
				random.nextBytes(frame);
				frames.add(frame);
				stream.write(frame.length >>> 24);
				stream.write(frame.length >>> 16);
				stream.write(frame.length >>> 8);
				stream.write(frame.length);
				stream.write(frame, 0, frame.length);
			}
			byte[] bytes = stream.toByteArray();
			MeshFrameReassembler r = new MeshFrameReassembler(MAX_FRAME);
			List<byte[]> polled = new ArrayList<>();
			int pos = 0;
			while (pos < bytes.length) {
				int n = Math.min(bytes.length - pos,
						1 + random.nextInt(random.nextBoolean() ? 20 : 600));
				byte[] chunk = new byte[n];
				System.arraycopy(bytes, pos, chunk, 0, n);
				pos += n;
				r.append(chunk);
				byte[] frame;
				while ((frame = r.poll()) != null) polled.add(frame);
			}
			assertEquals("round " + round, frames.size(), polled.size());
			for (int i = 0; i < frames.size(); i++) {
				assertArrayEquals("round " + round + " frame " + i,
						frames.get(i), polled.get(i));
			}
			assertNull(r.poll());
		}
	}

	@Test
	public void aPartialFrameYieldsNothingUntilItCompletes() {
		MeshFrameReassembler r = new MeshFrameReassembler(MAX_FRAME);
		r.append(new byte[] {0, 0});
		assertNull(r.poll());
		r.append(new byte[] {0, 3, 'a'});
		assertNull(r.poll());
		r.append(new byte[] {'b'});
		assertNull(r.poll());
		r.append(new byte[] {'c'});
		assertArrayEquals(new byte[] {'a', 'b', 'c'}, r.poll());
		assertNull(r.poll());
	}

	@Test
	public void anEmptyFrameIsAFrame() {
		MeshFrameReassembler r = new MeshFrameReassembler(MAX_FRAME);
		r.append(new byte[] {0, 0, 0, 0, 0, 0, 0, 1, 'x'});
		assertArrayEquals(new byte[0], r.poll());
		assertArrayEquals(new byte[] {'x'}, r.poll());
		assertNull(r.poll());
	}

	@Test
	public void anOversizedLengthDiscardsTheBufferWithoutAllocating() {
		MeshFrameReassembler r = new MeshFrameReassembler(MAX_FRAME);
		r.append(new byte[] {0, 0, (byte) 0x40, 1, 'j', 'u', 'n', 'k'});
		assertNull(r.poll());
		r.append(new byte[] {0, 0, 0, 2, 'o', 'k'});
		assertArrayEquals(new byte[] {'o', 'k'}, r.poll());
	}

	@Test
	public void aNegativeLengthDiscardsTheBuffer() {
		MeshFrameReassembler r = new MeshFrameReassembler(MAX_FRAME);
		r.append(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
				(byte) 0xFF, 1, 2, 3});
		assertNull(r.poll());
		r.append(new byte[] {(byte) 0x80, 0, 0, 0});
		assertNull(r.poll());
		r.append(new byte[] {0, 0, 0, 1, 'z'});
		assertArrayEquals(new byte[] {'z'}, r.poll());
	}

	@Test
	public void aLengthOneOverTheLimitIsRefusedAndTheLimitItselfIsAccepted() {
		MeshFrameReassembler r = new MeshFrameReassembler(MAX_FRAME);
		byte[] atLimit = new byte[MAX_FRAME];
		random.nextBytes(atLimit);
		r.append(prefix(MAX_FRAME));
		r.append(atLimit);
		assertArrayEquals(atLimit, r.poll());
		r.append(prefix(MAX_FRAME + 1));
		r.append(new byte[MAX_FRAME + 1]);
		assertNull(r.poll());
		assertNull(r.poll());
	}

	@Test
	public void manyFramesAppendedBeforeAnyPollComeBackInOrder() {
		MeshFrameReassembler r = new MeshFrameReassembler(MAX_FRAME);
		List<byte[]> frames = new ArrayList<>();
		for (int i = 0; i < 500; i++) {
			byte[] frame = new byte[random.nextInt(300)];
			random.nextBytes(frame);
			frames.add(frame);
			r.append(prefix(frame.length));
			r.append(frame);
		}
		for (byte[] frame : frames) assertArrayEquals(frame, r.poll());
		assertNull(r.poll());
	}

	private static byte[] prefix(int len) {
		return new byte[] {(byte) (len >>> 24), (byte) (len >>> 16),
				(byte) (len >>> 8), (byte) len};
	}
}
