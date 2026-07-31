package org.zerionproject.transport.mesh;

import org.zerionproject.core.api.FormatException;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class MeshFrameTest {

	private final Random random = new Random(4);

	@Test
	public void roundTrips() throws FormatException {
		byte[] id = new byte[MeshFrame.MESSAGE_ID_BYTES];
		random.nextBytes(id);
		byte[] payload = new byte[300];
		random.nextBytes(payload);
		MeshFrame f = new MeshFrame(5, id, payload);
		MeshFrame d = MeshFrame.decode(f.encode());
		assertEquals(5, d.getHopsLeft());
		assertArrayEquals(id, d.getMessageId());
		assertArrayEquals(payload, d.getPayload());
	}

	@Test
	public void decrementReducesHopsAndStopsAtZero() {
		byte[] id = new byte[MeshFrame.MESSAGE_ID_BYTES];
		MeshFrame f = new MeshFrame(1, id, new byte[1]);
		MeshFrame once = f.decremented();
		assertEquals(0, once.getHopsLeft());
		assertNull(once.decremented());
	}

	@Test
	public void decodeRejectsBadVersionAndTrailingBytes() {
		byte[] id = new byte[MeshFrame.MESSAGE_ID_BYTES];
		byte[] enc = new MeshFrame(3, id, new byte[16]).encode();
		byte[] badVersion = enc.clone();
		badVersion[0] = 0x02;
		expectFormat(badVersion);
		byte[] trailing = new byte[enc.length + 1];
		System.arraycopy(enc, 0, trailing, 0, enc.length);
		expectFormat(trailing);
	}

	private void expectFormat(byte[] in) {
		try {
			MeshFrame.decode(in);
			fail("expected FormatException");
		} catch (FormatException expected) {
			// ok
		}
	}
}
