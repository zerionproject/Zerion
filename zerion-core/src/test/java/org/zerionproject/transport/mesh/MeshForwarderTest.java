package org.zerionproject.transport.mesh;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class MeshForwarderTest {

	private final SecureRandom random = new SecureRandom();

	private static class Collector implements MeshForwarder.FrameListener {
		final List<byte[]> delivered = new ArrayList<>();

		@Override
		public void onFrame(byte[] payload) {
			delivered.add(payload);
		}
	}

	/** Wires node {@code a}'s link to node {@code b} and vice versa, so a
	 * broadcast from one is delivered to the other as if over a radio. */
	private void connect(MeshForwarder a, String aId, MeshForwarder b,
			String bId) {
		a.addLink(link(aId, b, bId));
		b.addLink(link(bId, a, aId));
	}

	private MeshLink link(String id, MeshForwarder target,
			String targetInboundId) {
		return new MeshLink() {
			@Override
			public String getId() {
				return id;
			}

			@Override
			public void broadcast(byte[] frame) {
				target.onReceive(frame, targetInboundId);
			}
		};
	}

	@Test
	public void twoNodesDeliverOnce() {
		Collector cb = new Collector();
		MeshForwarder a = new MeshForwarder(p -> {}, random);
		MeshForwarder b = new MeshForwarder(cb, random);
		connect(a, "a-b", b, "b-a");
		byte[] payload = "war-zone message".getBytes();
		a.originate(payload);
		assertEquals(1, cb.delivered.size());
		assertArrayEquals(payload, cb.delivered.get(0));
	}

	@Test
	public void floodsThreeHopChainAndDeliversOncePerNode() {
		Collector cbB = new Collector();
		Collector cbC = new Collector();
		MeshForwarder a = new MeshForwarder(p -> {}, random);
		MeshForwarder b = new MeshForwarder(cbB, random);
		MeshForwarder c = new MeshForwarder(cbC, random);
		connect(a, "a-b", b, "b-a");
		connect(b, "b-c", c, "c-b");
		byte[] payload = "relayed".getBytes();
		a.originate(payload);
		assertEquals(1, cbB.delivered.size());
		assertEquals(1, cbC.delivered.size());
		assertArrayEquals(payload, cbC.delivered.get(0));
	}

	@Test
	public void duplicateFrameSuppressed() {
		Collector cb = new Collector();
		MeshForwarder node = new MeshForwarder(cb, random);
		byte[] id = new byte[MeshFrame.MESSAGE_ID_BYTES];
		byte[] frame = new MeshFrame(3, id, "hi".getBytes()).encode();
		node.onReceive(frame, "x");
		node.onReceive(frame, "x");
		assertEquals(1, cb.delivered.size());
	}

	@Test
	public void hopExhaustedFrameDeliversButDoesNotReflood() {
		Collector cb = new Collector();
		AtomicInteger rebroadcasts = new AtomicInteger();
		MeshForwarder node = new MeshForwarder(cb, random);
		node.addLink(new MeshLink() {
			@Override
			public String getId() {
				return "out";
			}

			@Override
			public void broadcast(byte[] frame) {
				rebroadcasts.incrementAndGet();
			}
		});
		byte[] id = new byte[MeshFrame.MESSAGE_ID_BYTES];
		byte[] frame = new MeshFrame(0, id, "last".getBytes()).encode();
		node.onReceive(frame, "in");
		assertEquals(1, cb.delivered.size());
		assertEquals(0, rebroadcasts.get());
	}

	@Test
	public void relaysOnSameLinkExcludingSourcePeer() {
		Collector cb = new Collector();
		MeshForwarder node = new MeshForwarder(cb, random);
		List<String> relayExcept = new ArrayList<>();
		node.addLink(new MeshLink() {
			@Override
			public String getId() {
				return "ble";
			}

			@Override
			public void broadcast(byte[] frame) {
				relayExcept.add("ALL");
			}

			@Override
			public void broadcast(byte[] frame,
					@javax.annotation.Nullable String exceptPeerId) {
				relayExcept.add(exceptPeerId == null ? "ALL" : exceptPeerId);
			}
		});
		byte[] id = new byte[MeshFrame.MESSAGE_ID_BYTES];
		id[0] = 9;
		byte[] frame = new MeshFrame(3, id, "hop".getBytes()).encode();
		node.onReceive(frame, "ble", "c:AA:BB");
		assertEquals(1, cb.delivered.size());
		assertEquals(1, relayExcept.size());
		assertEquals("c:AA:BB", relayExcept.get(0));
	}

	@Test
	public void storeCarryForwardReachesALateNeighbour() {
		MeshForwarder a = new MeshForwarder(p -> {}, random);
		byte[] payload = "carried later".getBytes();
		a.originate(payload);
		// A late node connects after the flood; it still receives the payload.
		Collector cbD = new Collector();
		MeshForwarder d = new MeshForwarder(cbD, random);
		connect(a, "a-d", d, "d-a");
		assertEquals(1, cbD.delivered.size());
		assertArrayEquals(payload, cbD.delivered.get(0));
	}
}
