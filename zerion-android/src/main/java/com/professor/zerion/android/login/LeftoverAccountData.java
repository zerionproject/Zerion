package com.professor.zerion.android.login;

import android.content.Context;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;

@NotNullByDefault
final class LeftoverAccountData {

	private static final String VAULT_THROTTLE_FILE = "unlock.throttle";

	private LeftoverAccountData() {
	}

	static boolean present(Context context) {
		Context app = context.getApplicationContext();
		File profiles = new File(app.getFilesDir(), "profiles");
		if (holdsAnyFile(profiles)) return true;
		File noBackup = app.getNoBackupFilesDir();
		if (holdsAnyFile(new File(noBackup, "vault"))) return true;
		if (holdsAnyFile(new File(noBackup, "xmr"))) return true;
		if (holdsAnyFile(new File(app.getFilesDir(), "stickers"))) {
			return true;
		}
		File data = new File(app.getApplicationInfo().dataDir);
		return holdsAnyFile(new File(data, "app_db"))
				|| holdsAnyFile(new File(data, "app_key"));
	}

	private static boolean holdsAnyFile(File dir) {
		if (!dir.exists()) return false;
		if (!dir.isDirectory()) return true;
		File[] children = dir.listFiles();
		if (children == null) return true;
		for (File c : children) {
			if (c.isDirectory()) {
				if (holdsAnyFile(c)) return true;
			} else if (!c.getName().equals(VAULT_THROTTLE_FILE)) {
				return true;
			}
		}
		return false;
	}
}
