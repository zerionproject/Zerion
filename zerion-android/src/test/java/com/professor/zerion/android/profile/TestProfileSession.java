package com.professor.zerion.android.profile;

import android.content.Context;
import android.content.SharedPreferences;

import com.professor.zerion.android.AndroidComponent;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.zerionproject.core.api.account.AccountManager;

import static org.junit.Assert.assertTrue;

public final class TestProfileSession {

	static {
		TestAndroidKeyStore.register();
	}

	private TestProfileSession() {
	}

	public static SharedPreferences signedInPreferences(Context app) {
		AndroidComponent component = AppModule.getAndroidComponent(app);
		AccountManager accountManager = component.accountManager();
		if (!accountManager.hasDatabaseKey()) {
			assertTrue(accountManager.createAccount("Test",
					"test profile password".toCharArray()));
		}
		return component.profilePreferences();
	}
}
