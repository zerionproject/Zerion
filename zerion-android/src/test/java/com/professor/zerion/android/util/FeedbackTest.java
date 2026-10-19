package com.professor.zerion.android.util;

import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.material.snackbar.Snackbar;
import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

import androidx.appcompat.app.AppCompatActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class FeedbackTest {

	static {
		TestAndroidKeyStore.register();
	}

	private AppCompatActivity activity;
	private View composer;

	private void open(boolean composerShown) {
		activity = Robolectric.buildActivity(AppCompatActivity.class).setup()
				.get();
		activity.setTheme(R.style.ZerionTheme_NoActionBar);
		FrameLayout root = new FrameLayout(activity);
		composer = new View(activity);
		composer.setVisibility(composerShown ? View.VISIBLE : View.GONE);
		root.addView(composer, new FrameLayout.LayoutParams(100, 100));
		activity.setContentView(root);
		idle();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	@Test
	public void aFailedSendSaysHowManyAndOffersRetryAboveTheComposer() {
		open(true);
		AtomicInteger retried = new AtomicInteger();

		Snackbar s = Feedback.couldNotSend(composer, 2,
				retried::incrementAndGet);
		idle();

		assertEquals(activity.getResources().getQuantityString(
				R.plurals.message_state_could_not_send, 2, 2),
				SnackbarProbe.text(activity));
		assertSame("the bar sits above the composer", composer,
				s.getAnchorView());
		TextView action = SnackbarProbe.action(activity);
		assertNotNull(action);
		assertEquals(activity.getString(R.string.message_state_retry),
				action.getText().toString());
		action.performClick();
		assertEquals(1, retried.get());
	}

	@Test
	public void aNoticeHasNoActionAndIsNotAnchoredToAHiddenComposer() {
		open(false);

		Snackbar s = Feedback.show(composer, "Your post will be sent later.");
		idle();

		assertEquals("Your post will be sent later.",
				SnackbarProbe.text(activity));
		assertNull(SnackbarProbe.action(activity));
		assertNull(s.getAnchorView());
	}
}
