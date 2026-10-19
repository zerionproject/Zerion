package com.professor.zerion.android.accessibility;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.message.MessageState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
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
public class RowStatesTogetherTest {

	private Activity activity;
	private ShadowAccessibilityManager talkBack;
	private final List<TextView> rows = new ArrayList<>();

	@Before
	public void setUp() {
		activity = Robolectric.buildActivity(Activity.class).setup().get();
		talkBack = shadowOf((AccessibilityManager) activity.getSystemService(
				Context.ACCESSIBILITY_SERVICE));
		talkBack.setEnabled(true);
		FrameLayout root = new FrameLayout(activity);
		activity.setContentView(root);
		for (int i = 0; i < 4; i++) {
			TextView row = new TextView(activity);
			root.addView(row);
			rows.add(row);
		}
		shadowOf(Looper.getMainLooper()).idle();
	}

	private List<String> saidAfterAWhile() {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
		List<String> out = new ArrayList<>();
		for (AccessibilityEvent e : talkBack.getSentAccessibilityEvents()) {
			if (e.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
				out.add(e.getText().toString());
			}
		}
		return out;
	}

	private void all(MessageState state) {
		RowStates states = RowStates.of(rows.get(0));
		for (int i = 0; i < rows.size(); i++) {
			String subject = "message " + i;
			states.saw(rows.get(i), "row" + i, state, () -> subject);
		}
	}

	@Test
	public void manyRowsGettingThroughTogetherAreSaidOnce() {
		all(MessageState.NOT_SENT);
		all(MessageState.SENDING);
		all(MessageState.WAITING);
		List<String> said = saidAfterAWhile();
		assertEquals("[[" + activity.getString(R.string.message_state_waiting)
				+ "]]", said.toString());
	}

	@Test
	public void manyRowsTurningNotSentTogetherAreCountedOnce() {
		all(MessageState.WAITING);
		all(MessageState.NOT_SENT);
		List<String> said = saidAfterAWhile();
		assertEquals("[[" + activity.getResources().getQuantityString(
				R.plurals.message_state_could_not_send, 4, 4) + "]]",
				said.toString());
	}

	@Test
	public void oneRowStillSaysItsWords() {
		RowStates states = RowStates.of(rows.get(0));
		states.saw(rows.get(0), "one", MessageState.WAITING, () -> "see you");
		states.saw(rows.get(1), "two", MessageState.WAITING, () -> "later");
		states.saw(rows.get(0), "one", MessageState.NOT_SENT, () -> "see you");
		List<String> said = saidAfterAWhile();
		assertEquals("[[" + activity.getString(
				R.string.message_state_action_description,
				activity.getString(R.string.message_state_not_sent),
				"see you") + "]]", said.toString());
	}

	@Test
	public void aStateThatMovesOnWithinTheWindowIsNotSaid() {
		RowStates states = RowStates.of(rows.get(0));
		states.saw(rows.get(0), "one", MessageState.WAITING, () -> "dinner");
		states.saw(rows.get(0), "one", MessageState.NOT_SENT, () -> "dinner");
		states.saw(rows.get(0), "one", MessageState.SENDING, () -> "dinner");
		assertEquals("the row shows Sending, so nothing is said", "[]",
				saidAfterAWhile().toString());
	}

	@Test
	public void mixedStatesAreSaidApartNotSentFirst() {
		RowStates states = RowStates.of(rows.get(0));
		states.saw(rows.get(0), "a", MessageState.NOT_SENT, () -> "a");
		states.saw(rows.get(1), "b", MessageState.WAITING, () -> "b");
		states.saw(rows.get(2), "c", MessageState.WAITING, () -> "c");
		states.saw(rows.get(0), "a", MessageState.SENDING, () -> "a");
		states.saw(rows.get(0), "a", MessageState.WAITING, () -> "a");
		states.saw(rows.get(1), "b", MessageState.NOT_SENT, () -> "b");
		states.saw(rows.get(2), "c", MessageState.NOT_SENT, () -> "c");
		List<String> said = saidAfterAWhile();
		assertEquals("[[" + activity.getResources().getQuantityString(
				R.plurals.message_state_could_not_send, 2, 2) + "], ["
				+ activity.getString(R.string.message_state_waiting) + "]]",
				said.toString());
	}

	@Test
	public void changesFarApartAreSaidEachOnTheirOwn() {
		RowStates states = RowStates.of(rows.get(0));
		states.saw(rows.get(0), "one", MessageState.WAITING, () -> "see you");
		states.saw(rows.get(1), "two", MessageState.WAITING, () -> "later");
		states.saw(rows.get(0), "one", MessageState.NOT_SENT, () -> "see you");
		saidAfterAWhile();
		states.saw(rows.get(1), "two", MessageState.NOT_SENT, () -> "later");
		List<String> said = saidAfterAWhile();
		assertEquals(2, said.size());
		assertEquals("[" + activity.getString(
				R.string.message_state_action_description,
				activity.getString(R.string.message_state_not_sent),
				"later") + "]", said.get(1));
	}
}
