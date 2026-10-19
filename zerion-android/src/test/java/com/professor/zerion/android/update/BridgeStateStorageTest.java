package com.professor.zerion.android.update;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.transport.BridgeHealth;
import org.zerionproject.transport.BridgeListStore;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class BridgeStateStorageTest {

	private static final List<Class<?>> HOLDERS = Arrays.asList(
			BridgeListUpdater.class, BridgeListStore.class, BridgeHealth.class);

	private static final List<String> SOURCES = Arrays.asList(
			"src/main/java/com/professor/zerion/android/update/"
					+ "BridgeListUpdater.java",
			"src/main/java/com/professor/zerion/android/update/"
					+ "BridgeListSource.java",
			"../zerion-core/src/main/java/org/zerionproject/transport/"
					+ "BridgeListStore.java",
			"../zerion-core/src/main/java/org/zerionproject/transport/"
					+ "BridgeHealth.java");

	@Test
	public void bridgeStateIsKeptOnlyInTheEncryptedAccountDatabase() {
		for (Class<?> c : HOLDERS) {
			for (Field f : c.getDeclaredFields()) {
				assertFalse(c + "." + f.getName(),
						SharedPreferences.class.isAssignableFrom(f.getType()));
				assertFalse(c + "." + f.getName(),
						f.getGenericType().toString()
								.contains("SharedPreferences"));
				assertFalse(c + "." + f.getName(),
						Context.class.isAssignableFrom(f.getType()));
			}
		}
		for (Class<?> c : Arrays.asList(BridgeListStore.class,
				BridgeHealth.class)) {
			Constructor<?>[] ctors = c.getConstructors();
			assertEquals(1, ctors.length);
			assertEquals(SettingsManager.class,
					ctors[0].getParameterTypes()[0]);
		}
	}

	@Test
	public void noBridgeSourceTouchesPreferencesOrLogs() throws IOException {
		for (String path : SOURCES) {
			String text = new String(Files.readAllBytes(new File(path)
					.toPath()), StandardCharsets.UTF_8);
			assertFalse(path, text.contains("SharedPreferences"));
			assertFalse(path, text.contains("getSharedPreferences"));
			assertFalse(path, text.contains("android.util.Log"));
			assertFalse(path, text.contains("Logger"));
			assertFalse(path, text.contains("printStackTrace"));
			assertFalse(path, text.contains("System.out"));
			assertFalse(path, text.contains("System.err"));
		}
	}

	@Test
	public void theBridgeNamespacesAreNotTheTorSettings() {
		String tor = TorConstants.ID.getString();
		assertNotEquals(tor, BridgeListStore.NAMESPACE);
		assertNotEquals(tor, BridgeHealth.NAMESPACE);
		assertFalse(BridgeListStore.NAMESPACE.startsWith(tor));
		assertFalse(BridgeHealth.NAMESPACE.startsWith(tor));
		assertTrue(BridgeListStore.NAMESPACE.startsWith("tor-bridge-"));
		assertTrue(BridgeHealth.NAMESPACE.startsWith("tor-bridge-"));
	}
}
