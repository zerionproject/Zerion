package com.professor.zerion.android.navdrawer;

import android.os.Looper;

import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.plugin.event.TorHelperTransportsPromptEvent;

import java.util.List;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static android.content.DialogInterface.BUTTON_NEGATIVE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class HelperTransportsPromptTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static int prompts(NavDrawerActivity a) {
		int n = 0;
		List<Fragment> fragments =
				a.getSupportFragmentManager().getFragments();
		for (Fragment f : fragments) {
			if (f instanceof HelperTransportsDialogFragment) n++;
		}
		return n;
	}

	private static HelperTransportsDialogFragment prompt(
			NavDrawerActivity a) {
		return (HelperTransportsDialogFragment) a.getSupportFragmentManager()
				.findFragmentByTag(HelperTransportsDialogFragment.TAG);
	}

	private static void ask(String country) {
		ZerionApplication app = ApplicationProvider.getApplicationContext();
		app.getApplicationComponent().eventBus()
				.broadcast(new TorHelperTransportsPromptEvent(country));
		idle();
	}

	@Test
	public void thePromptIsADialogFragmentThatSurvivesRotationOnce() {
		ActivityController<NavDrawerActivity> c =
				Robolectric.buildActivity(NavDrawerActivity.class).setup();
		idle();
		NavDrawerActivity a = c.get();
		assertNull(prompt(a));
		ask("RU");
		HelperTransportsDialogFragment f = prompt(a);
		assertNotNull(f);
		assertEquals("RU", f.country());
		assertTrue(f.getDialog() != null && f.getDialog().isShowing());
		assertEquals(1, prompts(a));
		ask("RU");
		assertEquals("a second event does not stack a prompt", 1,
				prompts(a));

		c.recreate();
		idle();
		NavDrawerActivity b = c.get();
		assertEquals("exactly one prompt after rotation", 1, prompts(b));
		HelperTransportsDialogFragment g = prompt(b);
		assertNotNull(g);
		assertTrue(g.getDialog() != null && g.getDialog().isShowing());
		assertEquals("RU", g.country());
	}

	@Test
	public void anAnswerGivenElsewhereClosesThePrompt() {
		ActivityController<NavDrawerActivity> c =
				Robolectric.buildActivity(NavDrawerActivity.class).setup();
		idle();
		NavDrawerActivity a = c.get();
		ask("CN");
		assertNotNull(prompt(a));

		a.answerHelperTransports("CN", true);
		idle();

		assertNull("the prompt stayed after its answer", prompt(a));
	}

	@Test
	public void decliningClosesThePromptAndItDoesNotComeBackOnItsOwn() {
		ActivityController<NavDrawerActivity> c =
				Robolectric.buildActivity(NavDrawerActivity.class).setup();
		idle();
		NavDrawerActivity a = c.get();
		ask("CN");
		HelperTransportsDialogFragment f = prompt(a);
		assertNotNull(f);
		((AlertDialog) f.getDialog()).getButton(BUTTON_NEGATIVE)
				.performClick();
		idle();
		assertNull(prompt(a));
		c.recreate();
		idle();
		assertNull(prompt(c.get()));
	}
}
