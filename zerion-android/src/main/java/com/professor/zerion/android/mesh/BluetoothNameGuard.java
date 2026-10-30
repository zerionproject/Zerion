package com.professor.zerion.android.mesh;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@SuppressLint("MissingPermission")
@ThreadSafe
@NotNullByDefault
public class BluetoothNameGuard {

	public static final String HOLDER_MESH = "mesh";
	public static final String HOLDER_PAIRING = "pairing";

	interface AdapterSource {

		@Nullable
		MeshBluetoothName.Adapter get();
	}

	private final SettingsManager settings;
	private final AdapterSource source;
	private final Set<String> holders = new HashSet<>();
	private boolean restorePending = false;

	public BluetoothNameGuard(Context context, SettingsManager settings,
			Executor executor) {
		this(context, settings, systemAdapter(context.getApplicationContext()),
				executor);
	}

	BluetoothNameGuard(Context context, SettingsManager settings,
			AdapterSource source, Executor executor) {
		this(settings, source);
		context.getApplicationContext().registerReceiver(
				new BroadcastReceiver() {
					@Override
					public void onReceive(Context c, Intent intent) {
						int state = intent.getIntExtra(
								BluetoothAdapter.EXTRA_STATE,
								BluetoothAdapter.ERROR);
						if (state != BluetoothAdapter.STATE_ON) return;
						try {
							executor.execute(BluetoothNameGuard.this
									::onBluetoothOn);
						} catch (RejectedExecutionException e) {
						}
					}
				}, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
	}

	BluetoothNameGuard(SettingsManager settings, AdapterSource source) {
		this.settings = settings;
		this.source = source;
	}

	public synchronized void acquire(String holder) {
		holders.add(holder);
		mask();
	}

	public synchronized void release(String holder) {
		holders.remove(holder);
		if (holders.isEmpty()) restore();
	}

	public synchronized void restoreIfUnheld() {
		if (holders.isEmpty()) restore();
	}

	public synchronized void onBluetoothOn() {
		if (restorePending) restoreIfUnheld();
	}

	public synchronized void reapply() {
		if (!holders.isEmpty()) mask();
	}

	private void mask() {
		MeshBluetoothName.Adapter adapter = source.get();
		if (adapter == null) return;
		try {
			MeshBluetoothName.mask(adapter, settings);
		} catch (Exception e) {
		}
	}

	private void restore() {
		MeshBluetoothName.Adapter adapter = source.get();
		if (adapter == null) {
			restorePending = true;
			return;
		}
		try {
			restorePending = !MeshBluetoothName.restore(adapter, settings);
		} catch (Exception e) {
			restorePending = true;
		}
	}

	private static AdapterSource systemAdapter(Context context) {
		return () -> {
			if (android.os.Build.VERSION.SDK_INT
					< android.os.Build.VERSION_CODES.S) return null;
			BluetoothManager bm = (BluetoothManager)
					context.getSystemService(Context.BLUETOOTH_SERVICE);
			if (bm == null) return null;
			BluetoothAdapter adapter = bm.getAdapter();
			if (adapter == null) return null;
			return new MeshBluetoothName.Adapter() {
				@Nullable
				@Override
				public String getName() {
					return adapter.getName();
				}

				@Override
				public boolean setName(String name) {
					return adapter.setName(name);
				}
			};
		};
	}
}
