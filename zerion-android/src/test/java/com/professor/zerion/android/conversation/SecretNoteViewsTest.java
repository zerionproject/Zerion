package com.professor.zerion.android.conversation;

import android.content.Context;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import androidx.lifecycle.MutableLiveData;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SecretNoteViewsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String FIRST = "meet at the old mill";
	private static final String SECOND = "bring the blue folder";

	private Context ctx;
	private ConversationListener listener;
	private ConversationAdapter adapter;

	@Before
	public void setUp() {
		ctx = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
				R.style.ZerionTheme_NoActionBar);
		listener = mock(ConversationListener.class);
		adapter = new ConversationAdapter(ctx, listener,
				mock(org.zerionproject.app.api.attachment.AttachmentReader.class),
				Runnable::run, mock(com.professor.zerion.android.conversation
				.voice.VoiceMemoKeys.class));
	}

	private static ConversationSecretNoteItem note(String content,
			boolean local) {
		PrivateMessageHeader h = new PrivateMessageHeader(
				new MessageId(getRandomId()), new GroupId(getRandomId()),
				1_000L, local, true, true, false, true,
				Collections.emptyList(), -1L);
		ConversationSecretNoteItem item = new ConversationSecretNoteItem(h,
				new MutableLiveData<>("Bob"));
		item.setText(ConversationSecretNoteItem.wrapContent(content, 2));
		return item;
	}

	private static ConversationSecretNoteItem note(PrivateMessageHeader h,
			String content, int seconds) {
		ConversationSecretNoteItem item = new ConversationSecretNoteItem(h,
				new MutableLiveData<>("Bob"));
		item.setText(ConversationSecretNoteItem.wrapContent(content, seconds));
		return item;
	}

	private static PrivateMessageHeader incoming(long time) {
		return new PrivateMessageHeader(new MessageId(getRandomId()),
				new GroupId(getRandomId()), time, false, true, true, false,
				true, Collections.emptyList(), -1L);
	}

	private ConversationItemViewHolder holderAt(int position) {
		ConversationItemViewHolder h = adapter.onCreateViewHolder(
				new FrameLayout(ctx), adapter.getItemViewType(position));
		adapter.onBindViewHolder(h, position);
		return h;
	}

	private static String content(ConversationItemViewHolder h) {
		TextView v = h.itemView.findViewById(R.id.secretContent);
		return v.getVisibility() == View.VISIBLE ? v.getText().toString()
				: "";
	}

	private static String subtitle(ConversationItemViewHolder h) {
		return ((TextView) h.itemView.findViewById(R.id.secretSubtitle))
				.getText().toString();
	}

	private static void pass(long millis) {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
	}

	private ConversationItemViewHolder bound(ConversationSecretNoteItem item) {
		adapter.setItems(Collections.singletonList(item));
		ConversationItemViewHolder h = adapter.onCreateViewHolder(
				new FrameLayout(ctx), adapter.getItemViewType(0));
		adapter.onBindViewHolder(h, 0);
		return h;
	}

	private void rebind(ConversationItemViewHolder h,
			ConversationSecretNoteItem item) {
		adapter.setItems(Collections.singletonList(item));
		adapter.onBindViewHolder(h, 0);
	}

	private static List<String> shownText(View v) {
		List<String> out = new ArrayList<>();
		collect(v, out);
		return out;
	}

	private static void collect(View v, List<String> out) {
		CharSequence spoken = v.getContentDescription();
		if (spoken != null) out.add(spoken.toString());
		if (v instanceof TextView) {
			CharSequence t = ((TextView) v).getText();
			if (t != null) out.add(t.toString());
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				collect(g.getChildAt(i), out);
			}
		}
	}

	private static void assertNowhere(View row, String content) {
		for (String shown : shownText(row)) {
			assertTrue("a view holds the note text: " + shown,
					!shown.contains(content));
		}
	}

	private static String hidden(ConversationItemViewHolder h) {
		return ((TextView) h.itemView.findViewById(R.id.text)).getText()
				.toString();
	}

	@Test
	public void anUnreadNoteKeepsItsTextOutOfEveryView() {
		ConversationItemViewHolder h = bound(note(FIRST, false));

		assertNowhere(h.itemView, FIRST);
		assertNowhere(h.itemView, "SECRET:");
		assertEquals("", hidden(h));
	}

	@Test
	public void aSentNoteKeepsItsTextOutOfEveryView() {
		ConversationItemViewHolder h = bound(note(FIRST, true));

		assertNowhere(h.itemView, FIRST);
		assertNowhere(h.itemView, "SECRET:");
		assertEquals("", hidden(h));
	}

	@Test
	public void theHiddenViewStaysEmptyWhileTheNoteIsReadAndAfterItBurns() {
		ConversationSecretNoteItem item = note(FIRST, false);
		ConversationItemViewHolder h = bound(item);

		h.itemView.findViewById(R.id.layout).performClick();
		assertEquals(FIRST, ((TextView) h.itemView.findViewById(
				R.id.secretContent)).getText().toString());
		assertEquals("", hidden(h));

		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));

		assertNowhere(h.itemView, FIRST);
		assertEquals(null, item.getText());
	}

	@Test
	public void aRowLeftWhileANoteIsReadKeepsNoneOfItsText() {
		ConversationItemViewHolder h = bound(note(FIRST, false));
		h.itemView.findViewById(R.id.layout).performClick();

		adapter.onViewRecycled(h);
		assertNowhere(h.itemView, FIRST);

		rebind(h, note(SECOND, false));
		assertNowhere(h.itemView, FIRST);
		assertNowhere(h.itemView, SECOND);
		assertEquals("", hidden(h));
	}

	@Test
	public void recyclingOrRebindingACountingRowBurnsNothing() {
		ConversationSecretNoteItem first = note(incoming(1_000L), FIRST, 5);
		ConversationSecretNoteItem second = note(incoming(2_000L), SECOND, 9);
		adapter.setItems(Arrays.asList(first, second));
		ConversationItemViewHolder h = holderAt(0);
		h.itemView.findViewById(R.id.layout).performClick();
		pass(1_000);

		adapter.onViewRecycled(h);
		adapter.onBindViewHolder(h, 1);
		pass(1_000);

		verify(listener, never()).onSecretNoteOpened(any());
		assertEquals(ctx.getString(R.string.secret_note_tap_to_read),
				subtitle(h));
		assertNowhere(h.itemView, FIRST);

		ConversationItemViewHolder again = holderAt(0);
		assertEquals(FIRST, content(again));
		assertEquals(ctx.getString(R.string.secret_note_deletes_in, 3),
				subtitle(again));

		pass(3_000);
		verify(listener, times(1)).onSecretNoteOpened(first.getId());
		verify(listener, never()).onSecretNoteOpened(second.getId());
		verify(listener, times(1)).onSecretNoteRevealed(first.getId(), 5);
		verify(listener, never()).onSecretNoteRevealed(eq(second.getId()),
				anyInt());
		assertEquals("", content(again));
		assertEquals(ctx.getString(R.string.secret_note_expired),
				subtitle(again));
	}

	@Test
	public void twoRowsCountDownTogether() {
		ConversationSecretNoteItem first = note(incoming(1_000L), FIRST, 3);
		ConversationSecretNoteItem second = note(incoming(2_000L), SECOND, 6);
		adapter.setItems(Arrays.asList(first, second));
		ConversationItemViewHolder one = holderAt(0);
		ConversationItemViewHolder two = holderAt(1);
		one.itemView.findViewById(R.id.layout).performClick();
		pass(1_000);
		two.itemView.findViewById(R.id.layout).performClick();

		assertEquals(FIRST, content(one));
		assertEquals(SECOND, content(two));
		assertEquals(ctx.getString(R.string.secret_note_deletes_in, 2),
				subtitle(one));
		assertEquals(ctx.getString(R.string.secret_note_deletes_in, 6),
				subtitle(two));

		pass(2_000);
		verify(listener).onSecretNoteOpened(first.getId());
		assertEquals("", content(one));
		assertEquals(SECOND, content(two));
		assertEquals(ctx.getString(R.string.secret_note_deletes_in, 4),
				subtitle(two));

		pass(4_000);
		verify(listener).onSecretNoteOpened(second.getId());
		assertEquals("", content(two));
		assertNull(first.getText());
		assertNull(second.getText());
	}

	@Test
	public void aNewItemForARevealedNoteShowsItsCountdownWithoutItsOwnText() {
		PrivateMessageHeader header = incoming(1_000L);
		ConversationSecretNoteItem first = note(header, FIRST, 5);
		ConversationItemViewHolder h = bound(first);
		h.itemView.findViewById(R.id.layout).performClick();
		pass(2_000);

		ConversationSecretNoteItem reloaded = note(header, FIRST, 5);
		adapter.onViewRecycled(h);
		rebind(h, reloaded);

		assertTrue(reloaded.isRevealed());
		assertNull(reloaded.getText());
		assertEquals(FIRST, content(h));
		assertEquals(ctx.getString(R.string.secret_note_deletes_in, 3),
				subtitle(h));
		verify(listener, never()).onSecretNoteOpened(any());

		pass(3_000);
		verify(listener, times(1)).onSecretNoteOpened(header.getId());
		assertNowhere(h.itemView, FIRST);
	}

	@Test
	public void aNoteWhoseCountdownEndedShowsAsDeleted() {
		PrivateMessageHeader header = incoming(1_000L);
		ConversationItemViewHolder h = bound(note(header, FIRST, 2));
		h.itemView.findViewById(R.id.layout).performClick();
		pass(2_000);

		ConversationSecretNoteItem reloaded = note(header, FIRST, 2);
		ConversationItemViewHolder later = bound(reloaded);

		assertEquals("", content(later));
		assertEquals(ctx.getString(R.string.secret_note_expired),
				subtitle(later));
		assertNowhere(later.itemView, FIRST);
		assertNull(reloaded.getText());
		later.itemView.findViewById(R.id.layout).performClick();
		pass(5_000);
		verify(listener, times(1)).onSecretNoteOpened(header.getId());
	}
}
