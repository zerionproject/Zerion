package com.professor.zerion.android.accessibility;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAccessibilityManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import androidx.core.view.ViewCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.message.MessageState.DELIVERED;
import static com.professor.zerion.android.message.MessageState.NOT_SENT;
import static com.professor.zerion.android.message.MessageState.SENDING;
import static com.professor.zerion.android.message.MessageState.SENT;
import static com.professor.zerion.android.message.MessageState.WAITING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SpeechTest {

	private Activity activity;
	private ShadowAccessibilityManager talkBack;
	private TextView view;

	@Before
	public void setUp() {
		activity = Robolectric.buildActivity(Activity.class).setup().get();
		talkBack = shadowOf((AccessibilityManager) activity.getSystemService(
				Context.ACCESSIBILITY_SERVICE));
		talkBack.setEnabled(true);
		FrameLayout root = new FrameLayout(activity);
		activity.setContentView(root);
		view = new TextView(activity);
		root.addView(view);
		idle();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	static List<String> said(ShadowAccessibilityManager talkBack) {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
		List<String> out = new ArrayList<>();
		for (AccessibilityEvent e : talkBack.getSentAccessibilityEvents()) {
			if (e.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
				out.add(e.getText().toString());
			}
		}
		return out;
	}

	private int liveChanges() {
		idle();
		int n = 0;
		for (AccessibilityEvent e : talkBack.getSentAccessibilityEvents()) {
			if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
					&& e.getClassName() != null && e.getClassName().toString()
					.equals(TextView.class.getName())) {
				n++;
			}
		}
		return n;
	}

	@Test
	public void theFirstValueIsQuietAndEachRealChangeIsSaidOnce() {
		Announcer a = new Announcer();
		assertFalse(a.update(view, "connecting", "Connecting"));
		assertFalse("the same value again is a rebind",
				a.update(view, "connecting", "Connecting"));
		assertTrue(a.update(view, "connected", "Connected"));
		assertFalse(a.update(view, "connected", "Connected"));
		assertFalse("hiding the line says nothing",
				a.update(view, null, ""));
		assertTrue(a.update(view, "connected", "Connected"));
		assertEquals(2, said(talkBack).size());
		assertEquals("[Connected]", said(talkBack).get(0));
	}

	@Test
	public void nothingIsSaidWhenNoScreenReaderListens() {
		talkBack.setEnabled(false);
		Announcer a = new Announcer();
		a.update(view, 1, "one");
		a.update(view, 2, "two");
		talkBack.setEnabled(true);
		assertTrue(said(talkBack).isEmpty());
	}

	@Test
	public void aLiveTextOnlyChangesWhenItsWordsChange() {
		Speech.politely(view);
		assertEquals(ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE,
				ViewCompat.getAccessibilityLiveRegion(view));
		int before = liveChanges();
		assertTrue(Speech.setText(view, "Ringing"));
		int once = liveChanges();
		assertEquals(before + 1, once);
		assertFalse(Speech.setText(view, "Ringing"));
		assertFalse(Speech.setText(view, new StringBuilder("Ringing")));
		assertEquals("setting the same words again is not news", once,
				liveChanges());
	}

	@Test
	public void aSendThatFailsIsLeftToTheRetryMessage() {
		RowStates rows = new RowStates();
		assertNull("the first look is quiet", rows.change("a", SENDING));
		assertNull("the Retry message says this", rows.change("a", NOT_SENT));
		assertNull(rows.change("a", NOT_SENT));
		assertNull(rows.change("a", SENDING));
		assertSame(SENT, rows.change("a", SENT));
		assertNull("said once", rows.change("a", SENT));
		assertNull(rows.change("a", DELIVERED));
	}

	@Test
	public void aWaitingRowThatTurnsNotSentIsSaidAndSoIsItsWayBack() {
		RowStates rows = new RowStates();
		assertNull(rows.change("q", WAITING));
		assertSame(NOT_SENT, rows.change("q", NOT_SENT));
		assertNull(rows.change("q", NOT_SENT));
		assertSame(WAITING, rows.change("q", WAITING));
		assertNull(rows.change("q", WAITING));
	}

	@Test
	public void anOrdinarySendIsNeverSaid() {
		RowStates rows = new RowStates();
		assertNull(rows.change("b", SENDING));
		assertNull(rows.change("b", SENT));
		assertNull(rows.change("c", NOT_SENT));
		assertNull("a row first seen not sent", rows.change("c", NOT_SENT));
		assertNull(rows.change("d", null));
		assertNull(rows.change("d", null));
	}

	@Test
	public void aRowFirstSeenNotSentIsSaidWhenItGetsThrough() {
		RowStates rows = new RowStates();
		assertNull(rows.change("e", NOT_SENT));
		assertNull(rows.change("e", SENDING));
		assertSame(WAITING, rows.change("e", WAITING));
	}

	@Test
	public void theTrackerForgetsOldRows() {
		RowStates rows = new RowStates();
		rows.change("first", WAITING);
		for (int i = 0; i < RowStates.MAX_ROWS + 1; i++) {
			rows.change("row" + i, SENT);
		}
		assertNull("forgotten rows start quiet again",
				rows.change("first", NOT_SENT));
	}

	@Test
	public void aStateChangeIsSaidWithTheVisibleWordsOnly() {
		RowStates rows = RowStates.of(view);
		rows.saw(view, "p", WAITING, () -> "Dinner at eight");
		rows.saw(view, "p", NOT_SENT, () -> "Dinner at eight");
		rows.saw(view, "p", NOT_SENT, () -> "Dinner at eight");
		List<String> said = said(talkBack);
		assertEquals(1, said.size());
		assertEquals("[not sent: Dinner at eight]", said.get(0));
	}

	@Test
	public void eachScreenKeepsItsOwnRows() {
		TextView other = new TextView(activity);
		assertSame(RowStates.of(view), RowStates.of(other));
		Activity second = Robolectric.buildActivity(Activity.class).setup()
				.get();
		assertNotSame(RowStates.of(view), RowStates.of(new TextView(second)));
		assertNotSame(RowStates.of(new TextView(
						activity.getApplicationContext())),
				RowStates.of(new TextView(activity.getApplicationContext())));
	}

	@Test
	public void aLongSubjectIsShortenedLikeTheButtons() {
		RowStates rows = RowStates.of(view);
		StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 30; i++) longText.append("word ");
		rows.saw(view, "long", WAITING, longText::toString);
		rows.saw(view, "long", NOT_SENT, longText::toString);
		String said = said(talkBack).get(0);
		assertTrue(said, said.length() < 60);
		assertTrue(said, said.endsWith("…]"));
	}
}
