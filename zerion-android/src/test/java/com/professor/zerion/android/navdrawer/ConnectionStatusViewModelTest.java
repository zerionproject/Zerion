package com.professor.zerion.android.navdrawer;

import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.network.NetworkManager;
import org.zerionproject.core.api.network.NetworkStatus;
import org.zerionproject.core.api.network.event.NetworkStatusEvent;
import org.zerionproject.core.api.plugin.OnionClientAuthManager;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.Plugin.State;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.TorClockSkewEvent;
import org.zerionproject.core.api.plugin.event.TorOnionPublishedEvent;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.settings.event.SettingsUpdatedEvent;
import org.zerionproject.core.plugin.tor.B4OnionRotation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ConnectionStatusViewModelTest {

	private final List<EventListener> listeners = new CopyOnWriteArrayList<>();
	private final EventBus bus = new EventBus() {
		@Override
		public void addListener(EventListener l) {
			listeners.add(l);
		}

		@Override
		public void removeListener(EventListener l) {
			listeners.remove(l);
		}

		@Override
		public void broadcast(Event e) {
			for (EventListener l : listeners) l.eventOccurred(e);
		}
	};
	private final PluginManager pluginManager = mock(PluginManager.class);
	private final Plugin tor = mock(Plugin.class);
	private final NetworkManager networkManager = mock(NetworkManager.class);
	private final ConnectionRegistry connections =
			mock(ConnectionRegistry.class);

	@Before
	public void setUp() {
		when(pluginManager.getPlugin(TorConstants.ID)).thenReturn(tor);
		when(tor.getState()).thenReturn(State.ACTIVE);
		when(networkManager.getNetworkStatus()).thenReturn(
				new NetworkStatus(true, true, false));
		when(connections.getConnectedContacts(TorConstants.ID))
				.thenReturn(Collections.emptyList());
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private ConnectionStatusViewModel open() {
		TorPublishTracker tracker =
				new TorPublishTracker(bus, pluginManager, connections);
		idle();
		ConnectionStatusViewModel vm = new ConnectionStatusViewModel(bus,
				pluginManager, networkManager, tracker);
		idle();
		return vm;
	}

	@Test
	public void aPublishedAddressReadsConnected() {
		ConnectionStatusViewModel vm = open();
		assertEquals(ConnectionChip.State.CONNECTING, vm.getState().getValue());
		bus.broadcast(new TorOnionPublishedEvent("ownonion"));
		idle();
		assertEquals(ConnectionChip.State.CONNECTED, vm.getState().getValue());
	}

	@Test
	public void pausingConnectionsIsSeenWithoutRestarting() {
		ConnectionStatusViewModel vm = open();
		when(pluginManager.isConnectionsPaused()).thenReturn(true);
		when(pluginManager.getPlugin(TorConstants.ID)).thenReturn(null);
		bus.broadcast(new SettingsUpdatedEvent("org.zerionproject.mode",
				new Settings()));
		idle();
		assertEquals(ConnectionChip.State.PAUSED, vm.getState().getValue());
	}

	@Test
	public void losingTheInternetReadsOffline() {
		ConnectionStatusViewModel vm = open();
		bus.broadcast(new NetworkStatusEvent(
				new NetworkStatus(false, false, false)));
		idle();
		assertEquals(ConnectionChip.State.OFFLINE, vm.getState().getValue());
	}

	@Test
	public void aWrongClockIsReported() {
		ConnectionStatusViewModel vm = open();
		bus.broadcast(new TorClockSkewEvent(3_600L));
		idle();
		assertEquals(ConnectionChip.State.CLOCK, vm.getState().getValue());
	}

	@Test
	public void closingTheScreenStopsListening() throws Exception {
		ConnectionStatusViewModel vm = open();
		assertTrue(listeners.contains(vm));
		Method m = androidx.lifecycle.ViewModel.class
				.getDeclaredMethod("onCleared");
		m.setAccessible(true);
		m.invoke(vm);
		assertFalse(listeners.contains(vm));
	}

	@Test
	public void theChipNeedsNoAddressesOrContacts() {
		List<Class<?>> heavy = Arrays.asList(TransportPropertyManager.class,
				B4OnionRotation.class, OnionClientAuthManager.class,
				ContactManager.class, SettingsManager.class,
				PluginViewModel.class);
		for (Constructor<?> c :
				ConnectionStatusViewModel.class.getDeclaredConstructors()) {
			for (Class<?> p : c.getParameterTypes()) {
				assertFalse(p.getName(), heavy.contains(p));
			}
		}
		for (Field f : ConnectionStatusViewModel.class.getDeclaredFields()) {
			assertFalse(f.getName(), heavy.contains(f.getType()));
		}
	}
}
