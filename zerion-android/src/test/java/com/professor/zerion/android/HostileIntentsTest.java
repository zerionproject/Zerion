package com.professor.zerion.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import com.professor.zerion.android.channel.ChannelInviteHandlerActivity;
import com.professor.zerion.android.navdrawer.NavDrawerActivity;
import com.professor.zerion.android.panic.PanicResponderActivity;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.splash.SplashScreenActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.conversation.ConversationActivity.CONTACT_ID;
import static org.junit.Assert.assertTrue;

/**
 * Every exported activity is started with intents any installed app can
 * send: no action, a view intent with a malformed or oversized URI, extras of
 * the wrong type and size, a share intent with an oversized text, and the
 * panic trigger action from an untrusted sender. None may crash, and the
 * panic responder must finish without acting on an untrusted trigger.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class HostileIntentsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String PANIC_TRIGGER =
			"info.guardianproject.panic.action.TRIGGER";

	@Test
	public void exportedActivitiesSurviveHostileIntents() {
		List<Class<? extends Activity>> exported = new ArrayList<>();
		exported.add(SplashScreenActivity.class);
		exported.add(NavDrawerActivity.class);
		exported.add(ChannelInviteHandlerActivity.class);
		exported.add(PanicResponderActivity.class);
		for (Class<? extends Activity> cls : exported) {
			for (Intent intent : hostileIntents()) {
				launch(cls, intent);
			}
		}
	}

	@Test
	public void panicTriggerFromAnUntrustedSenderOnlyFinishes() {
		Intent trigger = new Intent(PANIC_TRIGGER);
		trigger.putExtra("info.guardianproject.panic.extra.PACKAGE_NAME",
				"com.example.attacker");
		Activity a = launch(PanicResponderActivity.class, trigger);
		assertTrue(a.isFinishing());
	}

	private static List<Intent> hostileIntents() {
		StringBuilder huge = new StringBuilder();
		for (int i = 0; i < 20_000; i++) huge.append('a');
		List<Intent> intents = new ArrayList<>();
		intents.add(new Intent());
		intents.add(new Intent(Intent.ACTION_VIEW,
				Uri.parse("zerion://channel/invite?x=%00%FF&y=")));
		intents.add(new Intent(Intent.ACTION_VIEW,
				Uri.parse("zerion://channel/" + huge)));
		intents.add(new Intent(Intent.ACTION_VIEW, Uri.parse("zerion://")));
		intents.add(new Intent(Intent.ACTION_VIEW, Uri.parse("zerion:")));
		intents.add(new Intent(Intent.ACTION_VIEW,
				Uri.parse("https://example.org/" + huge)));
		Intent wrongTypes = new Intent(Intent.ACTION_VIEW,
				Uri.parse("zerion://channel/x"));
		wrongTypes.putExtra(CONTACT_ID, "not an int");
		wrongTypes.putExtra("groupId", new byte[1]);
		wrongTypes.putExtra("contactId", new int[] {1, 2});
		wrongTypes.putExtra("blob", new byte[200_000]);
		intents.add(wrongTypes);
		Intent send = new Intent(Intent.ACTION_SEND);
		send.setType("text/plain");
		send.putExtra(Intent.EXTRA_TEXT, huge.toString());
		intents.add(send);
		Intent panic = new Intent(PANIC_TRIGGER);
		panic.putExtra("info.guardianproject.panic.extra.PACKAGE_NAME",
				huge.toString());
		intents.add(panic);
		return intents;
	}

	private static <T extends Activity> T launch(Class<T> cls, Intent intent) {
		ActivityController<T> controller = null;
		try {
			controller = Robolectric.buildActivity(cls, intent);
			controller.create().start().resume();
			return controller.get();
		} catch (RuntimeException e) {
			throw new AssertionError(cls.getSimpleName() + " failed on "
					+ intent, e);
		} finally {
			if (controller != null) {
				try {
					controller.pause().stop().destroy();
				} catch (RuntimeException ignored) {
				}
			}
		}
	}
}
