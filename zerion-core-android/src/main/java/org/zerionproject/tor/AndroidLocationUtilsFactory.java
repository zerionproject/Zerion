package org.zerionproject.tor;

import android.app.Application;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class AndroidLocationUtilsFactory {

	private AndroidLocationUtilsFactory() {
	}

	public static LocationUtils createAndroidLocationUtils(Application app) {
		return new AndroidLocationUtils(app);
	}
}
