package com.professor.zerion.android.security;

import android.content.Context;
import android.content.SharedPreferences;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class HardenedModeEvaluator {

	public static final String PREF_HARDENED_BOOT =
			"pref_hardened_boot";
	public static final String PREF_HARDENED_TAMPER =
			"pref_hardened_tamper";
	public static final String PREF_HARDENED_USB_PANIC =
			"pref_hardened_usb_panic";
	public static final String PREF_HARDENED_USB_PANIC_WIPE =
			"pref_hardened_usb_panic_wipe";

	private HardenedModeEvaluator() {
	}

	public static int evaluate(SharedPreferences uiPrefs, Context ctx) {
		if (uiPrefs.getBoolean(PREF_HARDENED_BOOT, false)) {
			int r = SecureBootGuard.evaluateStrictBoot();
			if (r != SecureBootGuard.RESULT_OK) return r;
		}
		if (uiPrefs.getBoolean(PREF_HARDENED_TAMPER, true)) {
			int r = SecureBootGuard.evaluateAntiTamper();
			if (r != SecureBootGuard.RESULT_OK) return r;
			if (SecureBootGuard.adbEnabled(ctx)) {
				return SecureBootGuard.RESULT_ADB_DAEMON_LISTENING;
			}
			r = SecureBootGuard.verifyAppSignature(ctx);
			if (r != SecureBootGuard.RESULT_OK) return r;
		}
		return SecureBootGuard.RESULT_OK;
	}

	public static boolean usbPanicArmed(SharedPreferences uiPrefs) {
		return uiPrefs.getBoolean(PREF_HARDENED_USB_PANIC, false);
	}

	public static boolean usbPanicWipesAccount(SharedPreferences uiPrefs) {
		return uiPrefs.getBoolean(PREF_HARDENED_USB_PANIC_WIPE, false);
	}
}
