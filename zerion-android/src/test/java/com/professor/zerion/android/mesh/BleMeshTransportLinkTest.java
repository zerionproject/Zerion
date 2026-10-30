package com.professor.zerion.android.mesh;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;

import org.zerionproject.transport.mesh.MeshForwarder;
import org.zerionproject.transport.mesh.MeshFrame;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class BleMeshTransportLinkTest {

	private static final long SHORTEST_ADDRESS_LIFETIME_MS = 7 * 60_000L;

	private Context context;
	private BleMeshTransport transport;

	@Before
	public void setUp() {
		context = RuntimeEnvironment.getApplication();
		MeshForwarder forwarder =
				new MeshForwarder(payload -> {
				}, new SecureRandom());
		transport = new BleMeshTransport(context, forwarder, null, null);
	}

	@After
	public void tearDown() {
		transport.stop();
	}

	@Test
	public void everyAdvertisingSetIsReplacedBeforeItsAddressCouldChange()
			throws Exception {
		transport.start();
		ScheduledThreadPoolExecutor rotator =
				(ScheduledThreadPoolExecutor) field("nonceRotator");
		long longest = 0;
		for (Runnable task : rotator.getQueue()) {
			longest = Math.max(longest,
					((Delayed) task).getDelay(TimeUnit.MILLISECONDS));
		}
		assertTrue("longest scheduled interval " + longest + " ms",
				longest < SHORTEST_ADDRESS_LIFETIME_MS);
	}

	@Test
	public void aSilentAdvertiserGivesUpItsSlotToANewPeer() throws Exception {
		byte[] ours = new byte[8];
		Arrays.fill(ours, (byte) 0xFF);
		setField("sessionNonce", ours);
		ScanCallback scan = (ScanCallback) field("scanCallback");
		for (int i = 1; i <= 6; i++) {
			byte[] low = new byte[8];
			low[7] = (byte) i;
			scan.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
					result("AA:00:00:00:00:0" + i, low));
		}
		@SuppressWarnings("unchecked")
		Map<String, ?> clients = (Map<String, ?>) field("connectedClients");
		assertEquals(6, clients.size());
		ShadowSystemClock.advanceBy(Duration.ofMinutes(10));
		byte[] peer = new byte[8];
		peer[0] = (byte) 0x80;
		scan.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
				result("BB:00:00:00:00:01", peer));
		assertTrue("the new peer was not dialled",
				clients.containsKey("BB:00:00:00:00:01"));
		assertEquals(6, clients.size());
	}

	@Test
	public void relayedFramesThisNodeCannotOpenDoNotKeepALinkUseful()
			throws Exception {
		String key = "s:AA:00:00:00:00:03";
		MeshLinkPolicy policy = (MeshLinkPolicy) field("policy");
		policy.onLinkUp(key, android.os.SystemClock.elapsedRealtime());
		ShadowSystemClock.advanceBy(Duration.ofMillis(
				MeshLinkPolicy.NEW_LINK_GRACE_MS + 10_000L));
		Method onBytes = BleMeshTransport.class.getDeclaredMethod("onBytes",
				String.class, byte[].class);
		onBytes.setAccessible(true);
		SecureRandom random = new SecureRandom();
		for (int i = 0; i < 3; i++) {
			byte[] id = new byte[MeshFrame.MESSAGE_ID_BYTES];
			random.nextBytes(id);
			byte[] frame = new MeshFrame(3, id, new byte[100]).encode();
			onBytes.invoke(transport, key, withLengthPrefix(frame));
		}
		CountDownLatch drained = new CountDownLatch(1);
		((Executor) field("receiveExecutor")).execute(drained::countDown);
		assertTrue(drained.await(5, TimeUnit.SECONDS));
		assertEquals("novel but unopened frames kept the slot", key,
				policy.idleLinkToEvict(Collections.singleton(key),
						android.os.SystemClock.elapsedRealtime()));
	}

	@Test
	public void aFrameOpenedForThisNodeKeepsItsLinkUseful() throws Exception {
		String key = "s:AA:00:00:00:00:04";
		MeshLinkPolicy policy = (MeshLinkPolicy) field("policy");
		policy.onLinkUp(key, android.os.SystemClock.elapsedRealtime());
		ShadowSystemClock.advanceBy(Duration.ofMillis(
				MeshLinkPolicy.NEW_LINK_GRACE_MS + 10_000L));
		transport.onOpenedFrom(key);
		assertEquals(null, policy.idleLinkToEvict(
				Collections.singleton(key),
				android.os.SystemClock.elapsedRealtime()));
	}

	private static byte[] withLengthPrefix(byte[] frame) {
		byte[] out = new byte[4 + frame.length];
		out[0] = (byte) (frame.length >>> 24);
		out[1] = (byte) (frame.length >>> 16);
		out[2] = (byte) (frame.length >>> 8);
		out[3] = (byte) frame.length;
		System.arraycopy(frame, 0, out, 4, frame.length);
		return out;
	}

	private ScanResult result(String address, byte[] nonce) throws Exception {
		BluetoothAdapter adapter = ((BluetoothManager) context
				.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
		BluetoothDevice device = adapter.getRemoteDevice(address);
		byte[] raw = new byte[2 + 2 + nonce.length];
		raw[0] = (byte) (1 + 2 + nonce.length);
		raw[1] = (byte) 0xFF;
		raw[2] = (byte) 0xFF;
		raw[3] = (byte) 0xFF;
		System.arraycopy(nonce, 0, raw, 4, nonce.length);
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

	private void setField(String name, Object value) throws Exception {
		Field f = BleMeshTransport.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(transport, value);
	}
}
