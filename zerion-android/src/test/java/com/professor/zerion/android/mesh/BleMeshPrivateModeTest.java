package com.professor.zerion.android.mesh;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
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
import org.zerionproject.transport.mesh.MeshForwarder;
import org.zerionproject.transport.mesh.MeshFrame;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
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

	private final SecureRandom random = new SecureRandom();
	private final BluetoothManager manager = mock(BluetoothManager.class);
	private final BluetoothAdapter adapter = mock(BluetoothAdapter.class);
	private final BluetoothLeAdvertiser advertiser =
			mock(BluetoothLeAdvertiser.class);
	private final BluetoothLeScanner scanner = mock(BluetoothLeScanner.class);
	private final byte[] ownAdvertKey = bytes(32);
	private final List<MeshPeer> peers = new ArrayList<>();
	private final MeshPeer friend =
			new MeshPeer(7, bytes(32), bytes(32), true);
	private final MeshPeer other =
			new MeshPeer(8, bytes(32), bytes(32), false);
	private MeshForwarder forwarder;
	private BleMeshTransport transport;

	@Before
	public void setUp() throws Exception {
		Context context = RuntimeEnvironment.getApplication();
		when(manager.getAdapter()).thenReturn(adapter);
		when(adapter.isEnabled()).thenReturn(true);
		when(adapter.getBluetoothLeAdvertiser()).thenReturn(advertiser);
		when(adapter.getBluetoothLeScanner()).thenReturn(scanner);
		when(manager.openGattServer(any(Context.class),
				any(BluetoothGattServerCallback.class)))
				.thenReturn(mock(BluetoothGattServer.class));
		peers.add(friend);
		peers.add(other);
		setUpTransport();
	}

	private void setUpTransport() throws Exception {
		Context context = RuntimeEnvironment.getApplication();
		forwarder = new MeshForwarder(payload -> {
		}, new SecureRandom());
		transport = new BleMeshTransport(context, forwarder, null, null,
				manager);
		List<MeshPeer> snapshot = Collections.unmodifiableList(
				new ArrayList<>(peers));
		transport.setPrivatePeers(new BleMeshTransport.PrivatePeers() {
			@Override
			@Nullable
			public byte[] ownAdvertKey() {
				return ownAdvertKey;
			}

			@Override
			public List<MeshPeer> peers() {
				return snapshot;
			}
		});
		transport.start();
		advertisingStarted();
	}

	@After
	public void tearDown() {
		transport.stop();
	}

	@Test
	public void theAdvertCarriesNothingButAValueOnlyContactsRecognise() {
		ArgumentCaptor<AdvertiseData> data =
				ArgumentCaptor.forClass(AdvertiseData.class);
		verify(advertiser).startAdvertisingSet(
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
	public void theScanAsksForAnyRandomIdentifierNotForTheMeshOne() {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ScanFilter>> filters =
				ArgumentCaptor.forClass(List.class);
		verify(scanner).startScan(filters.capture(), any(ScanSettings.class),
				any(ScanCallback.class));
		assertEquals(1, filters.getValue().size());
		ScanFilter f = filters.getValue().get(0);
		assertEquals(new ParcelUuid(MeshPrivateLink.RANDOM_UUID_BITS),
				f.getServiceUuid());
		assertEquals(new ParcelUuid(MeshPrivateLink.RANDOM_UUID_MASK),
				f.getServiceUuidMask());
		assertNull(f.getManufacturerData());
	}

	@Test
	public void onlyAContactsAdvertIsDialledAndOnlyOnce() throws Exception {
		BluetoothDevice stranger = device("AA:00:00:00:00:01");
		seen(stranger, UUID.randomUUID());
		seen(stranger, MeshDiscovery.discoveryUuid(
				MeshDiscovery.currentEpoch(), GATT_SERVICE_UUID));
		seen(stranger, MeshPrivateLink.advert(friend.advertKey, random,
				epoch() - 10));
		verify(stranger, never()).connectGatt(any(), anyBoolean(), any(),
				anyInt());

		BluetoothDevice near = device("AA:00:00:00:00:02");
		when(near.connectGatt(any(), anyBoolean(), any(), anyInt()))
				.thenReturn(mock(BluetoothGatt.class));
		UUID advert = MeshPrivateLink.advert(friend.advertKey, random,
				epoch());
		seen(near, advert);
		seen(near, advert);
		verify(near, times(1)).connectGatt(any(), anyBoolean(), any(),
				anyInt());
	}

	@Test
	public void anAdvertWhoseDialProvedNothingIsNotDialledAgain()
			throws Exception {
		BluetoothDevice replayer = device("AA:00:00:00:00:05");
		BluetoothGatt gatt = mock(BluetoothGatt.class);
		when(gatt.getDevice()).thenReturn(replayer);
		when(replayer.connectGatt(any(), anyBoolean(), any(), anyInt()))
				.thenReturn(gatt);
		UUID advert = MeshPrivateLink.advert(friend.advertKey, random,
				epoch());
		seen(replayer, advert);
		verify(replayer, times(1)).connectGatt(any(), anyBoolean(), any(),
				anyInt());

		Method evict = BleMeshTransport.class.getDeclaredMethod(
				"evictClient", String.class);
		evict.setAccessible(true);
		evict.invoke(transport, "AA:00:00:00:00:05");
		seen(replayer, advert);
		BluetoothDevice elsewhere = device("AA:00:00:00:00:06");
		seen(elsewhere, advert);

		verify(replayer, times(1)).connectGatt(any(), anyBoolean(), any(),
				anyInt());
		verify(elsewhere, never()).connectGatt(any(), anyBoolean(), any(),
				anyInt());

		BluetoothDevice real = device("AA:00:00:00:00:07");
		when(real.connectGatt(any(), anyBoolean(), any(), anyInt()))
				.thenReturn(mock(BluetoothGatt.class));
		seen(real, MeshPrivateLink.advert(friend.advertKey, random,
				epoch()));
		verify(real, times(1)).connectGatt(any(), anyBoolean(), any(),
				anyInt());
	}

	@Test
	public void anAdvertTwoContactsClaimIsTriedWithEachOfThem()
			throws Exception {
		transport.stop();
		MeshPeer impostor = new MeshPeer(5, friend.advertKey, bytes(32),
				true);
		peers.clear();
		peers.add(impostor);
		peers.add(friend);
		setUpTransport();
		UUID advert = MeshPrivateLink.advert(friend.advertKey, random,
				epoch());
		BluetoothDevice near = device("AA:00:00:00:00:08");
		BluetoothGatt gatt = mock(BluetoothGatt.class);
		when(gatt.getDevice()).thenReturn(near);
		when(near.connectGatt(any(), anyBoolean(), any(), anyInt()))
				.thenReturn(gatt);
		seen(near, advert);
		assertEquals(impostor, pendingField(pending("c:AA:00:00:00:00:08"),
				"peer"));
		Method evict = BleMeshTransport.class.getDeclaredMethod(
				"evictClient", String.class);
		evict.setAccessible(true);
		evict.invoke(transport, "AA:00:00:00:00:08");

		seen(near, advert);

		assertEquals("the contact that really holds the key was not tried",
				friend, pendingField(pending("c:AA:00:00:00:00:08"),
						"peer"));
	}

	@Test
	public void aContactThatDialsInIsLinkedAfterTheHandshake()
			throws Exception {
		String key = central("BB:00:00:00:00:01");
		UUID advert = ownAdvert();
		byte[] dialerNonce = bytes(16);

		bytesFrom(key, MeshPrivateLink.hello(friend.linkKey, advert,
				dialerNonce));

		Object pending = pending(key);
		assertNotNull("a contact's hello was not taken", pending);
		assertFalse(linked().containsKey(key));
		byte[] answererNonce = (byte[]) pendingField(pending,
				"answererNonce");
		bytesFrom(key, MeshPrivateLink.confirm(friend.linkKey, dialerNonce,
				bytes(16)));
		assertFalse("a confirm for another session linked the contact",
				linked().containsKey(key));

		bytesFrom(key, MeshPrivateLink.confirm(friend.linkKey, dialerNonce,
				answererNonce));

		assertEquals(Integer.valueOf(friend.contact), linked().get(key));
		assertEquals(1, transport.getPeerCount());
	}

	@Test
	public void aHelloFromSomeoneWhoIsNoContactIsNotAnswered()
			throws Exception {
		String key = central("BB:00:00:00:00:02");
		UUID advert = ownAdvert();

		bytesFrom(key, MeshPrivateLink.hello(bytes(32), advert, bytes(16)));

		assertNull(pending(key));
		assertFalse(linked().containsKey(key));
	}

	@Test
	public void aHelloRecordedUnderAnEarlierAdvertIsNotAnswered()
			throws Exception {
		String key = central("BB:00:00:00:00:03");
		UUID earlier = MeshPrivateLink.advert(ownAdvertKey, random, epoch());

		bytesFrom(key, MeshPrivateLink.hello(friend.linkKey, earlier,
				bytes(16)));

		assertNull("a replayed hello showed that this device knows the"
				+ " contact", pending(key));
	}

	@Test
	public void theAdvertBeforeTheCurrentOneIsAnsweredForHalfAMinuteOnly()
			throws Exception {
		UUID first = ownAdvert();
		Method start = BleMeshTransport.class.getDeclaredMethod(
				"startAdvertising", BluetoothAdapter.class, byte[].class);
		start.setAccessible(true);
		start.invoke(transport, adapter, new byte[8]);
		advertisingStarted();
		assertNotEquals(first, ownAdvert());

		String soon = central("BB:00:00:00:00:06");
		bytesFrom(soon, MeshPrivateLink.hello(friend.linkKey, first,
				bytes(16)));
		assertNotNull("a dial that began under the advert just replaced was"
				+ " refused", pending(soon));

		org.robolectric.shadows.ShadowSystemClock.advanceBy(
				java.time.Duration.ofMillis(
						BleMeshTransport.PREVIOUS_ADVERT_MS + 1));
		String later = central("BB:00:00:00:00:07");
		bytesFrom(later, MeshPrivateLink.hello(friend.linkKey, first,
				bytes(16)));
		assertNull(pending(later));
	}

	@Test
	public void aLinkThatSendsFramesWithoutProofIsToldToStartAgainOnce()
			throws Exception {
		String key = central("BB:00:00:00:00:08");

		bytesFrom(key, frame());
		bytesFrom(key, frame());

		assertTrue(((java.util.Set<?>) field("resetsSent")).contains(key));
		assertFalse(linked().containsKey(key));
	}

	@Test
	public void aDiallerToldToStartAgainDropsItsLink() throws Exception {
		String key = dialAndLink("CC:00:00:00:00:05");
		assertTrue(linked().containsKey(key));

		bytesFrom(key, MeshPrivateLink.reset());

		assertFalse(linked().containsKey(key));
		assertFalse(((Map<?, ?>) field("connectedClients"))
				.containsKey("CC:00:00:00:00:05"));
	}

	@Test
	public void ourOwnDialIsNotCountedAsSomeoneDiallingIn() throws Exception {
		String key = dialAndLink("CC:00:00:00:00:06");
		BluetoothGattServerCallback server =
				(BluetoothGattServerCallback) field("serverCallback");

		server.onConnectionStateChange(device("CC:00:00:00:00:06"),
				BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);

		assertFalse(((Map<?, ?>) field("connectedCentrals"))
				.containsKey("CC:00:00:00:00:06"));
		assertTrue(linked().containsKey(key));
	}

	@Test
	public void strangersWaitingForProofDoNotPushOutLinkedContacts()
			throws Exception {
		String proved = central("BB:00:00:00:00:10");
		UUID advert = ownAdvert();
		byte[] dialerNonce = bytes(16);
		bytesFrom(proved, MeshPrivateLink.hello(friend.linkKey, advert,
				dialerNonce));
		bytesFrom(proved, MeshPrivateLink.confirm(friend.linkKey,
				dialerNonce, (byte[]) pendingField(pending(proved),
						"answererNonce")));
		assertTrue(linked().containsKey(proved));
		org.robolectric.shadows.ShadowSystemClock.advanceBy(
				java.time.Duration.ofMinutes(30));

		for (int i = 0; i < 12; i++) {
			central("EE:00:00:00:00:" + (10 + i));
		}

		assertTrue("an unproved newcomer took a linked contact's place",
				linked().containsKey(proved));
		Map<?, ?> centrals = (Map<?, ?>) field("connectedCentrals");
		assertTrue(centrals.containsKey("BB:00:00:00:00:10"));
		assertEquals(1 + BleMeshTransport.MAX_UNPROVED_CENTRALS,
				centrals.size());
	}

	@Test
	public void framesFromALinkThatHasNotProvedAContactAreDropped()
			throws Exception {
		String stranger = central("BB:00:00:00:00:04");
		byte[] unproved = frame();

		bytesFrom(stranger, unproved);
		drain();

		assertTrue("a frame from an unproved link reached the forwarder",
				forwarder.onReceive(unproved, "test", "fresh"));

		String key = central("BB:00:00:00:00:05");
		UUID advert = ownAdvert();
		byte[] dialerNonce = bytes(16);
		bytesFrom(key, MeshPrivateLink.hello(friend.linkKey, advert,
				dialerNonce));
		byte[] answererNonce = (byte[]) pendingField(pending(key),
				"answererNonce");
		bytesFrom(key, MeshPrivateLink.confirm(friend.linkKey, dialerNonce,
				answererNonce));
		byte[] proved = frame();

		bytesFrom(key, proved);
		drain();

		assertFalse("a frame from a linked contact did not reach the"
				+ " forwarder", forwarder.onReceive(proved, "test", "fresh"));
	}

	@Test
	public void aDialledContactIsLinkedOnlyAfterItsReplyProvesTheLinkKey()
			throws Exception {
		String key = dial("CC:00:00:00:00:01");

		byte[] dialerNonce = (byte[]) pendingField(pending(key),
				"dialerNonce");
		assertNotNull(dialerNonce);
		assertFalse(linked().containsKey(key));
		bytesFrom(key, MeshPrivateLink.reply(other.linkKey, dialerNonce,
				bytes(16)));
		assertFalse("a reply under another key linked the device",
				linked().containsKey(key));

		bytesFrom(key, MeshPrivateLink.reply(friend.linkKey, dialerNonce,
				bytes(16)));

		assertEquals(Integer.valueOf(friend.contact), linked().get(key));
	}

	@Test
	public void ofTwoLinksMadeTogetherTheAgreedOneIsKept() throws Exception {
		String outbound = dial("DD:00:00:00:00:02");
		String inbound = central("DD:00:00:00:00:01");
		UUID advert = ownAdvert();
		byte[] dialerNonce = bytes(16);
		bytesFrom(inbound, MeshPrivateLink.hello(friend.linkKey, advert,
				dialerNonce));
		bytesFrom(inbound, MeshPrivateLink.confirm(friend.linkKey,
				dialerNonce, (byte[]) pendingField(pending(inbound),
						"answererNonce")));
		assertTrue(linked().containsKey(inbound));

		bytesFrom(outbound, MeshPrivateLink.reply(friend.linkKey,
				(byte[]) pendingField(pending(outbound), "dialerNonce"),
				bytes(16)));

		assertTrue("this side keeps the link it dialled",
				linked().containsKey(outbound));
		assertFalse(linked().containsKey(inbound));
		assertEquals(1, transport.getPeerCount());
	}

	@Test
	public void aFreshLinkReplacesAnOldOneWhoeverDialled() throws Exception {
		String outbound = dialAndLink("DD:00:00:00:00:03");
		org.robolectric.shadows.ShadowSystemClock.advanceBy(
				java.time.Duration.ofMillis(
						BleMeshTransport.DUPLICATE_WINDOW_MS + 1));

		String inbound = central("DD:00:00:00:00:04");
		UUID advert = ownAdvert();
		byte[] dialerNonce = bytes(16);
		bytesFrom(inbound, MeshPrivateLink.hello(friend.linkKey, advert,
				dialerNonce));
		bytesFrom(inbound, MeshPrivateLink.confirm(friend.linkKey,
				dialerNonce, (byte[]) pendingField(pending(inbound),
						"answererNonce")));

		assertTrue("the link the contact just proved was dropped for an old"
				+ " one that may be dead", linked().containsKey(inbound));
		assertFalse(linked().containsKey(outbound));
	}

	private String dial(String address) throws Exception {
		BluetoothDevice near = device(address);
		BluetoothGatt gatt = mock(BluetoothGatt.class);
		when(gatt.getDevice()).thenReturn(near);
		when(gatt.getService(any())).thenReturn(
				mock(android.bluetooth.BluetoothGattService.class));
		when(near.connectGatt(any(), anyBoolean(), any(), anyInt()))
				.thenReturn(gatt);
		seen(near, MeshPrivateLink.advert(friend.advertKey, random,
				epoch()));
		BluetoothGattCallback client =
				(BluetoothGattCallback) field("clientCallback");
		client.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS);
		return "c:" + address;
	}

	private String dialAndLink(String address) throws Exception {
		String key = dial(address);
		byte[] dialerNonce = (byte[]) pendingField(pending(key),
				"dialerNonce");
		bytesFrom(key, MeshPrivateLink.reply(friend.linkKey, dialerNonce,
				bytes(16)));
		assertTrue(linked().containsKey(key));
		return key;
	}

	private void seen(BluetoothDevice device, UUID service) throws Exception {
		ScanCallback scan = (ScanCallback) field("scanCallback");
		scan.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
				result(device, service));
		CountDownLatch matched = new CountDownLatch(1);
		((Executor) field("scanMatcher")).execute(matched::countDown);
		assertTrue(matched.await(5, TimeUnit.SECONDS));
	}

	private UUID ownAdvert() throws Exception {
		UUID advert = (UUID) field("ownAdvert");
		if (advert == null) throw new AssertionError("not advertising");
		return advert;
	}

	private void advertisingStarted() throws Exception {
		AdvertisingSetCallback cb =
				(AdvertisingSetCallback) field("advertiseCallback");
		cb.onAdvertisingSetStarted(null, 0,
				AdvertisingSetCallback.ADVERTISE_SUCCESS);
	}

	private static long epoch() {
		return MeshPrivateLink.currentEpoch();
	}

	private String central(String address) throws Exception {
		BluetoothDevice device = device(address);
		BluetoothGattServerCallback server =
				(BluetoothGattServerCallback) field("serverCallback");
		server.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS,
				BluetoothProfile.STATE_CONNECTED);
		return "s:" + address;
	}

	private BluetoothDevice device(String address) {
		BluetoothDevice device = mock(BluetoothDevice.class);
		when(device.getAddress()).thenReturn(address);
		return device;
	}

	private void bytesFrom(String key, byte[] frame) throws Exception {
		Method onBytes = BleMeshTransport.class.getDeclaredMethod("onBytes",
				String.class, byte[].class);
		onBytes.setAccessible(true);
		byte[] out = new byte[4 + frame.length];
		ByteBuffer.wrap(out).putInt(frame.length).put(frame);
		onBytes.invoke(transport, key, out);
	}

	private byte[] frame() {
		byte[] id = new byte[MeshFrame.MESSAGE_ID_BYTES];
		random.nextBytes(id);
		return new MeshFrame(3, id, new byte[100]).encode();
	}

	private void drain() throws Exception {
		CountDownLatch drained = new CountDownLatch(1);
		((Executor) field("receiveExecutor")).execute(drained::countDown);
		assertTrue(drained.await(5, TimeUnit.SECONDS));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Integer> linked() throws Exception {
		return (Map<String, Integer>) field("linkedContact");
	}

	@Nullable
	private Object pending(String key) throws Exception {
		return ((Map<?, ?>) field("pendingLinks")).get(key);
	}

	private static Object pendingField(@Nullable Object pending, String name)
			throws Exception {
		if (pending == null) throw new AssertionError("no handshake");
		Field f = pending.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(pending);
	}

	private ScanResult result(BluetoothDevice device, UUID service)
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

	private Object field(String name) throws Exception {
		Field f = BleMeshTransport.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(transport);
	}

	private byte[] bytes(int n) {
		byte[] b = new byte[n];
		new SecureRandom().nextBytes(b);
		return b;
	}
}
