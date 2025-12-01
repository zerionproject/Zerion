package com.professor.zerion.android.panic;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;
import info.guardianproject.GuardianProjectRSA4096;
import info.guardianproject.panic.Panic;
import info.guardianproject.panic.PanicResponder;
import info.guardianproject.trustedintents.TrustedIntents;

import static com.professor.zerion.android.panic.PanicPreferencesFragment.KEY_LOCK;
import static com.professor.zerion.android.panic.PanicPreferencesFragment.KEY_PURGE;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class PanicResponderActivity extends ZerionActivity {

	@Inject
	@AppModule.UiPrefs
	SharedPreferences uiPrefs;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		TrustedIntents trustedIntents = TrustedIntents.get(this);
		trustedIntents.addTrustedSigner(GuardianProjectRSA4096.class);
		trustedIntents.addTrustedSigner(FDroidSignaturePin.class);

		Intent intent = trustedIntents.getIntentFromTrustedSender(this);
		if (intent != null) {
			if (Panic.isTriggerIntent(intent)) {
				if (PanicResponder.receivedTriggerFromConnectedApp(this)) {
					if (uiPrefs.getBoolean(KEY_PURGE, false)) {
						signOut(true, true);
					} else if (uiPrefs.getBoolean(KEY_LOCK, true)) {
						signOut(true, false);
					}
				} else if (uiPrefs.getBoolean(KEY_LOCK, true)) {
					signOut(true, false);
				}
			}
		}
		finishAndRemoveTask();
	}

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}
}
