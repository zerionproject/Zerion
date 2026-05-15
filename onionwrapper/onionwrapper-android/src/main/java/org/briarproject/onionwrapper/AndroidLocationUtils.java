package org.briarproject.onionwrapper;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Locale;
import java.util.logging.Logger;

import javax.inject.Inject;

import static android.content.Context.TELEPHONY_SERVICE;

@NotNullByDefault
class AndroidLocationUtils implements LocationUtils {

	private static final Logger LOG =
			Logger.getLogger(AndroidLocationUtils.class.getName());

	private final Context appContext;

	@Inject
	AndroidLocationUtils(Application app) {
		appContext = app.getApplicationContext();
	}

	@Override
	@SuppressLint("DefaultLocale")
	public String getCurrentCountry() {
		String countryCode = getCountryFromPhoneNetwork();
		if (!TextUtils.isEmpty(countryCode)) return countryCode.toUpperCase();
		LOG.info("Falling back to SIM card country");
		countryCode = getCountryFromSimCard();
		if (!TextUtils.isEmpty(countryCode)) return countryCode.toUpperCase();
		LOG.info("Falling back to user-defined locale");
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
