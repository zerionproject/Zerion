package org.briarproject.bramble.network;

import android.annotation.TargetApi;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkInfo;

import org.briarproject.bramble.api.Cancellable;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventExecutor;
import org.briarproject.bramble.api.lifecycle.Service;
import org.briarproject.bramble.api.network.NetworkManager;
import org.briarproject.bramble.api.network.NetworkStatus;
import org.briarproject.bramble.api.network.event.NetworkStatusEvent;
import org.briarproject.bramble.api.system.TaskScheduler;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.inject.Inject;

import static android.content.Context.CONNECTIVITY_SERVICE;
import static android.content.Intent.ACTION_SCREEN_OFF;
import static android.content.Intent.ACTION_SCREEN_ON;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED;
import static java.net.NetworkInterface.getNetworkInterfaces;
import static java.util.Collections.list;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.briarproject.bramble.util.AndroidUtils.registerReceiver;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class AndroidNetworkManager implements NetworkManager, Service {

	private final TaskScheduler scheduler;
	private final EventBus eventBus;
	private final Executor eventExecutor;
	private final Application app;
	private final ConnectivityManager connectivityManager;
	private final AtomicReference<Cancellable> connectivityCheck =
			new AtomicReference<>();
	private final AtomicBoolean used = new AtomicBoolean(false);

	private volatile BroadcastReceiver networkStateReceiver = null;

	@Inject
	AndroidNetworkManager(TaskScheduler scheduler, EventBus eventBus,
			@EventExecutor Executor eventExecutor, Application app) {
		this.scheduler = scheduler;
		this.eventBus = eventBus;
		this.eventExecutor = eventExecutor;
		this.app = app;
		connectivityManager = (ConnectivityManager)
				requireNonNull(app.getSystemService(CONNECTIVITY_SERVICE));
	}

	@Override
	public void startService() {
		if (used.getAndSet(true)) throw new IllegalStateException();
		networkStateReceiver = new NetworkStateReceiver();
		IntentFilter filter = new IntentFilter();
		filter.addAction(CONNECTIVITY_ACTION);
		filter.addAction(ACTION_SCREEN_ON);
		filter.addAction(ACTION_SCREEN_OFF);
		if (SDK_INT >= 23) filter.addAction(ACTION_DEVICE_IDLE_MODE_CHANGED);
		registerReceiver(app, networkStateReceiver, filter);
	}

	@Override
	public void stopService() {
		if (networkStateReceiver != null)
			app.unregisterReceiver(networkStateReceiver);
	}

	@Override
	public NetworkStatus getNetworkStatus() {
		try {
			NetworkInfo net = connectivityManager.getActiveNetworkInfo();
			boolean connected = net != null && net.isConnected();
			boolean ipv6Only = false;
			if (connected) {
				if (SDK_INT >= 23) ipv6Only = isActiveNetworkIpv6Only();
				else ipv6Only = areAllAvailableNetworksIpv6Only();
			}
			return new NetworkStatus(connected, false, ipv6Only);
		} catch (SecurityException e) {
			return new NetworkStatus(true, false, true);
		}
	}

	@TargetApi(23)
	private boolean isActiveNetworkIpv6Only() {
		try {
			Network net = connectivityManager.getActiveNetwork();
			if (net == null) {
				return false;
			}
			LinkProperties props = connectivityManager.getLinkProperties(net);
			if (props == null) {
				return false;
			}
			boolean hasIpv6Unicast = false;
			for (LinkAddress linkAddress : props.getLinkAddresses()) {
				InetAddress addr = linkAddress.getAddress();
				if (addr instanceof Inet4Address) return false;
				if (!addr.isMulticastAddress()) hasIpv6Unicast = true;
			}
			return hasIpv6Unicast;
		} catch (SecurityException e) {
			return false;
		}
	}

	private boolean areAllAvailableNetworksIpv6Only() {
		try {
			Enumeration<NetworkInterface> interfaces = getNetworkInterfaces();
			if (interfaces == null) {
				return false;
			}
			boolean hasIpv6Unicast = false;
			for (NetworkInterface i : list(interfaces)) {
				if (i.isLoopback() || !i.isUp()) continue;
				for (InetAddress addr : list(i.getInetAddresses())) {
					if (addr instanceof Inet4Address) return false;
					if (!addr.isMulticastAddress()) hasIpv6Unicast = true;
				}
			}
			return hasIpv6Unicast;
		} catch (SocketException e) {
			return false;
		}
	}

	private void updateConnectionStatus() {
		eventBus.broadcast(new NetworkStatusEvent(getNetworkStatus()));
	}

	private void scheduleConnectionStatusUpdate(int delay, TimeUnit unit) {
		Cancellable newConnectivityCheck =
				scheduler.schedule(this::updateConnectionStatus, eventExecutor,
						delay, unit);
		Cancellable oldConnectivityCheck =
				connectivityCheck.getAndSet(newConnectivityCheck);
		if (oldConnectivityCheck != null) oldConnectivityCheck.cancel();
	}

	private class NetworkStateReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context ctx, Intent i) {
			String action = i.getAction();
			eventExecutor.execute(() -> updateConnectionStatus());
			if (isSleepOrDozeEvent(action)) {
				scheduleConnectionStatusUpdate(1, MINUTES);
			}
		}

		private boolean isSleepOrDozeEvent(@Nullable String action) {
			boolean isSleep = ACTION_SCREEN_ON.equals(action) ||
					ACTION_SCREEN_OFF.equals(action);
			boolean isDoze = SDK_INT >= 23 &&
					ACTION_DEVICE_IDLE_MODE_CHANGED.equals(action);
			return isSleep || isDoze;
		}
	}
}
