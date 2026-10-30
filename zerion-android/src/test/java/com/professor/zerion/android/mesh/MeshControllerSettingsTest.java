package com.professor.zerion.android.mesh;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MeshControllerSettingsTest {

	private final MeshTestSettings settings = new MeshTestSettings();
	private final MeshManager meshManager = mock(MeshManager.class);
	private MeshController controller;

	@Before
	public void setUp() {
		Context context = mock(Context.class);
		when(context.getApplicationContext()).thenReturn(context);
		controller = new MeshController(context, meshManager, settings,
				Runnable::run, () -> mock(MeshTextSender.class),
				mock(MeshOutbox.class));
	}

	@Test
	public void theBluetoothNameIsAndroidUntilTheUserChoosesOne()
			throws Exception {
		assertEquals("Android", controller.getBluetoothAlias());
	}

	@Test
	public void aChosenNameIsCleanedStoredAndShownAtOnce() throws Exception {
		controller.setBluetoothAlias(" Kitchen\u200b radio ");
		assertEquals("Kitchen radio", controller.getBluetoothAlias());
		assertEquals("Kitchen radio", settings.getSettings(
				MeshController.MESH_NAMESPACE).get("btAlias"));
		verify(meshManager).reapplyBluetoothName();
	}

	@Test
	public void theSettingsScreenShowsTheNameThatWillBeUsed() {
		assertEquals("Kitchen radio",
				MeshController.cleanBluetoothAlias("\tKitchen radio\u202e"));
		assertEquals("Android", MeshController.cleanBluetoothAlias(""));
	}

	@Test
	public void theRangeIsNormalUntilTheUserShortensIt() throws Exception {
		assertFalse(controller.isShortRange());
		controller.setShortRange(true);
		assertTrue(controller.isShortRange());
		verify(meshManager).setShortRange(true);
		controller.setShortRange(false);
		assertFalse(controller.isShortRange());
		verify(meshManager).setShortRange(false);
	}

	@Test
	public void theScreenPauseIsOffUntilTheUserTurnsItOn() throws Exception {
		assertFalse(controller.isPauseWhenScreenOff());
		controller.setPauseWhenScreenOff(true);
		assertTrue(controller.isPauseWhenScreenOff());
		verify(meshManager).setPauseWhenScreenOff(true);
	}

	@Test
	public void clearingTheNameGoesBackToAndroid() throws Exception {
		controller.setBluetoothAlias("Kitchen radio");
		controller.setBluetoothAlias("   ");
		assertEquals("Android", controller.getBluetoothAlias());
	}
}
