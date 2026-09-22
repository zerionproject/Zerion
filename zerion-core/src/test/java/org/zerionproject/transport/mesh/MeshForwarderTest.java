package org.zerionproject.transport.mesh;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

	private static byte[] frameWithId(int id, String text) {
		byte[] messageId = new byte[MeshFrame.MESSAGE_ID_BYTES];
		messageId[0] = (byte) (id >>> 24);
		messageId[1] = (byte) (id >>> 16);
		messageId[2] = (byte) (id >>> 8);
		messageId[3] = (byte) id;
		return new MeshFrame(3, messageId, text.getBytes()).encode();
	}

	/**
	 * NET-11: a neighbour flooding fresh frame ids can neither flush the
	 * duplicate set (its share is capped and nothing leaves before it has
	 * expired) nor crowd out another neighbour's frames.
	 */
	@Test
	public void floodFromOnePeerCannotFlushAnotherPeersFrameId() {
		Collector cb = new Collector();
		MeshForwarder node = new MeshForwarder(cb, random);
		AtomicLong now = new AtomicLong(1_000_000L);
		node.clock = now::get;
		byte[] fromB = frameWithId(1, "from b");
		node.onReceive(fromB, "ble", "b");
		assertEquals(1, cb.delivered.size());
		int flood = MeshForwarder.SEEN_PER_PEER_CAP + 500;
		for (int i = 0; i < flood; i++) {
			now.addAndGet(25);
			node.onReceive(frameWithId(1000 + i, "junk " + i), "ble", "a");
		}
		assertEquals("only the flooding peer's share is admitted",
				1 + MeshForwarder.SEEN_PER_PEER_CAP, cb.delivered.size());
		node.onReceive(fromB, "ble", "b");
		assertEquals("b's frame is still a duplicate", 1
				+ MeshForwarder.SEEN_PER_PEER_CAP, cb.delivered.size());
		node.onReceive(frameWithId(2, "new from c"), "ble", "c");
		assertEquals("another peer's fresh frame is admitted", 2
				+ MeshForwarder.SEEN_PER_PEER_CAP, cb.delivered.size());
	}

	@Test
	public void perPeerRateLimitDropsOnlyThatPeer() {
		Collector cb = new Collector();
		MeshForwarder node = new MeshForwarder(cb, random);
		AtomicLong now = new AtomicLong(1_000_000L);
		node.clock = now::get;
		for (int i = 0; i < MeshForwarder.MAX_FRAMES_PER_SEC_PER_PEER + 20;
				i++) {
			node.onReceive(frameWithId(100 + i, "burst"), "ble", "a");
		}
		assertEquals(MeshForwarder.MAX_FRAMES_PER_SEC_PER_PEER,
				cb.delivered.size());
		node.onReceive(frameWithId(7, "from b"), "ble", "b");
		assertEquals(MeshForwarder.MAX_FRAMES_PER_SEC_PER_PEER + 1,
				cb.delivered.size());
	}

	@Test
	public void entriesLeaveOnlyOnceExpired() {
		Collector cb = new Collector();
		MeshForwarder node = new MeshForwarder(cb, random);
		AtomicLong now = new AtomicLong(1_000_000L);
		node.clock = now::get;
		byte[] frame = frameWithId(5, "once");
		node.onReceive(frame, "ble", "a");
		now.addAndGet(MeshForwarder.SEEN_TTL_MS - 1);
		node.onReceive(frame, "ble", "a");
		assertEquals(1, cb.delivered.size());
		now.addAndGet(1);
		node.onReceive(frame, "ble", "a");
		assertEquals("forgotten only after the ttl", 2, cb.delivered.size());
	}

	@Test
	public void ownFramesAreAdmittedWhenTheSetIsFullAndForeignOnesAreNot() {
		Collector cb = new Collector();
		MeshForwarder node = new MeshForwarder(cb, random);
		AtomicLong now = new AtomicLong(1_000_000L);
		node.clock = now::get;
		AtomicInteger broadcasts = new AtomicInteger();
		node.addLink(new MeshLink() {
			@Override
			public String getId() {
				return "out";
			}

			@Override
			public void broadcast(byte[] frame) {
				broadcasts.incrementAndGet();
			}
		});
		int peers = MeshForwarder.SEEN_CAP / MeshForwarder.SEEN_PER_PEER_CAP;
		int id = 10_000;
		for (int p = 0; p < peers; p++) {
			for (int i = 0; i < MeshForwarder.SEEN_PER_PEER_CAP; i++) {
				now.addAndGet(25);
				node.onReceive(frameWithId(id++, "fill"), "ble", "p" + p);
			}
		}
		assertEquals(MeshForwarder.SEEN_CAP, cb.delivered.size());
		int before = broadcasts.get();
		node.originate("mine".getBytes());
		assertEquals("own frame relayed at the cap", before + 1,
				broadcasts.get());
		now.addAndGet(25);
		node.onReceive(frameWithId(id++, "late"), "ble", "late-peer");
		assertEquals("A2-NET-06: a neighbour holding fewer entries is admitted"
				+ " at the expense of the heaviest holder",
				MeshForwarder.SEEN_CAP + 1, cb.delivered.size());
	}

	/**
	 * A2-NET-06: a flood spread over many invented identities fills the set
	 * but cannot shut out a real neighbour: its frame is admitted by evicting
	 * the oldest entry of whichever identity holds the most.
	 */
	@Test
	public void aFloodOverManyIdentitiesCannotShutOutANeighbour() {
		Collector cb = new Collector();
		MeshForwarder node = new MeshForwarder(cb, random);
		AtomicLong now = new AtomicLong(1_000_000L);
		node.clock = now::get;
		node.addLink(new MeshLink() {
			@Override
			public String getId() {
				return "out";
			}

			@Override
			public void broadcast(byte[] frame) {
			}
		});
		int identities = 64;
		int perIdentity = MeshForwarder.SEEN_CAP / identities;
		int id = 50_000;
		for (int p = 0; p < identities; p++) {
			for (int i = 0; i < perIdentity; i++) {
				now.addAndGet(30);
				node.onReceive(frameWithId(id++, "flood"), "ble", "fake" + p);
			}
		}
		assertEquals(MeshForwarder.SEEN_CAP, cb.delivered.size());
		for (int i = 0; i < 20; i++) {
			now.addAndGet(30);
			node.onReceive(frameWithId(id++, "real " + i), "ble", "neighbour");
		}
		assertEquals("every real frame got in", MeshForwarder.SEEN_CAP + 20,
				cb.delivered.size());
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
