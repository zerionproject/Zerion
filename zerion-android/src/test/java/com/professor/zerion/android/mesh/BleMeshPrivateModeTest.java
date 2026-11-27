package com.professor.zerion.android.mesh;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import org.zerionproject.transport.mesh.MeshForwarder;
import org.zerionproject.transport.mesh.MeshFrame;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class BleMeshPrivateModeTest {

	private static final UUID GATT_SERVICE_UUID =
			UUID.fromString("9f2a7c14-6b38-4d0e-8a51-c3e7d924b6f8");
	private static final int CHANNEL = 0x91;

	private final class Node {

		private final BluetoothManager manager = mock(BluetoothManager.class);
		private final BluetoothAdapter adapter = mock(BluetoothAdapter.class);
		private final BluetoothLeAdvertiser advertiser =
				mock(BluetoothLeAdvertiser.class);
		private final BluetoothLeScanner scanner =
				mock(BluetoothLeScanner.class);
		private final MeshForwarder forwarder = new MeshForwarder(payload -> {
		}, new SecureRandom());
		private final BleMeshTransport transport;

		private Node(byte[] advertKey, List<MeshPeer> known) throws Exception {
			Context context = RuntimeEnvironment.getApplication();
			BluetoothServerSocket server = mock(BluetoothServerSocket.class);
			CountDownLatch closed = new CountDownLatch(1);
			when(server.getPsm()).thenReturn(CHANNEL);
			when(server.accept()).thenAnswer(invocation -> {
				closed.await();
				throw new java.io.IOException();
			});
			org.mockito.Mockito.doAnswer(invocation -> {
				closed.countDown();
				return null;
			}).when(server).close();
			when(manager.getAdapter()).thenReturn(adapter);
			when(adapter.isEnabled()).thenReturn(true);
			when(adapter.getBluetoothLeAdvertiser()).thenReturn(advertiser);
			when(adapter.getBluetoothLeScanner()).thenReturn(scanner);
			when(adapter.listenUsingInsecureL2capChannel()).thenReturn(server);
			transport = new BleMeshTransport(context, forwarder, null, null,
					manager);
			List<MeshPeer> snapshot = Collections.unmodifiableList(
					new ArrayList<>(known));
			transport.setPrivatePeers(new BleMeshTransport.PrivatePeers() {
				@Override
				@Nullable
				public byte[] ownAdvertKey() {
					return advertKey;
				}

				@Override
				public List<MeshPeer> peers() {
					return snapshot;
				}
			});
			transport.start();
			started();
		}

		private void started() throws Exception {
			AdvertisingSetCallback cb =
					(AdvertisingSetCallback) field("advertiseCallback");
			cb.onAdvertisingSetStarted(null, 0,
					AdvertisingSetCallback.ADVERTISE_SUCCESS);
		}

		private Object field(String name) throws Exception {
			Field f = BleMeshTransport.class.getDeclaredField(name);
			f.setAccessible(true);
			return f.get(transport);
		}

		private Object call(String name, Class<?>[] types, Object... args)
				throws Exception {
			Method m = BleMeshTransport.class.getDeclaredMethod(name, types);
			m.setAccessible(true);
			return m.invoke(transport, args);
		}

		private UUID ownAdvert() throws Exception {
			UUID advert = (UUID) field("ownAdvert");
			if (advert == null) throw new AssertionError("not advertising");
			return advert;
		}

		@SuppressWarnings("unchecked")
		private Map<String, Integer> linked() throws Exception {
			return (Map<String, Integer>) field("linkedContact");
		}

		private Map<?, ?> channels() throws Exception {
			return (Map<?, ?>) field("channels");
		}

		@Nullable
		private Object pending(String key) throws Exception {
			return ((Map<?, ?>) field("pendingLinks")).get(key);
		}

		private void frameFrom(String key, byte[] frame) throws Exception {
			call("onFrame", new Class<?>[] {String.class, byte[].class}, key,
					frame);
		}

		private void drop(String key) throws Exception {
			call("dropLink", new Class<?>[] {String.class}, key);
		}

		private String dialledIn(BluetoothSocket socket) throws Exception {
			call("onDialledIn", new Class<?>[] {BluetoothSocket.class},
					socket);
			return "s:" + socket.getRemoteDevice().getAddress();
		}

		private void seen(BluetoothDevice device, UUID service)
				throws Exception {
			ScanCallback scan = (ScanCallback) field("scanCallback");
			scan.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
					result(device, service));
			CountDownLatch matched = new CountDownLatch(1);
			((Executor) field("scanMatcher")).execute(matched::countDown);
			assertTrue(matched.await(5, TimeUnit.SECONDS));
		}

		private void await(Check check) throws Exception {
			long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (!check.holds()) {
				if (System.nanoTime() > until) {
					throw new AssertionError("timed out");
				}
				Thread.sleep(10);
			}
		}

		private void drain() throws Exception {
			CountDownLatch drained = new CountDownLatch(1);
			((Executor) field("receiveExecutor")).execute(drained::countDown);
			assertTrue(drained.await(5, TimeUnit.SECONDS));
		}
	}

	private interface Check {
		boolean holds() throws Exception;
	}

	private final SecureRandom random = new SecureRandom();
	private final byte[] ownAdvertKey = bytes(32);
	private final MeshPeer friend =
			new MeshPeer(7, bytes(32), bytes(32), true);
	private final MeshPeer other =
			new MeshPeer(8, bytes(32), bytes(32), false);
	private final List<Node> nodes = new ArrayList<>();
	private Node node;

	@Before
	public void setUp() throws Exception {
		node = node(ownAdvertKey, friend, other);
	}

	@After
	public void tearDown() {
		for (Node n : nodes) n.transport.stop();
	}

	private Node node(byte[] advertKey, MeshPeer... known) throws Exception {
		List<MeshPeer> list = new ArrayList<>();
		Collections.addAll(list, known);
		Node n = new Node(advertKey, list);
		nodes.add(n);
		return n;
	}

	@Test
	public void theAdvertCarriesNothingButAValueOnlyContactsRecognise()
			throws Exception {
		ArgumentCaptor<AdvertiseData> data =
				ArgumentCaptor.forClass(AdvertiseData.class);
		verify(node.advertiser).startAdvertisingSet(
				any(AdvertisingSetParameters.class), data.capture(),
				isNull(), isNull(), isNull(),
				any(AdvertisingSetCallback.class));
		AdvertiseData d = data.getValue();
		assertFalse(d.getIncludeDeviceName());
		assertEquals(0, d.getManufacturerSpecificData().size());
		assertEquals(0, d.getServiceData().size());
		assertEquals(1, d.getServiceUuids().size());
		UUID advert = d.getServiceUuids().get(0).getUuid();
		assertTrue(MeshPrivateLink.recognises(ownAdvertKey, advert, epoch()));
		assertFalse(MeshPrivateLink.recognises(friend.advertKey, advert,
				epoch()));
		assertEquals("a contact must find the channel in the advert",
				CHANNEL, MeshPrivateLink.channelOf(ownAdvertKey, advert));
		for (long e = MeshDiscovery.currentEpoch() - 3;
				e <= MeshDiscovery.currentEpoch() + 3; e++) {
			assertNotEquals("the advert is the identifier every open mesh"
							+ " device shares",
					MeshDiscovery.discoveryUuid(e, GATT_SERVICE_UUID),
					advert);
		}
		assertNotEquals(GATT_SERVICE_UUID, advert);
	}

	@Test
	public void noServiceThatAConnectingDeviceCouldReadIsOffered() {
		verify(node.manager, never()).openGattServer(any(Context.class),
				any(BluetoothGattServerCallback.class));
	}

	@Test
	public void theScanAsksForAnyRandomIdentifierNotForTheMeshOne() {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ScanFilter>> filters =
				ArgumentCaptor.forClass(List.class);
		verify(node.scanner).startScan(filters.capture(),
				any(ScanSettings.class), any(ScanCallback.class));
		assertEquals(1, filters.getValue().size());
		ScanFilter f = filters.getValue().get(0);
		assertEquals(new ParcelUuid(MeshPrivateLink.RANDOM_UUID_BITS),
				f.getServiceUuid());
		assertEquals(new ParcelUuid(MeshPrivateLink.RANDOM_UUID_MASK),
				f.getServiceUuidMask());
		assertNull(f.getManufacturerData());
	}

	@Test
	public void onlyAContactsAdvertIsDialledOnItsChannelAndOnlyOnce()
			throws Exception {
		BluetoothDevice stranger = device("AA:00:00:00:00:01");
		node.seen(stranger, UUID.randomUUID());
		node.seen(stranger, MeshDiscovery.discoveryUuid(
				MeshDiscovery.currentEpoch(), GATT_SERVICE_UUID));
		node.seen(stranger, advertOf(friend, epoch() - 10));
		Thread.sleep(100);
		verify(stranger, never()).createInsecureL2capChannel(anyInt());

		BluetoothDevice near = device("AA:00:00:00:00:02");
		BluetoothSocket opened = socket(near, new ByteArrayOutputStream());
		when(near.createInsecureL2capChannel(anyInt())).thenReturn(opened);
		UUID advert = MeshPrivateLink.advert(friend.advertKey, random,
				epoch(), 0xA3);
		node.seen(near, advert);
		node.await(() -> node.channels().containsKey("c:AA:00:00:00:00:02"));
		node.seen(near, advert);
		verify(near, times(1)).createInsecureL2capChannel(0xA3);
	}

	@Test
	public void anAdvertWhoseDialProvedNothingIsNotDialledAgain()
			throws Exception {
		UUID advert = advertOf(friend, epoch());
		String key = dial("AA:00:00:00:00:05", advert);

		node.drop(key);
		BluetoothDevice elsewhere = device("AA:00:00:00:00:06");
		node.seen(elsewhere, advert);
		Thread.sleep(100);

		verify(elsewhere, never()).createInsecureL2capChannel(anyInt());

		dial("AA:00:00:00:00:07", advertOf(friend, epoch()));
	}

	@Test
	public void anAdvertTwoContactsClaimIsTriedWithEachOfThem()
			throws Exception {
		MeshPeer impostor = new MeshPeer(5, friend.advertKey, bytes(32),
				true);
		Node n = node(bytes(32), impostor, friend);
		UUID advert = advertOf(friend, epoch());
		BluetoothDevice near = device("AA:00:00:00:00:08");
		BluetoothSocket first = socket(near, new ByteArrayOutputStream());
		BluetoothSocket second = socket(near, new ByteArrayOutputStream());
		when(near.createInsecureL2capChannel(anyInt()))
				.thenReturn(first, second);
		String key = "c:AA:00:00:00:00:08";
		n.seen(near, advert);
		n.await(() -> n.channels().containsKey(key));
		assertEquals(impostor, pendingField(n.pending(key), "peer"));
		n.drop(key);
		ShadowSystemClock.advanceBy(Duration.ofSeconds(5));

		n.seen(near, advert);
		n.await(() -> n.channels().containsKey(key));

		assertEquals("the contact that really holds the key was not tried",
				friend, pendingField(n.pending(key), "peer"));
	}

	@Test
	public void aContactThatDialsInIsLinkedAfterTheHandshake()
			throws Exception {
		String key = dialledIn("BB:00:00:00:00:01");
		byte[] dialerNonce = bytes(16);

		node.frameFrom(key, MeshPrivateLink.hello(friend.linkKey,
				node.ownAdvert(), dialerNonce));

		Object pending = node.pending(key);
		assertNotNull("a contact's hello was not taken", pending);
		assertFalse(node.linked().containsKey(key));
		byte[] answererNonce = (byte[]) pendingField(pending,
				"answererNonce");
		node.frameFrom(key, MeshPrivateLink.confirm(friend.linkKey,
				dialerNonce, bytes(16)));
		assertFalse("a confirm for another session linked the contact",
				node.linked().containsKey(key));

		node.frameFrom(key, MeshPrivateLink.confirm(friend.linkKey,
				dialerNonce, answererNonce));

		assertEquals(Integer.valueOf(friend.contact),
				node.linked().get(key));
		assertEquals(1, node.transport.getPeerCount());
	}

	@Test
	public void aHelloFromSomeoneWhoIsNoContactIsNotAnswered()
			throws Exception {
		String key = dialledIn("BB:00:00:00:00:02");

		node.frameFrom(key, MeshPrivateLink.hello(bytes(32),
				node.ownAdvert(), bytes(16)));

		assertNull(node.pending(key));
		assertFalse(node.linked().containsKey(key));
	}

	@Test
	public void aHelloRecordedUnderAnEarlierAdvertIsNotAnswered()
			throws Exception {
		String key = dialledIn("BB:00:00:00:00:03");
		UUID earlier = MeshPrivateLink.advert(ownAdvertKey, random, epoch(),
				CHANNEL);

		node.frameFrom(key, MeshPrivateLink.hello(friend.linkKey, earlier,
				bytes(16)));

		assertNull("a replayed hello showed that this device knows the"
				+ " contact", node.pending(key));
	}

	@Test
	public void theAdvertBeforeTheCurrentOneIsAnsweredForHalfAMinuteOnly()
			throws Exception {
		UUID first = node.ownAdvert();
		node.call("startAdvertising",
				new Class<?>[] {BluetoothAdapter.class, byte[].class},
				node.adapter, new byte[8]);
		node.started();
		assertNotEquals(first, node.ownAdvert());

		String soon = dialledIn("BB:00:00:00:00:06");
		node.frameFrom(soon, MeshPrivateLink.hello(friend.linkKey, first,
				bytes(16)));
		assertNotNull("a dial that began under the advert just replaced was"
				+ " refused", node.pending(soon));

		ShadowSystemClock.advanceBy(Duration.ofMillis(
				BleMeshTransport.PREVIOUS_ADVERT_MS + 1));
		String later = dialledIn("BB:00:00:00:00:07");
		node.frameFrom(later, MeshPrivateLink.hello(friend.linkKey, first,
				bytes(16)));
		assertNull(node.pending(later));
	}

	@Test
	public void aStrangerThatWritesAnythingReadsNothingBack()
			throws Exception {
		ByteArrayOutputStream written = new ByteArrayOutputStream();
		String key = node.dialledIn(socket(device("BB:00:00:00:00:08"),
				written));

		node.frameFrom(key, frame());
		node.frameFrom(key, new byte[] {0});
		node.frameFrom(key, new byte[] {0x7F, 0x05});
		node.frameFrom(key, MeshPrivateLink.hello(bytes(32),
				node.ownAdvert(), bytes(16)));
		node.frameFrom(key, MeshPrivateLink.confirm(friend.linkKey,
				bytes(16), bytes(16)));
		CountDownLatch sent = new CountDownLatch(1);
		((Executor) node.field("sendExecutor")).execute(sent::countDown);
		assertTrue(sent.await(5, TimeUnit.SECONDS));

		assertEquals("a connection that proved nothing was sent something"
				+ " that marks the app", 0, written.size());
		assertFalse(node.linked().containsKey(key));
	}

	@Test
	public void aDialThatCannotConnectClosesItsSocketAndMayBeTriedAgain()
			throws Exception {
		BluetoothDevice near = device("CC:00:00:00:00:07");
		BluetoothSocket failing = socket(near, new ByteArrayOutputStream());
		org.mockito.Mockito.doThrow(new java.io.IOException())
				.when(failing).connect();
		BluetoothSocket working = socket(near, new ByteArrayOutputStream());
		when(near.createInsecureL2capChannel(anyInt()))
				.thenReturn(failing, working);
		UUID advert = advertOf(friend, epoch());
		String key = "c:CC:00:00:00:00:07";

		node.seen(near, advert);
		node.await(() -> node.pending(key) == null);
		verify(failing, atLeastOnce()).close();
		assertFalse(node.channels().containsKey(key));
		ShadowSystemClock.advanceBy(Duration.ofSeconds(5));

		node.seen(near, advert);

		node.await(() -> node.channels().containsKey(key));
	}

	@Test
	public void aDroppedLinkClosesItsChannel() throws Exception {
		BluetoothSocket[] opened = new BluetoothSocket[1];
		String key = dialAndLink("CC:00:00:00:00:05", opened);

		node.drop(key);

		assertFalse(node.linked().containsKey(key));
		assertFalse(node.channels().containsKey(key));
		verify(opened[0], atLeastOnce()).close();
	}

	@Test
	public void strangersWaitingForProofDoNotPushOutLinkedContacts()
			throws Exception {
		String proved = dialledIn("BB:00:00:00:00:10");
		byte[] dialerNonce = bytes(16);
		node.frameFrom(proved, MeshPrivateLink.hello(friend.linkKey,
				node.ownAdvert(), dialerNonce));
		node.frameFrom(proved, MeshPrivateLink.confirm(friend.linkKey,
				dialerNonce, (byte[]) pendingField(node.pending(proved),
						"answererNonce")));
		assertTrue(node.linked().containsKey(proved));
		ShadowSystemClock.advanceBy(Duration.ofMinutes(30));

		for (int i = 0; i < 12; i++) {
			dialledIn("EE:00:00:00:00:" + (10 + i));
		}

		assertTrue("an unproved newcomer took a linked contact's place",
				node.linked().containsKey(proved));
		assertTrue(node.channels().containsKey(proved));
		assertEquals(1 + BleMeshTransport.MAX_UNPROVED_CENTRALS,
				node.channels().size());
	}

	@Test
	public void framesFromALinkThatHasNotProvedAContactAreDropped()
			throws Exception {
		String stranger = dialledIn("BB:00:00:00:00:04");
		byte[] unproved = frame();

		node.frameFrom(stranger, unproved);
		node.drain();

		assertTrue("a frame from an unproved link reached the forwarder",
				node.forwarder.onReceive(unproved, "test", "fresh"));

		String key = dialledIn("BB:00:00:00:00:05");
		byte[] dialerNonce = bytes(16);
		node.frameFrom(key, MeshPrivateLink.hello(friend.linkKey,
				node.ownAdvert(), dialerNonce));
		node.frameFrom(key, MeshPrivateLink.confirm(friend.linkKey,
				dialerNonce, (byte[]) pendingField(node.pending(key),
						"answererNonce")));
		byte[] proved = frame();

		node.frameFrom(key, proved);
		node.drain();

		assertFalse("a frame from a linked contact did not reach the"
				+ " forwarder", node.forwarder.onReceive(proved, "test",
				"fresh"));
	}

	@Test
	public void nothingButTheHandshakeIsWrittenToALinkWithoutProof()
			throws Exception {
		ByteArrayOutputStream written = new ByteArrayOutputStream();
		BluetoothDevice remote = device("BB:00:00:00:00:20");
		node.dialledIn(socket(remote, written));

		node.transport.broadcast(frame());
		CountDownLatch sent = new CountDownLatch(1);
		((Executor) node.field("sendExecutor")).execute(sent::countDown);
		assertTrue(sent.await(5, TimeUnit.SECONDS));

		assertEquals("a mesh frame was sent to a link that proved nothing",
				0, written.size());
	}

	@Test
	public void aDialledContactIsLinkedOnlyAfterItsReplyProvesTheLinkKey()
			throws Exception {
		String key = dial("CC:00:00:00:00:01", advertOf(friend, epoch()));

		byte[] dialerNonce = (byte[]) pendingField(node.pending(key),
				"dialerNonce");
		assertNotNull(dialerNonce);
		assertFalse(node.linked().containsKey(key));
		node.frameFrom(key, MeshPrivateLink.reply(other.linkKey, dialerNonce,
				bytes(16)));
		assertFalse("a reply under another key linked the device",
				node.linked().containsKey(key));

		node.frameFrom(key, MeshPrivateLink.reply(friend.linkKey,
				dialerNonce, bytes(16)));

		assertEquals(Integer.valueOf(friend.contact),
				node.linked().get(key));
	}

	@Test
	public void ofTwoLinksMadeTogetherTheAgreedOneIsKept() throws Exception {
		String outbound = dial("DD:00:00:00:00:02",
				advertOf(friend, epoch()));
		String inbound = dialledIn("DD:00:00:00:00:01");
		byte[] dialerNonce = bytes(16);
		node.frameFrom(inbound, MeshPrivateLink.hello(friend.linkKey,
				node.ownAdvert(), dialerNonce));
		node.frameFrom(inbound, MeshPrivateLink.confirm(friend.linkKey,
				dialerNonce, (byte[]) pendingField(node.pending(inbound),
						"answererNonce")));
		assertTrue(node.linked().containsKey(inbound));

		node.frameFrom(outbound, MeshPrivateLink.reply(friend.linkKey,
				(byte[]) pendingField(node.pending(outbound), "dialerNonce"),
				bytes(16)));

		assertTrue("this side keeps the link it dialled",
				node.linked().containsKey(outbound));
		assertFalse(node.linked().containsKey(inbound));
		assertEquals(1, node.transport.getPeerCount());
	}

	@Test
	public void aFreshLinkReplacesAnOldOneWhoeverDialled() throws Exception {
		String outbound = dialAndLink("DD:00:00:00:00:03",
				new BluetoothSocket[1]);
		ShadowSystemClock.advanceBy(Duration.ofMillis(
				BleMeshTransport.DUPLICATE_WINDOW_MS + 1));

		String inbound = dialledIn("DD:00:00:00:00:04");
		byte[] dialerNonce = bytes(16);
		node.frameFrom(inbound, MeshPrivateLink.hello(friend.linkKey,
				node.ownAdvert(), dialerNonce));
		node.frameFrom(inbound, MeshPrivateLink.confirm(friend.linkKey,
				dialerNonce, (byte[]) pendingField(node.pending(inbound),
						"answererNonce")));

		assertTrue("the link the contact just proved was dropped for an old"
				+ " one that may be dead",
				node.linked().containsKey(inbound));
		assertFalse(node.linked().containsKey(outbound));
	}

	@Test
	public void twoContactsLinkOverTheChannelAndPassAFrame() throws Exception {
		byte[] keyA = bytes(32);
		byte[] keyB = bytes(32);
		byte[] linkKey = bytes(32);
		Node a = node(keyA, new MeshPeer(1, keyB, linkKey, true));
		Node b = node(keyB, new MeshPeer(2, keyA, linkKey, false));
		PipedInputStream atB = new PipedInputStream(1 << 16);
		PipedOutputStream fromA = new PipedOutputStream(atB);
		PipedInputStream atA = new PipedInputStream(1 << 16);
		PipedOutputStream fromB = new PipedOutputStream(atA);
		BluetoothDevice deviceB = device("B0:00:00:00:00:01");
		BluetoothDevice deviceA = device("A0:00:00:00:00:01");
		BluetoothSocket socketAtA = socket(deviceB, atA, fromA);
		BluetoothSocket socketAtB = socket(deviceA, atB, fromB);
		when(deviceB.createInsecureL2capChannel(CHANNEL))
				.thenReturn(socketAtA);

		b.dialledIn(socketAtB);
		a.seen(deviceB, b.ownAdvert());

		a.await(() -> a.linked().containsKey("c:B0:00:00:00:00:01"));
		b.await(() -> b.linked().containsKey("s:A0:00:00:00:00:01"));
		assertEquals(1, a.transport.getPeerCount());
		assertEquals(1, b.transport.getPeerCount());

		byte[] sent = frame();
		a.transport.broadcast(sent);

		b.await(() -> {
			b.drain();
			return !b.forwarder.onReceive(sent, "test", "fresh");
		});
	}

	private String dialledIn(String address) throws Exception {
		return node.dialledIn(socket(device(address),
				new ByteArrayOutputStream()));
	}

	private String dial(String address, UUID advert) throws Exception {
		BluetoothDevice near = device(address);
		BluetoothSocket opened = socket(near, new ByteArrayOutputStream());
		when(near.createInsecureL2capChannel(anyInt())).thenReturn(opened);
		node.seen(near, advert);
		String key = "c:" + address;
		node.await(() -> node.channels().containsKey(key)
				&& pendingField(node.pending(key), "dialerNonce") != null);
		return key;
	}

	private String dialAndLink(String address, BluetoothSocket[] opened)
			throws Exception {
		BluetoothDevice near = device(address);
		BluetoothSocket socket = socket(near, new ByteArrayOutputStream());
		opened[0] = socket;
		when(near.createInsecureL2capChannel(anyInt())).thenReturn(socket);
		node.seen(near, advertOf(friend, epoch()));
		String key = "c:" + address;
		node.await(() -> node.channels().containsKey(key)
				&& pendingField(node.pending(key), "dialerNonce") != null);
		node.frameFrom(key, MeshPrivateLink.reply(friend.linkKey,
				(byte[]) pendingField(node.pending(key), "dialerNonce"),
				bytes(16)));
		assertTrue(node.linked().containsKey(key));
		return key;
	}

	private UUID advertOf(MeshPeer peer, long epoch) {
		return MeshPrivateLink.advert(peer.advertKey, random, epoch, CHANNEL);
	}

	private BluetoothDevice device(String address) {
		BluetoothDevice device = mock(BluetoothDevice.class);
		when(device.getAddress()).thenReturn(address);
		return device;
	}

	private BluetoothSocket socket(BluetoothDevice remote, OutputStream out)
			throws Exception {
		return socket(remote, new PipedInputStream(new PipedOutputStream()),
				out);
	}

	private BluetoothSocket socket(BluetoothDevice remote, InputStream in,
			OutputStream out) throws Exception {
		BluetoothSocket socket = mock(BluetoothSocket.class);
		when(socket.getRemoteDevice()).thenReturn(remote);
		when(socket.getInputStream()).thenReturn(in);
		when(socket.getOutputStream()).thenReturn(out);
		return socket;
	}

	private byte[] frame() {
		byte[] id = new byte[MeshFrame.MESSAGE_ID_BYTES];
		random.nextBytes(id);
		return new MeshFrame(3, id, new byte[100]).encode();
	}

	private static Object pendingField(@Nullable Object pending, String name)
			throws Exception {
		if (pending == null) throw new AssertionError("no handshake");
		Field f = pending.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(pending);
	}

	private static ScanResult result(BluetoothDevice device, UUID service)
			throws Exception {
		byte[] raw = new byte[18];
		raw[0] = 17;
		raw[1] = 0x07;
		ByteBuffer.wrap(raw, 2, 16)
				.order(java.nio.ByteOrder.LITTLE_ENDIAN)
				.putLong(service.getLeastSignificantBits())
				.putLong(service.getMostSignificantBits());
		Method parse = ScanRecord.class.getDeclaredMethod("parseFromBytes",
				byte[].class);
		parse.setAccessible(true);
		ScanRecord record = (ScanRecord) parse.invoke(null, (Object) raw);
		return new ScanResult(device, record, -50, 0L);
	}

	private static long epoch() {
		return MeshPrivateLink.currentEpoch();
	}

	private byte[] bytes(int n) {
		byte[] b = new byte[n];
		new SecureRandom().nextBytes(b);
		return b;
	}
}
