package com.professor.zerion.android.settings;

import android.content.Context;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.transport.TorBridgeConfigurator;

import java.util.Locale;

import javax.annotation.Nullable;

@NotNullByDefault
final class HelperTransportsSummary {

	private HelperTransportsSummary() {
	}

	static String of(Context ctx, @Nullable Integer choice,
			@Nullable String country) {
		int c = choice == null ? TorConstants.HELPER_TRANSPORTS_ASK : choice;
		boolean everywhere = country == null || country.trim().isEmpty()
				|| TorBridgeConfigurator.ANY_COUNTRY.equals(country.trim());
		if (c == TorConstants.HELPER_TRANSPORTS_USE) {
			return everywhere ? ctx.getString(R.string.tor_helper_summary_use)
					: ctx.getString(R.string.tor_helper_summary_use_in,
					displayName(country));
		}
		if (c == TorConstants.HELPER_TRANSPORTS_OFF) {
			return everywhere ? ctx.getString(R.string.tor_helper_summary_off)
					: ctx.getString(R.string.tor_helper_summary_off_in,
					displayName(country));
		}
		return ctx.getString(R.string.tor_helper_summary_ask);
	}

	static String displayName(@Nullable String country) {
		String code = country == null ? "" : country.trim().toUpperCase(Locale.US);
		if (!code.matches("[A-Z]{2}")) return code;
		String name = new Locale("", code).getDisplayCountry();
		return name.isEmpty() ? code : name;
	}
}
