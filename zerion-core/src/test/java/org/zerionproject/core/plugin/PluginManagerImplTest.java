package org.zerionproject.core.plugin;

import org.zerionproject.core.api.connection.ConnectionManager;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.PluginCallback;
import org.zerionproject.core.api.plugin.PluginConfig;
import org.zerionproject.core.api.plugin.PluginException;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexPluginFactory;
import org.zerionproject.core.api.plugin.simplex.SimplexPlugin;
import org.zerionproject.core.api.plugin.simplex.SimplexPluginFactory;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.zerionproject.core.test.TestUtils.getTransportId;
import static org.junit.Assert.assertTrue;

public class PluginManagerImplTest extends BrambleMockTestCase {

	@Test
	public void testStartAndStop() throws Exception {
		Executor ioExecutor = Executors.newSingleThreadExecutor();
		EventBus eventBus = context.mock(EventBus.class);
		PluginConfig pluginConfig = context.mock(PluginConfig.class);
		ConnectionManager connectionManager =
				context.mock(ConnectionManager.class);
		SettingsManager settingsManager =
				context.mock(SettingsManager.class);
		TransportPropertyManager transportPropertyManager =
				context.mock(TransportPropertyManager.class);

		SimplexPluginFactory simplexFactory =
				context.mock(SimplexPluginFactory.class);
		SimplexPlugin simplexPlugin = context.mock(SimplexPlugin.class);
		TransportId simplexId = getTransportId();
		SimplexPluginFactory simplexFailFactory =
				context.mock(SimplexPluginFactory.class, "simplexFailFactory");
		SimplexPlugin simplexFailPlugin =
				context.mock(SimplexPlugin.class, "simplexFailPlugin");
		TransportId simplexFailId = getTransportId();

		DuplexPluginFactory duplexFactory =
				context.mock(DuplexPluginFactory.class);
		DuplexPlugin duplexPlugin = context.mock(DuplexPlugin.class);
		TransportId duplexId = getTransportId();
		DuplexPluginFactory duplexFailFactory =
				context.mock(DuplexPluginFactory.class, "duplexFailFactory");
		TransportId duplexFailId = getTransportId();

		context.checking(new Expectations() {{
			allowing(simplexPlugin).getId();
			will(returnValue(simplexId));
			allowing(simplexFailPlugin).getId();
			will(returnValue(simplexFailId));
			allowing(duplexPlugin).getId();
			will(returnValue(duplexId));
			allowing(pluginConfig).shouldPoll();
			will(returnValue(false));

			oneOf(eventBus).addListener(with(any(EventListener.class)));
			oneOf(settingsManager).getSettings(with(any(String.class)));
			will(returnValue(new Settings()));

			oneOf(pluginConfig).getSimplexFactories();
			will(returnValue(Arrays.asList(simplexFactory,
					simplexFailFactory)));
			oneOf(simplexFactory).getId();
			will(returnValue(simplexId));
			oneOf(simplexFactory).createPlugin(with(any(PluginCallback.class)));
			will(returnValue(simplexPlugin));
			oneOf(simplexPlugin).start();

			oneOf(simplexFailFactory).getId();
			will(returnValue(simplexFailId));
			oneOf(simplexFailFactory).createPlugin(with(any(
					PluginCallback.class)));
			will(returnValue(simplexFailPlugin));
			oneOf(simplexFailPlugin).start();
			will(throwException(new PluginException()));

			oneOf(pluginConfig).getDuplexFactories();
			will(returnValue(Arrays.asList(duplexFactory, duplexFailFactory)));
			oneOf(duplexFactory).getId();
			will(returnValue(duplexId));
			oneOf(duplexFactory).createPlugin(with(any(PluginCallback.class)));
			will(returnValue(duplexPlugin));
			oneOf(duplexPlugin).start();

			oneOf(duplexFailFactory).getId();
			will(returnValue(duplexFailId));
			oneOf(duplexFailFactory).createPlugin(with(any(
					PluginCallback.class)));
			will(returnValue(null));

			oneOf(eventBus).removeListener(with(any(EventListener.class)));
			oneOf(simplexPlugin).stop();
			oneOf(simplexFailPlugin).stop();
			oneOf(duplexPlugin).stop();
		}});

		PluginManagerImpl p = new PluginManagerImpl(ioExecutor, ioExecutor,
				eventBus, pluginConfig, connectionManager, settingsManager,
				transportPropertyManager);

		p.startService();
		p.stopService();
	}

