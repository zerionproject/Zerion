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
import java.util.Collections;
import java.util.List;

import androidx.lifecycle.MutableLiveData;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
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
	private ConversationAdapter adapter;

	@Before
	public void setUp() {
		ctx = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
				R.style.ZerionTheme_NoActionBar);
		adapter = new ConversationAdapter(ctx,
				mock(ConversationListener.class),
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
}
