package org.zerionproject.tor;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Locale;

import static android.content.Context.TELEPHONY_SERVICE;

/**
 * Guesses the current country from the first of these that answers, in
 * order of likely correctness: the phone network, which works without a
 * SIM or with a foreign one; the SIM, which assumes no roaming; the user's
 * locale.
 */
@NotNullByDefault
class AndroidLocationUtils implements LocationUtils {

	private final Context appContext;

	AndroidLocationUtils(Application app) {
		appContext = app.getApplicationContext();
	}

	@Override
	@SuppressLint("DefaultLocale")
	public String getCurrentCountry() {
		String countryCode = getCountryFromPhoneNetwork();
		if (!TextUtils.isEmpty(countryCode)) return countryCode.toUpperCase();
		countryCode = getCountryFromSimCard();
		if (!TextUtils.isEmpty(countryCode)) return countryCode.toUpperCase();
		return Locale.getDefault().getCountry();
	}

	private String getCountryFromPhoneNetwork() {
		Object o = appContext.getSystemService(TELEPHONY_SERVICE);
		TelephonyManager tm = (TelephonyManager) o;
		return tm == null ? "" : tm.getNetworkCountryIso();
	}

	private String getCountryFromSimCard() {
		Object o = appContext.getSystemService(TELEPHONY_SERVICE);
		TelephonyManager tm = (TelephonyManager) o;
		return tm == null ? "" : tm.getSimCountryIso();
	}
}
