package com.professor.zerion.android.mesh;

import android.content.Context;
import android.content.pm.PackageManager;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Provider;

import androidx.core.content.ContextCompat;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.S;

@ThreadSafe
@NotNullByDefault
public class MeshController implements OpenDatabaseHook {

	static final String MESH_NAMESPACE = "org.zerionproject.mesh";
	static final String PREF_MESH_ENABLED = "meshEnabled";

	private final Context appContext;
	private final MeshManager meshManager;
	private final SettingsManager settingsManager;
	private final Executor ioExecutor;
	private final Provider<MeshTextSender> textSenderProvider;
	private final MeshOutbox outbox;

	public MeshController(Context context, MeshManager meshManager,
			SettingsManager settingsManager, Executor ioExecutor,
			Provider<MeshTextSender> textSenderProvider, MeshOutbox outbox) {
		this.appContext = context.getApplicationContext();
		this.meshManager = meshManager;
		this.settingsManager = settingsManager;
		this.ioExecutor = ioExecutor;
		this.textSenderProvider = textSenderProvider;
		this.outbox = outbox;
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		boolean enabled = settingsManager.getSettings(txn, MESH_NAMESPACE)
				.getBoolean(PREF_MESH_ENABLED, false);
		if (enabled && isSupported() && hasPermissions(appContext)) {
			ioExecutor.execute(this::startQuietly);
		}
	}

	public void setMeshEnabled(boolean enabled) {
		ioExecutor.execute(() -> {
			try {
				Settings s = new Settings();
				s.putBoolean(PREF_MESH_ENABLED, enabled);
				settingsManager.mergeSettings(s, MESH_NAMESPACE);
			} catch (DbException e) {
			}
			if (enabled) {
				startQuietly();
			} else {
				meshManager.stop();
				outbox.clear();
			}
		});
	}

	public boolean isMeshEnabled() throws DbException {
		return settingsManager.getSettings(MESH_NAMESPACE)
				.getBoolean(PREF_MESH_ENABLED, false);
	}

	public boolean isRunning() {
		return meshManager.isRunning();
	}

	public int getPeerCount() {
		return meshManager.getPeerCount();
	}

	private void startQuietly() {
		try {
			textSenderProvider.get();
			meshManager.start();
		} catch (DbException e) {
		}
	}

	public static boolean isSupported() {
		return SDK_INT >= S;
	}

	public static boolean hasPermissions(Context context) {
		if (SDK_INT < S) return false;
		return granted(context, "android.permission.BLUETOOTH_ADVERTISE")
				&& granted(context, "android.permission.BLUETOOTH_CONNECT")
				&& granted(context, "android.permission.BLUETOOTH_SCAN");
	}

	public static String[] requiredPermissions() {
		return new String[]{
				"android.permission.BLUETOOTH_ADVERTISE",
				"android.permission.BLUETOOTH_CONNECT",
				"android.permission.BLUETOOTH_SCAN",
		};
	}

	private static boolean granted(Context context, String permission) {
		return ContextCompat.checkSelfPermission(context, permission)
				== PackageManager.PERMISSION_GRANTED;
	}
}
