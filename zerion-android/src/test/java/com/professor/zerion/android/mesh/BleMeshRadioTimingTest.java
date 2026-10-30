package com.professor.zerion.android.mesh;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLock;
import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.transport.mesh.MeshForwarder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class BleMeshRadioTimingTest {

	private final BluetoothManager manager = mock(BluetoothManager.class);
	private final BluetoothAdapter adapter = mock(BluetoothAdapter.class);
	private final BluetoothLeAdvertiser advertiser =
			mock(BluetoothLeAdvertiser.class);
	private final AndroidWakeLockManager wakeLocks =
			mock(AndroidWakeLockManager.class);
	private final AndroidWakeLock wakeLock = mock(AndroidWakeLock.class);
	private final TaskScheduler scheduler = mock(TaskScheduler.class);
	private Context context;
	private BleMeshTransport transport;

	@Before
	public void setUp() {
		context = RuntimeEnvironment.getApplication();
		when(manager.getAdapter()).thenReturn(adapter);
		when(adapter.isEnabled()).thenReturn(true);
		when(adapter.getBluetoothLeAdvertiser()).thenReturn(advertiser);
		when(adapter.getBluetoothLeScanner())
				.thenReturn(mock(BluetoothLeScanner.class));
		when(manager.openGattServer(any(Context.class),
				any(BluetoothGattServerCallback.class)))
				.thenReturn(mock(BluetoothGattServer.class));
		when(wakeLocks.createWakeLock(anyString())).thenReturn(wakeLock);
		when(scheduler.schedule(any(Runnable.class), any(Executor.class),
				any(Long.class), any(TimeUnit.class)))
				.thenReturn(mock(Cancellable.class));
		MeshForwarder forwarder = new MeshForwarder(payload -> {
		}, new SecureRandom());
		transport = new BleMeshTransport(context, forwarder, null, null,
				manager, MeshScreenPause.PAUSE_AFTER_MS, wakeLocks, scheduler);
	}

	@After
	public void tearDown() {
		transport.stop();
	}

	@Test
	public void thePhoneStaysAwakeFromTheStopUntilTheNewSetStarts()
			throws Exception {
		transport.start();
		Method m = BleMeshTransport.class.getDeclaredMethod(
				"switchAdvertisingSet", byte[].class);
		m.setAccessible(true);
		m.invoke(transport,
				new MeshLinkPolicy(new SecureRandom()).freshNonce());
		verify(wakeLock).acquire();
		verify(advertiser, timeout(5_000).times(2)).startAdvertisingSet(
				any(), any(), any(), isNull(), isNull(),
				any(AdvertisingSetCallback.class));
		verify(wakeLock, timeout(2_000)).release();
		InOrder order = inOrder(wakeLock, advertiser);
		order.verify(wakeLock).acquire();
		order.verify(advertiser).stopAdvertisingSet(any());
		order.verify(advertiser).startAdvertisingSet(any(), any(), any(),
				isNull(), isNull(), any(AdvertisingSetCallback.class));
		order.verify(wakeLock).release();
		verify(wakeLock, timeout(4_000).times(1)).release();
	}

	@Test
	public void theScreenOffWaitIsCountedOnTheRealTimeScheduler() {
		transport.setPauseWhenScreenOff(true);
		transport.start();
		context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
		shadowOf(Looper.getMainLooper()).idle();
		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(scheduler).schedule(task.capture(), any(Executor.class),
				eq(MeshScreenPause.PAUSE_AFTER_MS), eq(TimeUnit.MILLISECONDS));
		task.getValue().run();
		verify(advertiser, timeout(2_000)).stopAdvertisingSet(any());
	}

	@Test
	public void aSetThatFailsToStartIsTriedAgain() throws Exception {
		Field retry = BleMeshTransport.class.getDeclaredField(
				"advertisingRetryMs");
		retry.setAccessible(true);
		retry.set(transport, 50L);
		transport.start();
		ArgumentCaptor<AdvertisingSetCallback> cb =
				ArgumentCaptor.forClass(AdvertisingSetCallback.class);
		verify(advertiser).startAdvertisingSet(any(), any(), any(), isNull(),
				isNull(), cb.capture());
		cb.getValue().onAdvertisingSetStarted(null, 0,
				AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS);
		verify(advertiser, timeout(5_000).times(2)).startAdvertisingSet(
				any(), any(), any(), isNull(), isNull(),
				any(AdvertisingSetCallback.class));
	}
}
