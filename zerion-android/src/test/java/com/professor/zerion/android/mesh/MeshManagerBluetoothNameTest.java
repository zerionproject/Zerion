package com.professor.zerion.android.mesh;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.system.SystemClock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class MeshManagerBluetoothNameTest {

	private Context context;
	private BluetoothAdapter adapter;

	@Before
	public void setUp() {
		context = RuntimeEnvironment.getApplication();
		adapter = ((BluetoothManager) context
				.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
		adapter.setName("Alice's Pixel");
	}

	@Test
	public void twoSessionsShowTheSameName() throws Exception {
		mask(manager(new MeshTestSettings()));
		String first = adapter.getName();
		adapter.setName("Bob's Moto");
		mask(manager(new MeshTestSettings()));
		assertEquals(first, adapter.getName());
	}

	@Test
	public void stoppingRestoresTheNameAfterASessionThatDidNotStop()
			throws Exception {
		MeshTestSettings settings = new MeshTestSettings();
		mask(manager(settings));
		manager(settings).stop();
		assertEquals("Alice's Pixel", adapter.getName());
	}

	private MeshManager manager(SettingsManager settings) {
		return new MeshManager(context, mock(CryptoComponent.class),
				mock(IdentityManager.class), mock(DatabaseComponent.class),
				settings, new SystemClock(), new MeshManager.OpenedHandler() {
					@Override
					public boolean onOfflineMessage(byte[] sender, int type,
							byte[] payload, long ts) {
						return false;
					}
				});
	}

	private static void mask(MeshManager m) throws Exception {
		Method mask = MeshManager.class.getDeclaredMethod("maskBluetoothName");
		mask.setAccessible(true);
		mask.invoke(m);
	}
}
