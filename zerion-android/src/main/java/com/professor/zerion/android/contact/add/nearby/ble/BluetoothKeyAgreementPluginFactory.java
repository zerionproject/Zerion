package com.professor.zerion.android.contact.add.nearby.ble;

import android.app.Application;

import org.zerionproject.core.api.plugin.PluginCallback;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexPluginFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

@Immutable
@NotNullByDefault
public class BluetoothKeyAgreementPluginFactory implements DuplexPluginFactory {

	private final Application application;

	@Inject
	public BluetoothKeyAgreementPluginFactory(Application application) {
		this.application = application;
	}

	@Override
	public TransportId getId() {
		return BluetoothKeyAgreementConstants.ID;
	}

	@Override
	public long getMaxLatency() {
		return BluetoothKeyAgreementConstants.MAX_LATENCY;
	}

	@Override
	public DuplexPlugin createPlugin(PluginCallback callback) {
		return new BluetoothKeyAgreementPlugin(application, callback);
	}
}
