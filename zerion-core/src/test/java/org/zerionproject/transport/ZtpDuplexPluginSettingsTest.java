package org.zerionproject.transport;

import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.TorBootstrapEvent;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.event.SettingsUpdatedEvent;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZtpDuplexPluginSettingsTest {

	@Test
	public void onlyAChangeOfTheTorSettingsClearsTheEntryRelayWarning() {
		assertTrue(ZtpDuplexPlugin.isTorSettingsChange(new SettingsUpdatedEvent(
				TorConstants.ID.getString(), new Settings())));
		assertFalse(ZtpDuplexPlugin.isTorSettingsChange(
				new SettingsUpdatedEvent(I2pConstants.ID.getString(),
						new Settings())));
		assertFalse(ZtpDuplexPlugin.isTorSettingsChange(
				new TorBootstrapEvent(100)));
	}
}
