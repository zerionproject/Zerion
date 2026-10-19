package com.professor.zerion.android.navdrawer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.transport.TorBridgeConfigurator;

import java.util.ArrayList;
import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class HelperTransportsAnswerTest {

	@Test
	public void thePromptIsClearedOnlyOnceTheAnswerIsStored()
			throws Exception {
		EventBus eventBus = mock(EventBus.class);
		SettingsManager settings = mock(SettingsManager.class);
		TorBridgeConfigurator bridges = mock(TorBridgeConfigurator.class);
		List<Runnable> db = new ArrayList<>();
		HelperTransportsViewModel vm = new HelperTransportsViewModel(
				eventBus, settings, db::add, bridges);

		vm.answer("RU", true);

		assertNull(vm.getPrompt().getValue());
		verify(bridges, never()).clearHelperPrompt();
		assertEquals(1, db.size());
		db.get(0).run();
		InOrder order = inOrder(settings, bridges);
		order.verify(settings).mergeSettings(any(Settings.class),
				eq(TorConstants.ID.getString()));
		order.verify(bridges).clearHelperPrompt();
	}
}