	/**
	 * A restart stops the running plugin of the transport and starts a
	 * fresh one from its factory, so the Tor process and its listeners
	 * are rebuilt on request without touching the other plugins.
	 */
	@Test
	public void testRestartPluginStopsTheOldAndStartsAFreshOne()
			throws Exception {
		Executor ioExecutor = Executors.newSingleThreadExecutor();
		EventBus eventBus = context.mock(EventBus.class);
		PluginConfig pluginConfig = context.mock(PluginConfig.class);
		ConnectionManager connectionManager =
				context.mock(ConnectionManager.class);
		SettingsManager settingsManager =
				context.mock(SettingsManager.class);
		TransportPropertyManager transportPropertyManager =
				context.mock(TransportPropertyManager.class);
		DuplexPluginFactory duplexFactory =
				context.mock(DuplexPluginFactory.class);
		DuplexPlugin duplexPlugin = context.mock(DuplexPlugin.class, "old");
		DuplexPlugin freshPlugin = context.mock(DuplexPlugin.class, "fresh");
		TransportId duplexId = getTransportId();
		java.util.concurrent.CountDownLatch restarted =
				new java.util.concurrent.CountDownLatch(1);

		context.checking(new Expectations() {{
			allowing(duplexPlugin).getId();
			will(returnValue(duplexId));
			allowing(freshPlugin).getId();
			will(returnValue(duplexId));
			allowing(pluginConfig).shouldPoll();
			will(returnValue(false));
			oneOf(eventBus).addListener(with(any(EventListener.class)));
			allowing(settingsManager).getSettings(with(any(String.class)));
			will(returnValue(new Settings()));
			oneOf(pluginConfig).getSimplexFactories();
			will(returnValue(java.util.Collections.emptyList()));
			allowing(pluginConfig).getDuplexFactories();
			will(returnValue(java.util.Collections.singletonList(
					duplexFactory)));
			allowing(duplexFactory).getId();
			will(returnValue(duplexId));
			oneOf(duplexFactory).createPlugin(with(any(PluginCallback.class)));
			will(returnValue(duplexPlugin));
			oneOf(duplexPlugin).start();
			oneOf(duplexPlugin).stop();
			oneOf(duplexFactory).createPlugin(with(any(PluginCallback.class)));
			will(returnValue(freshPlugin));
			oneOf(freshPlugin).start();
			will(new org.jmock.api.Action() {
				@Override
				public Object invoke(org.jmock.api.Invocation invocation) {
					restarted.countDown();
					return null;
				}

				@Override
				public void describeTo(
						org.hamcrest.Description description) {
					description.appendText("counts the fresh start");
				}
			});
			oneOf(eventBus).removeListener(with(any(EventListener.class)));
			oneOf(freshPlugin).stop();
		}});

		PluginManagerImpl p = new PluginManagerImpl(ioExecutor, ioExecutor,
				eventBus, pluginConfig, connectionManager, settingsManager,
				transportPropertyManager);

		p.startService();
		p.restartPlugin(duplexId);
		assertTrue(restarted.await(10,
				java.util.concurrent.TimeUnit.SECONDS));
		p.stopService();
		context.assertIsSatisfied();
	}

	@Test
	public void testConnectionsPausedStartsNoPlugins() throws Exception {
		Executor ioExecutor = Executors.newSingleThreadExecutor();
		EventBus eventBus = context.mock(EventBus.class);
		PluginConfig pluginConfig = context.mock(PluginConfig.class);
		ConnectionManager connectionManager =
				context.mock(ConnectionManager.class);
		SettingsManager settingsManager =
				context.mock(SettingsManager.class);
		TransportPropertyManager transportPropertyManager =
				context.mock(TransportPropertyManager.class);

		Settings paused = new Settings();
		paused.putBoolean("connectionsPaused", true);

		context.checking(new Expectations() {{
			oneOf(eventBus).addListener(with(any(EventListener.class)));
			oneOf(settingsManager).getSettings(with(any(String.class)));
			will(returnValue(paused));

			oneOf(eventBus).removeListener(with(any(EventListener.class)));
		}});

		PluginManagerImpl p = new PluginManagerImpl(ioExecutor, ioExecutor,
				eventBus, pluginConfig, connectionManager, settingsManager,
				transportPropertyManager);

		p.startService();
		p.stopService();
	}
}
