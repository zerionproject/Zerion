package com.professor.zerion.android.selection;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAccessibilityManager;

import java.util.EnumSet;
import java.util.Set;

import androidx.core.view.ViewCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SelectionCountSpeechTest {

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static int titleChanges(ShadowAccessibilityManager talkBack,
			TextView title) {
		int n = 0;
		for (AccessibilityEvent e : talkBack.getSentAccessibilityEvents()) {
			if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
					&& (e.getContentChangeTypes()
					& AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT) != 0
					&& String.valueOf(title.getAccessibilityClassName())
					.equals(String.valueOf(e.getClassName()))) {
				n++;
			}
		}
		return n;
	}

	private static int paneChanges(ShadowAccessibilityManager talkBack) {
		int n = 0;
		for (AccessibilityEvent e : talkBack.getSentAccessibilityEvents()) {
			if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
					&& (e.getContentChangeTypes()
					& AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE) != 0) {
				n++;
			}
		}
		return n;
	}

	@Test
	public void theCountIsSaidOnceWhenTheBarAppearsAndOnceForEachChange() {
		SelectionBarTest.Probe activity = Robolectric.buildActivity(
				SelectionBarTest.Probe.class).setup().get();
		SelectionBar bar = new SelectionBar(activity, new SelectionBar.Host() {
			@Override
			public Set<SelectionAction> offeredActions() {
				return EnumSet.of(SelectionAction.COPY);
			}

			@Override
			public void onSelectionAction(SelectionAction action) {
			}

			@Override
			public void onSelectionClosed() {
			}
		});
		ShadowAccessibilityManager talkBack = shadowOf((AccessibilityManager)
				activity.getSystemService(Context.ACCESSIBILITY_SERVICE));
		talkBack.setEnabled(true);
		bar.show(1);
		idle();
		TextView title = bar.getTitleView();
		assertNotNull(title);
		View pane = (View) title.getParent();
		assertEquals("1 selected",
				String.valueOf(ViewCompat.getAccessibilityPaneTitle(pane)));
		int panes = paneChanges(talkBack);

		bar.show(2);
		bar.show(3);
		idle();
		assertEquals("a new count is not a new pane", panes,
				paneChanges(talkBack));
		assertEquals("1 selected",
				String.valueOf(ViewCompat.getAccessibilityPaneTitle(pane)));
		assertEquals("each new count is said by the live region", 2,
				titleChanges(talkBack, title));

		bar.close();
		idle();
		bar.show(2);
		idle();
		TextView again = bar.getTitleView();
		assertNotNull(again);
		assertEquals("2 selected", String.valueOf(
				ViewCompat.getAccessibilityPaneTitle(
						(View) again.getParent())));
	}

	@Test
	public void theCountIsAPoliteLiveRegionThatSpeaksOnlyWhenItChanges() {
		SelectionBarTest.Probe activity = Robolectric.buildActivity(
				SelectionBarTest.Probe.class).setup().get();
		SelectionBar bar = new SelectionBar(activity, new SelectionBar.Host() {
			@Override
			public Set<SelectionAction> offeredActions() {
				return EnumSet.of(SelectionAction.COPY);
			}

			@Override
			public void onSelectionAction(SelectionAction action) {
			}

			@Override
			public void onSelectionClosed() {
			}
		});
		ShadowAccessibilityManager talkBack = shadowOf((AccessibilityManager)
				activity.getSystemService(Context.ACCESSIBILITY_SERVICE));
		talkBack.setEnabled(true);
		bar.show(1);
		idle();
		TextView title = bar.getTitleView();
		assertNotNull(title);
		assertEquals(ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE,
				ViewCompat.getAccessibilityLiveRegion(title));
		assertEquals("opening is told by the pane, not by the count", 0,
				titleChanges(talkBack, title));

		bar.show(2);
		idle();
		assertEquals("2 selected", title.getText().toString());
		assertEquals(1, titleChanges(talkBack, title));
		bar.show(2);
		bar.refresh();
		idle();
		assertEquals("the same count again says nothing", 1,
				titleChanges(talkBack, title));
		bar.show(3);
		idle();
		assertEquals(2, titleChanges(talkBack, title));
	}
}
