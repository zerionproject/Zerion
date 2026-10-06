package org.zerionproject.transport.mesh;

import org.zerionproject.core.api.FormatException;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MeshForwarderFloodTest {

	private static final int FULL_PAYLOAD = MeshForwarder.MAX_PAYLOAD_BYTES;

	private final SecureRandom random = new SecureRandom();
	private final AtomicLong now = new AtomicLong(1_000_000L);
	private final List<byte[]> delivered = new ArrayList<>();
	private final MeshForwarder node =
			new MeshForwarder(delivered::add, random);

	private static byte[] frame(int holder, int n, int payloadBytes) {
		byte[] messageId = new byte[MeshFrame.MESSAGE_ID_BYTES];
		writeInt(holder, messageId, 0);
		writeInt(n, messageId, 4);
		return new MeshFrame(3, messageId, new byte[payloadBytes]).encode();
	}

	private static void writeInt(int v, byte[] b, int off) {
		b[off] = (byte) (v >>> 24);
		b[off + 1] = (byte) (v >>> 16);
		b[off + 2] = (byte) (v >>> 8);
		b[off + 3] = (byte) v;
	}

	private static int holderOf(byte[] encoded) throws FormatException {
		byte[] id = MeshFrame.decode(encoded).getMessageId();
		return ((id[0] & 0xFF) << 24) | ((id[1] & 0xFF) << 16)
				| ((id[2] & 0xFF) << 8) | (id[3] & 0xFF);
	}

	private void receive(byte[] frame, String peer) {
		now.addAndGet(25);
		node.onReceive(frame, "ble", peer);
	}

	private List<byte[]> carried() {
		List<byte[]> handed = new ArrayList<>();
		node.addLink(new MeshLink() {
			@Override
			public String getId() {
				return "late";
			}

			@Override
			public void broadcast(byte[] frame) {
				handed.add(frame);
			}
		});
		node.removeLink("late");
		return handed;
	}

	private static long bytes(List<byte[]> frames) {
		long total = 0;
		for (byte[] f : frames) total += f.length;
		return total;
	}

	@Test
	public void anOversizedFrameIsNeitherDeliveredRelayedNorCarried() {
		node.clock = now::get;
		AtomicInteger relayed = new AtomicInteger();
		node.addLink(new MeshLink() {
			@Override
			public String getId() {
				return "out";
			}

			@Override
			public void broadcast(byte[] frame) {
				relayed.incrementAndGet();
			}
		});
		receive(frame(1, 0, FULL_PAYLOAD + 1), "a");
		assertEquals(0, delivered.size());
		assertEquals(0, relayed.get());
		node.removeLink("out");
		assertTrue(carried().isEmpty());
		receive(frame(1, 1, FULL_PAYLOAD), "a");
		assertEquals("a frame at the bound is still accepted", 1,
				delivered.size());
	}

	@Test
	public void aFloodingNeighbourDisplacesOnlyItsOwnCarriedFrames()
			throws Exception {
		node.clock = now::get;
		receive(frame(2, 0, 100), "b");
		int flood = (3 * MeshForwarder.STORE_MAX_BYTES) / FULL_PAYLOAD;
		for (int i = 0; i < flood; i++) receive(frame(1, i, FULL_PAYLOAD), "a");
		List<byte[]> carried = carried();
		Map<Integer, Long> held = heldBytes(carried);
		assertTrue("b's frame was pushed out", held.containsKey(2));
		assertTrue("a holds more than its share",
				held.get(1) <= MeshForwarder.STORE_PER_PEER_MAX_BYTES);
	}

	@Test
	public void aFloodOverManyIdentitiesCannotPushOutANeighboursFrame()
			throws Exception {
		node.clock = now::get;
		receive(frame(2, 0, 100), "b");
		floodFromManyIdentities();
		List<byte[]> carried = carried();
		assertTrue("b's frame was pushed out",
				heldBytes(carried).containsKey(2));
		assertTrue(bytes(carried) <= MeshForwarder.STORE_MAX_BYTES);
	}

	@Test
	public void ownFramesAreStillCarriedAfterAFlood() throws Exception {
		node.clock = now::get;
		byte[] own = node.originate(new byte[100]);
		floodFromManyIdentities();
		boolean found = false;
		for (byte[] f : carried()) {
			if (java.util.Arrays.equals(own,
					MeshFrame.decode(f).getMessageId())) {
				found = true;
			}
		}
		assertTrue("this node's own frame was pushed out", found);
	}

	@Test
	public void manySmallFramesCannotGrowTheCarriedSetWithoutBound() {
		node.clock = now::get;
		int identities = 40;
		int perIdentity = 100;
		for (int n = 0; n < perIdentity; n++) {
			for (int p = 0; p < identities; p++) {
				receive(frame(100 + p, n, 1), "tiny" + p);
			}
		}
		List<byte[]> carried = carried();
		assertTrue("carried " + carried.size() + " frames",
				carried.size() <= MeshForwarder.STORE_MAX_FRAMES);
	}

	private void floodFromManyIdentities() {
		int identities = 16;
		int perIdentity = 12;
		for (int n = 0; n < perIdentity; n++) {
			for (int p = 0; p < identities; p++) {
				receive(frame(10 + p, n, FULL_PAYLOAD), "fake" + p);
			}
		}
	}

	private static Map<Integer, Long> heldBytes(List<byte[]> carried)
			throws FormatException {
		Map<Integer, Long> held = new HashMap<>();
		for (byte[] f : carried) {
			held.merge(holderOf(f), (long) f.length, Long::sum);
		}
		return held;
	}
}
