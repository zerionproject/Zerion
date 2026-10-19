package com.professor.zerion.android.accessibility;

import android.content.Context;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.message.MessageState;
import com.professor.zerion.android.selection.SelectionBarTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAccessibilityManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class RowStatesLifecycleTest {

	private static List<String> said(ShadowAccessibilityManager talkBack) {
		List<String> out = new ArrayList<>();
		for (AccessibilityEvent e : talkBack.getSentAccessibilityEvents()) {
			if (e.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
				out.add(e.getText().toString());
			}
		}
		return out;
	}

	@Test
	public void leavingTheScreenSaysWhatWasWaitingAndNothingAfter() {
		ActivityController<SelectionBarTest.Probe> c = Robolectric
				.buildActivity(SelectionBarTest.Probe.class).setup();
		SelectionBarTest.Probe activity = c.get();
		FrameLayout root = new FrameLayout(activity);
		TextView row = new TextView(activity);
		root.addView(row);
		activity.setContentView(root);
		shadowOf(Looper.getMainLooper()).idle();
		ShadowAccessibilityManager talkBack = shadowOf((AccessibilityManager)
				activity.getSystemService(Context.ACCESSIBILITY_SERVICE));
		talkBack.setEnabled(true);

		RowStates states = RowStates.of(row);
		states.saw(row, "one", MessageState.WAITING, () -> "dinner");
		states.saw(row, "one", MessageState.NOT_SENT, () -> "dinner");
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
		c.pause();
		shadowOf(Looper.getMainLooper()).idle();
		String notSent = "[" + activity.getString(
				R.string.message_state_action_description,
				activity.getString(R.string.message_state_not_sent),
				"dinner") + "]";
		assertEquals("[" + notSent + "]", said(talkBack).toString());

		states.saw(row, "two", MessageState.WAITING, () -> "lunch");
		states.saw(row, "two", MessageState.NOT_SENT, () -> "lunch");
		c.stop().destroy();
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
		assertEquals("nothing is said after the screen is gone",
				"[" + notSent + "]", said(talkBack).toString());
	}
}
