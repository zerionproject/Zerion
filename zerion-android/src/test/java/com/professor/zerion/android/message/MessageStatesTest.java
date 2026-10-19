package com.professor.zerion.android.message;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

import androidx.core.view.ViewCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class MessageStatesTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Context ctx = new ContextThemeWrapper(
			RuntimeEnvironment.getApplication(),
			R.style.ZerionTheme_NoActionBar);

	private TextView meta() {
		TextView meta = new TextView(ctx);
		meta.setTextColor(0xFFFFFFFF);
		return meta;
	}

	@Test
	public void theMetaLineIsTimeThenStateWithAnIcon() {
		TextView meta = meta();
		MessageStates.bind(meta, "18:04", MessageState.SENDING);

		assertEquals("18:04 · sending…", meta.getText().toString());
		Drawable icon = meta.getCompoundDrawablesRelative()[2];
		assertNotNull("the state has an icon", icon);
	}

	@Test
	public void everyStateIsSpokenWithTheWordsItShows() {
		TextView meta = meta();
		for (MessageState state : MessageState.values()) {
			MessageStates.bind(meta, "18:04", state);
			String shown = ctx.getString(state.getLabel());
			assertEquals("18:04 · " + shown, meta.getText().toString());
			assertEquals(state.name(), "18:04, " + shown,
					meta.getContentDescription().toString());
		}
	}

	@Test
	public void aRowWithoutAStateShowsOnlyTheTime() {
		TextView meta = meta();
		MessageStates.bind(meta, "18:04", MessageState.NOT_SENT);
		MessageStates.bind(meta, "18:05", null);

		assertEquals("18:05", meta.getText().toString());
		assertNull(meta.getContentDescription());
		assertNull(meta.getCompoundDrawablesRelative()[2]);
	}

	@Test
	public void rowsNeverAnnounceThemselvesSoAFailureIsHeardOnce() {
		TextView meta = meta();
		MessageStates.bind(meta, "18:04", MessageState.SENDING);
		MessageStates.bind(meta, "18:04", MessageState.NOT_SENT);
		assertEquals(ViewCompat.ACCESSIBILITY_LIVE_REGION_NONE,
				ViewCompat.getAccessibilityLiveRegion(meta));
	}

	@Test
	public void withoutAComposerTheEditActionSaysCopy() {
		View actions = LayoutInflater.from(ctx).inflate(
				R.layout.view_message_not_sent_actions, new FrameLayout(ctx),
				false);
		TextView edit = actions.findViewById(R.id.messageEditButton);
		MessageStates.actions(actions, "note", () -> {
		}, true, null, null);
		assertEquals(ctx.getString(R.string.copy_text), edit.getText()
				.toString());
		MessageStates.actions(actions, "note", () -> {
		}, false, null, null);
		assertEquals(ctx.getString(R.string.action_edit_message),
				edit.getText().toString());
		assertTrue(edit.isEnabled());
	}

	@Test
	public void notSentIsOutlinedAndItsActionsNameTheMessage() {
		FrameLayout row = new FrameLayout(ctx);
		View bubble = new FrameLayout(ctx);
		row.addView(bubble);
		View actions = LayoutInflater.from(ctx).inflate(
				R.layout.view_message_not_sent_actions, row, false);
		row.addView(actions);
		assertEquals(View.GONE, actions.getVisibility());

		AtomicInteger retried = new AtomicInteger();
		MessageStates.outline(bubble, true,
				R.drawable.bg_message_not_sent_out);
		MessageStates.actions(actions, "See you at ten", null,
				retried::incrementAndGet, () -> {
				});
		assertNotNull(bubble.getForeground());
		assertEquals(View.VISIBLE, actions.getVisibility());
		assertEquals(View.GONE, actions.findViewById(R.id.messageEditButton)
				.getVisibility());
		View retry = actions.findViewById(R.id.messageRetryButton);
		retry.performClick();
		assertEquals(1, retried.get());
		assertEquals(ctx.getString(R.string.message_state_retry)
				+ ": See you at ten", retry.getContentDescription());
		assertTrue(actions.findViewById(R.id.messageDeleteButton)
				.getContentDescription().toString()
				.endsWith(": See you at ten"));

		assertFalse("a second tap waits for the next bind",
				retry.isEnabled());

		MessageStates.outline(bubble, false,
				R.drawable.bg_message_not_sent_out);
		MessageStates.hideActions(actions);
		assertNull(bubble.getForeground());
		assertEquals(View.GONE, actions.getVisibility());
	}

	@Test
	public void twoNoticesAreJoinedTheWayTheLanguageJoinsThem() {
		Context app = RuntimeEnvironment.getApplication();
		PendingSends.Notice both = PendingSends.Notice.of(
				R.string.message_state_deleted).then(PendingSends.Notice.of(
				R.string.message_state_maybe_sent));
		assertEquals("Message deleted This may already have been sent.",
				both.text(app).toString());

		Resources base = app.getResources();
		Resources reordered = new Resources(base.getAssets(),
				base.getDisplayMetrics(), base.getConfiguration()) {
			@Override
			public String getString(int id, Object... args) {
				if (id == R.string.message_state_notice_join) {
					return args[1] + " | " + args[0];
				}
				return super.getString(id, args);
			}
		};
		Context translated = new ContextWrapper(app) {
			@Override
			public Resources getResources() {
				return reordered;
			}
		};

		assertEquals("This may already have been sent. | Message deleted",
				both.text(translated).toString());
	}
}
