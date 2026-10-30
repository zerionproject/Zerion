package com.professor.zerion.android.settings;

import com.professor.zerion.android.mesh.MeshController;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.plugin.PluginManager;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class OfflineModeBluetoothPromptTest {

	public static class Probe extends ConnectionsFragment {

		int asked;

		@Override
		void askToTurnOnBluetooth() {
			asked++;
		}
	}

	@Test
	public void offlineModeAsksToTurnBluetoothOnLikeTheMeshSwitch() {
		Probe f = new Probe();
		f.meshController = mock(MeshController.class);
		f.pluginManager = mock(PluginManager.class);
		f.turnOnOfflineMesh();
		verify(f.meshController).setMeshEnabled(true);
		verify(f.pluginManager).setOfflineMode(true);
		assertEquals(1, f.asked);
	}
}
