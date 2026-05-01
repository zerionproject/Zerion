package org.briarproject.onionwrapper;

import android.app.Application;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class AndroidLocationUtilsFactory {

	public static LocationUtils createAndroidLocationUtils(Application app) {
		return new AndroidLocationUtils(app);
	}

}
