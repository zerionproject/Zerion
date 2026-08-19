package com.professor.zerion.android.i2p;

import android.content.Context;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TorSocksPort;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.transport.ZtpConnectionHandler;
import org.zerionproject.transport.i2p.I2pOverlayTransport;
import org.zerionproject.transport.i2p.I2pStack;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

import javax.inject.Inject;
import javax.inject.Provider;

@NotNullByDefault
public class BundledI2pStack implements I2pStack {

	private final Context context;
	private final int torSocksPort;
	private final Provider<PluginManager> pluginManagerProvider;
	private final SettingsManager settingsManager;

	@Inject
	public BundledI2pStack(Context context, @TorSocksPort int torSocksPort,
			Provider<PluginManager> pluginManagerProvider,
			SettingsManager settingsManager) {
		this.context = context;
		this.torSocksPort = torSocksPort;
		this.pluginManagerProvider = pluginManagerProvider;
		this.settingsManager = settingsManager;
	}

	@Override
	public I2pOverlayTransport createTransport(Executor ioExecutor,
			ZtpConnectionHandler handler) {
		BooleanSupplier directReseedAllowed = () -> {
			try {
				return settingsManager.getSettings(I2pConstants.ID.getString())
						.getBoolean(I2pConstants.PREF_I2P_DIRECT_RESEED,
								I2pConstants.DEFAULT_PREF_I2P_DIRECT_RESEED);
			} catch (DbException e) {
				return false;
			}
		};
		BooleanSupplier torActive = () -> {
			Plugin p = pluginManagerProvider.get().getPlugin(TorConstants.ID);
			return p != null && p.getState() == Plugin.State.ACTIVE;
		};
		BundledI2pRouter router = new BundledI2pRouter(context, torSocksPort,
				directReseedAllowed, torActive);
		return new I2pStreamingTransport(router, ioExecutor, handler);
	}
}
