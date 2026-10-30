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
	static final String ALIAS_KEY = "btAlias";
	static final String APPLIED_MASK_KEY = "btAppliedMask";
	static final int MAX_ALIAS_LENGTH = 32;
	static final long APPLY_TIMEOUT_MS = 1_000L;
	private static final long APPLY_POLL_MS = 50L;
	private static final Pattern LEGACY_MASK =
			Pattern.compile("BT-[0-9a-f]{6}");

	private MeshBluetoothName() {
	}

	static String sanitizeAlias(@Nullable String raw) {
		if (raw == null) return "";
		StringBuilder sb = new StringBuilder();
		int kept = 0;
		int i = 0;
		while (i < raw.length() && kept < MAX_ALIAS_LENGTH) {
			int cp = raw.codePointAt(i);
			i += Character.charCount(cp);
			if (isHidden(cp)) continue;
			if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
				cp = ' ';
			}
			sb.appendCodePoint(cp);
			kept++;
		}
		return sb.toString().trim();
	}

	private static boolean isHidden(int cp) {
		switch (Character.getType(cp)) {
			case Character.CONTROL:
			case Character.FORMAT:
			case Character.SURROGATE:
			case Character.PRIVATE_USE:
			case Character.UNASSIGNED:
			case Character.LINE_SEPARATOR:
			case Character.PARAGRAPH_SEPARATOR:
				return true;
			default:
				return false;
		}
	}

	static String alias(Settings s) {
		String alias = sanitizeAlias(s.get(ALIAS_KEY));
		return alias.isEmpty() ? MASKED_NAME : alias;
	}

	private static boolean isMasked(@Nullable String name, Settings s) {
		if (name == null) return false;
		return MASKED_NAME.equals(name)
				|| LEGACY_MASK.matcher(name).matches()
				|| name.equals(alias(s))
				|| name.equals(s.get(APPLIED_MASK_KEY));
	}

	static void mask(Adapter adapter, SettingsManager settings)
			throws DbException {
		Settings s = settings.getSettings(NAMESPACE);
		String mask = alias(s);
		String current = adapter.getName();
		if (current != null && !isMasked(current, s)) {
			Settings upd = new Settings();
			upd.put(ORIGINAL_NAME_KEY, current);
			settings.mergeSettings(upd, NAMESPACE);
		}
		if (mask.equals(current)) {
			recordApplied(settings, s, mask);
			return;
		}
		if (!adapter.setName(mask)) return;
		recordApplied(settings, s, mask);
		long deadline = System.nanoTime() + APPLY_TIMEOUT_MS * 1_000_000L;
		while (!mask.equals(adapter.getName())
				&& System.nanoTime() < deadline) {
			try {
				Thread.sleep(APPLY_POLL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private static void recordApplied(SettingsManager settings, Settings s,
			String mask) throws DbException {
		if (mask.equals(s.get(APPLIED_MASK_KEY))) return;
		Settings upd = new Settings();
		upd.put(APPLIED_MASK_KEY, mask);
		settings.mergeSettings(upd, NAMESPACE);
	}

	static boolean restore(Adapter adapter, SettingsManager settings)
			throws DbException {
		Settings s = settings.getSettings(NAMESPACE);
		String original = s.get(ORIGINAL_NAME_KEY);
		if (original == null || original.isEmpty()) return true;
		String current = adapter.getName();
		if (current == null || isMasked(current, s)) {
			if (!adapter.setName(original)) return false;
		}
		Settings upd = new Settings();
		upd.put(ORIGINAL_NAME_KEY, "");
		settings.mergeSettings(upd, NAMESPACE);
		return true;
	}
}
