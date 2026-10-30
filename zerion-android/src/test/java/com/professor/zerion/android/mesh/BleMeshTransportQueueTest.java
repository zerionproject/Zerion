package com.professor.zerion.android.mesh;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;

import org.zerionproject.transport.mesh.MeshForwarder;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class BleMeshTransportQueueTest {

	private static final int FLOOD = 1000;
	private static final int FRAME = 16 * 1024;
	private static final int MAX_QUEUED_FRAMES = 64;
	private static final long MAX_QUEUED_BYTES = 1024 * 1024;
	private static final String RELAYED_FROM = "s:BB:00:00:00:00:09";
	private static final int OWN = 3;
	private static final int OWN_BASE = 5000;

	private final Context context = RuntimeEnvironment.getApplication();
	private final CountDownLatch busy = new CountDownLatch(1);
	private final List<Integer> handled =
			Collections.synchronizedList(new ArrayList<>());
	private BleMeshTransport transport;

	@After
	public void tearDown() {
		busy.countDown();
		if (transport != null) transport.stop();
	}

	@Test
	public void aBroadcastFloodWhileTheRadioIsBusyKeepsOnlyTheNewestFrames()
			throws Exception {
		captureSentFrames();

		blockWorker("sendExecutor");
		for (int i = 0; i < FLOOD; i++) {
			transport.broadcast(frame(i), RELAYED_FROM);
		}
		busy.countDown();

		assertOnlyTheNewestWereHandled(FRAME + 4);
	}

	@Test
	public void ownFramesAreNotPushedOutByARelayFlood() throws Exception {
		captureSentFrames();

		blockWorker("sendExecutor");
		for (int i = 0; i < OWN; i++) transport.broadcast(frame(OWN_BASE + i));
		for (int i = 0; i < FLOOD; i++) {
			transport.broadcast(frame(i), RELAYED_FROM);
		}
		busy.countDown();

		awaitLast(FLOOD - 1);
		for (int i = 0; i < OWN; i++) {
			assertTrue("own frame " + i + " was dropped",
					handled.contains(OWN_BASE + i));
		}
	}

	private void captureSentFrames() throws Exception {
		transport = new BleMeshTransport(context,
				new MeshForwarder(payload -> {
				}, new SecureRandom()), null, null);
		BluetoothGatt gatt = mock(BluetoothGatt.class);
		BluetoothGattService service = mock(BluetoothGattService.class);
		BluetoothGattCharacteristic ch =
				mock(BluetoothGattCharacteristic.class);
		when(gatt.getService(any())).thenReturn(service);
		when(service.getCharacteristic(any())).thenReturn(ch);
		doAnswer(inv -> {
			byte[] part = inv.getArgument(0);
			if (part.length >= 8) handled.add(readInt(part, 4));
			return true;
		}).when(ch).setValue(any(byte[].class));
		when(gatt.writeCharacteristic(any()))
				.thenThrow(new IllegalStateException());
		@SuppressWarnings("unchecked")
		Map<String, BluetoothGatt> clients =
				(Map<String, BluetoothGatt>) field("connectedClients");
		clients.put("AA:00:00:00:00:01", gatt);
	}

	@Test
	public void aReceiveFloodWhileTheForwarderIsBusyKeepsOnlyTheNewestFrames()
			throws Exception {
		MeshForwarder forwarder = mock(MeshForwarder.class);
		when(forwarder.onReceive(any(), nullable(String.class),
				nullable(String.class))).thenAnswer(inv -> {
			byte[] f = inv.getArgument(0);
			handled.add(readInt(f, 0));
			return false;
		});
		transport = new BleMeshTransport(context, forwarder, null, null);
		Method onBytes = BleMeshTransport.class.getDeclaredMethod("onBytes",
				String.class, byte[].class);
		onBytes.setAccessible(true);

		blockWorker("receiveExecutor");
		for (int i = 0; i < FLOOD; i++) {
			onBytes.invoke(transport, "s:AA:00:00:00:00:02",
					withLengthPrefix(frame(i)));
		}
		busy.countDown();

		assertOnlyTheNewestWereHandled(FRAME);
	}

	private void assertOnlyTheNewestWereHandled(int queuedBytesPerFrame)
			throws Exception {
		awaitLast(FLOOD - 1);
		List<Integer> seen = new ArrayList<>(handled);
		assertTrue("the queue held " + seen.size() + " frames",
				seen.size() <= MAX_QUEUED_FRAMES);
		long queuedBytes = seen.size() * (long) queuedBytesPerFrame;
		assertTrue("the queue held " + queuedBytes + " bytes",
				queuedBytes <= MAX_QUEUED_BYTES);
		assertEquals(FLOOD - 1, (int) seen.get(seen.size() - 1));
		for (int i = 1; i < seen.size(); i++) {
			assertEquals("the oldest frames are the ones dropped",
					seen.get(i - 1) + 1, (int) seen.get(i));
		}
	}

	private void awaitLast(int last) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline && (handled.isEmpty()
				|| handled.get(handled.size() - 1) != last)) {
			Thread.sleep(5);
		}
	}

	private void blockWorker(String name) throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		((Executor) field(name)).execute(() -> {
			started.countDown();
			try {
				busy.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		assertTrue(started.await(5, TimeUnit.SECONDS));
	}

	private static byte[] frame(int i) {
		byte[] f = new byte[FRAME];
		f[0] = (byte) (i >>> 24);
		f[1] = (byte) (i >>> 16);
		f[2] = (byte) (i >>> 8);
		f[3] = (byte) i;
		return f;
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

	private static int readInt(byte[] b, int off) {
		return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
				| ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
	}

	private Object field(String name) throws Exception {
		Field f = BleMeshTransport.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(transport);
	}
}
