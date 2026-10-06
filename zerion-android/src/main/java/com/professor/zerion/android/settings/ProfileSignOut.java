package com.professor.zerion.android.settings;

import android.content.Context;
import android.content.Intent;

import com.professor.zerion.android.ZerionService;

import org.briarproject.nullsafety.NotNullByDefault;

import static com.professor.zerion.android.AppModule.getAndroidComponent;

@NotNullByDefault
final class ProfileSignOut {

	private ProfileSignOut() {
	}

	static void signOutAndRestart(Context appContext) {
		ProfilesFragment.scheduleRestart(appContext);
		com.professor.zerion.android.util.SecureClipboard.clearOnSignOut(
				appContext);
		com.professor.zerion.android.conversation.voice.VoiceCallKeyHolder
				.clear();
		Intent exit = new Intent(appContext, ZerionService.class);
		exit.setAction(ZerionService.ACTION_EXIT);
		try {
			appContext.startService(exit);
		} catch (RuntimeException e) {
			stopDirectly(appContext);
		}
	}

	private static void stopDirectly(Context appContext) {
		Thread t = new Thread(() -> {
			try {
				getAndroidComponent(appContext).lifecycleManager()
						.stopServices();
				getAndroidComponent(appContext).lifecycleManager()
						.waitForShutdown();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (RuntimeException ignored) {
			}
			System.exit(0);
		}, "ProfileSignOut");
		t.setDaemon(true);
		t.start();
	}
}
