package com.professor.zerion.android.mesh;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.regex.Pattern;

import javax.annotation.Nullable;

@NotNullByDefault
final class MeshBluetoothName {

	interface Adapter {

		@Nullable
		String getName();

		boolean setName(String name);
	}

	static final String MASKED_NAME = "Android";
	static final String NAMESPACE = MeshController.MESH_NAMESPACE;
	static final String ORIGINAL_NAME_KEY = "btOriginalName";
	static final long APPLY_TIMEOUT_MS = 1_000L;
	private static final long APPLY_POLL_MS = 50L;
	private static final Pattern LEGACY_MASK =
			Pattern.compile("BT-[0-9a-f]{6}");

	private MeshBluetoothName() {
	}

	static boolean isMasked(@Nullable String name) {
		return name != null && (MASKED_NAME.equals(name)
				|| LEGACY_MASK.matcher(name).matches());
	}

	static void mask(Adapter adapter, SettingsManager settings)
			throws DbException {
		String current = adapter.getName();
		if (current != null && !isMasked(current)) {
			Settings upd = new Settings();
			upd.put(ORIGINAL_NAME_KEY, current);
			settings.mergeSettings(upd, NAMESPACE);
		}
		if (MASKED_NAME.equals(current)) return;
		if (!adapter.setName(MASKED_NAME)) return;
		long deadline = System.nanoTime() + APPLY_TIMEOUT_MS * 1_000_000L;
		while (!MASKED_NAME.equals(adapter.getName())
				&& System.nanoTime() < deadline) {
			try {
				Thread.sleep(APPLY_POLL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	static void restore(Adapter adapter, SettingsManager settings)
			throws DbException {
		String original = settings.getSettings(NAMESPACE)
				.get(ORIGINAL_NAME_KEY);
		if (original == null || original.isEmpty()) return;
		String current = adapter.getName();
		if (current == null || isMasked(current)) {
			if (!adapter.setName(original)) return;
		}
		Settings upd = new Settings();
		upd.put(ORIGINAL_NAME_KEY, "");
		settings.mergeSettings(upd, NAMESPACE);
	}
}
