package com.professor.zerion.android.selection;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.accessibility.RowReading;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAccessibilityManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import androidx.appcompat.app.AppCompatActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class RowBindQuietTest {

	private ShadowAccessibilityManager talkBack;
	private FrameLayout row;
	private TextView words;

	@Before
	public void setUp() {
		AppCompatActivity activity = Robolectric.buildActivity(
				SelectionBarTest.Probe.class).setup().get();
		talkBack = shadowOf((AccessibilityManager) activity.getSystemService(
				Context.ACCESSIBILITY_SERVICE));
		row = new FrameLayout(activity);
		words = new TextView(activity);
		words.setText("hello");
		words.setOnLongClickListener(v -> true);
		row.addView(words);
		activity.setContentView(row);
		idle();
		talkBack.setEnabled(true);
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private int changes() {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
		int n = 0;
		for (AccessibilityEvent e : talkBack.getSentAccessibilityEvents()) {
			if (e.getEventType()
					== AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
				n++;
			}
		}
		return n;
	}

	private static List<String> labels(View v) {
		List<String> out = new ArrayList<>();
		for (AccessibilityNodeInfo.AccessibilityAction a :
				v.createAccessibilityNodeInfo().getActionList()) {
			if (a.getId() == R.id.action_safety_code) {
				out.add(String.valueOf(a.getLabel()));
			}
		}
		return out;
	}

	private void bind(Runnable code) {
		RowReading.oneItem(row);
		RowReading.partOf(words);
		RowActions.extra(row, R.id.action_safety_code, "Safety code", code);
	}

	@Test
	public void bindingTheSameRowAgainTellsTalkBackNothing() {
		List<String> ran = new ArrayList<>();
		bind(() -> ran.add("first"));
		int once = changes();
		bind(() -> ran.add("second"));
		bind(() -> ran.add("third"));
		assertEquals("an unchanged rebind is quiet", once, changes());
		assertEquals("[Safety code]", labels(row).toString());
		assertTrue(row.performAccessibilityAction(R.id.action_safety_code,
				null));
		assertEquals("the latest bind's code runs", "[third]",
				ran.toString());
		assertFalse(words.createAccessibilityNodeInfo().isLongClickable());
	}

	@Nullable
	private static String longPressLabel(View v) {
		for (AccessibilityNodeInfo.AccessibilityAction a :
				v.createAccessibilityNodeInfo().getActionList()) {
			if (a.getId() == AccessibilityNodeInfo.ACTION_LONG_CLICK) {
				return String.valueOf(a.getLabel());
			}
		}
		return null;
	}

	@Test
	public void namingTheLongPressAgainTellsTalkBackNothing() {
		List<String> held = new ArrayList<>();
		row.setOnLongClickListener(v -> {
			held.add("held");
			return true;
		});
		SelectionRows.nameLongPress(row);
		int once = changes();
		SelectionRows.nameLongPress(row);
		SelectionRows.nameLongPress(row);
		assertEquals("an unchanged name is quiet", once, changes());
		assertEquals("Select", longPressLabel(row));
		assertTrue(row.performAccessibilityAction(
				AccessibilityNodeInfo.ACTION_LONG_CLICK, null));
		assertEquals("[held]", held.toString());
	}

	@Test
	public void anUnselectableRowLosesTheNameAndCanGetItBack() {
		row.setOnLongClickListener(v -> true);
		SelectionRows.nameLongPress(row);
		SelectionRows.notSelectable(row);
		assertNull(longPressLabel(row));
		int gone = changes();
		SelectionRows.notSelectable(row);
		assertEquals("an unchanged unselectable row is quiet", gone,
				changes());
		row.setOnLongClickListener(v -> true);
		SelectionRows.nameLongPress(row);
		assertEquals("Select", longPressLabel(row));
		assertTrue(changes() > gone);
	}

	@Test
	public void aChangedOrRemovedExtraIsStillPassedOn() {
		bind(() -> {
		});
		int once = changes();
		RowActions.extra(row, R.id.action_safety_code, null, null);
		assertTrue(labels(row).isEmpty());
		assertTrue(changes() > once);
		assertFalse(row.performAccessibilityAction(R.id.action_safety_code,
				null));
		RowActions.extra(row, R.id.action_safety_code, "Code", () -> {
		});
		assertEquals("[Code]", labels(row).toString());
	}
}
