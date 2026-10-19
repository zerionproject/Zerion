package com.professor.zerion.android.conversation;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.attachment.AttachmentItemsForTests;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.messaging.LinkPreview;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.zerionproject.core.api.sync.MessageId.LENGTH;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ConversationListWorkTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final int MESSAGES = 20;

	private final List<String> changes = new ArrayList<>();
	private ConversationAdapter adapter;
	private Context ctx;

	@Before
	public void setUp() {
		ctx = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
				R.style.ZerionTheme_NoActionBar);
		adapter = new ConversationAdapter(ctx,
				mock(ConversationListener.class),
				mock(org.zerionproject.app.api.attachment.AttachmentReader.class),
				Runnable::run, mock(com.professor.zerion.android.conversation
				.voice.VoiceMemoKeys.class));
		adapter.registerAdapterDataObserver(
				new RecyclerView.AdapterDataObserver() {
					@Override
					public void onChanged() {
						changes.add("all");
					}

					@Override
					public void onItemRangeChanged(int start, int count) {
						changes.add("changed " + start + "+" + count);
					}

					@Override
					public void onItemRangeChanged(int start, int count,
							@Nullable Object payload) {
						changes.add("changed " + start + "+" + count
								+ (payload == null ? "" : " partly"));
					}

					@Override
					public void onItemRangeInserted(int start, int count) {
						changes.add("inserted " + start + "+" + count);
					}

					@Override
					public void onItemRangeRemoved(int start, int count) {
						changes.add("removed " + start + "+" + count);
					}

					@Override
					public void onItemRangeMoved(int from, int to, int count) {
						changes.add("moved " + from + ">" + to);
					}
				});
	}

	private static MessageId id(int n) {
		byte[] id = new byte[LENGTH];
		id[0] = (byte) n;
		id[1] = (byte) (n >> 8);
		return new MessageId(id);
	}

	private static PrivateMessageHeader header(int n, boolean sent,
			boolean seen) {
		return new PrivateMessageHeader(id(n), new GroupId(new byte[LENGTH]),
				1_000L + n, true, true, sent, seen, true,
				Collections.emptyList(), -1L);
	}

	private static ConversationMessageItem message(int n, boolean sent,
			boolean seen) {
		ConversationMessageItem item = new ConversationMessageItem(
				R.layout.list_item_conversation_msg_out, header(n, sent, seen),
				new MutableLiveData<>("Bob"));
		item.setText("message " + n);
		return item;
	}

	private static List<ConversationItem> messages(int seenUpTo) {
		List<ConversationItem> out = new ArrayList<>();
		for (int i = 0; i < MESSAGES; i++) {
			out.add(message(i, true, i < seenUpTo));
		}
		return out;
	}

	private ConversationItemViewHolder bound(int position) {
		ConversationItemViewHolder h = adapter.onCreateViewHolder(
				new FrameLayout(ctx), adapter.getItemViewType(position));
		adapter.onBindViewHolder(h, position);
		return h;
	}

	private static String meta(ConversationItemViewHolder h) {
		return ((TextView) h.itemView.findViewById(R.id.time)).getText()
				.toString();
	}

	@Test
	public void replacingTheListWithEqualItemsRebindsNothing() {
		adapter.setItems(messages(5));
		changes.clear();

		adapter.replaceAll(messages(5));

		assertEquals(Collections.emptyList(), changes);
		assertEquals(MESSAGES, adapter.getItemCount());
	}

	@Test
	public void onlyTheMessageWhoseStateChangedIsRebound() {
		adapter.setItems(messages(5));
		changes.clear();

		adapter.replaceAll(messages(6));

		assertEquals(Arrays.asList("changed 5+1"), changes);
		assertTrue(adapter.getItemAt(5).isSeen());
	}

	@Test
	public void aMessageWithNewTextOrAReactionIsRebound() {
		adapter.setItems(messages(5));
		List<ConversationItem> next = messages(5);
		next.get(2).setText("message 2 (edited)");
		next.get(7).addReaction("ok");
		changes.clear();

		adapter.replaceAll(next);

		assertEquals(Arrays.asList("changed 2+1", "changed 7+1"), changes);
	}

	@Test
	public void markingAMessageSeenRebindsOnlyItsStateLine() {
		adapter.setItems(messages(0));
		ConversationItemViewHolder h = bound(3);
		String sent = ctx.getString(R.string.message_state_sent);
		assertTrue(meta(h), meta(h).endsWith(" · " + sent));
		TextView body = h.itemView.findViewById(R.id.text);
		body.setText("left alone");
		changes.clear();

		adapter.getItemAt(3).setSeen(true);
		adapter.refreshState(3);
		assertEquals(Arrays.asList("changed 3+1 partly"), changes);
		adapter.onBindViewHolder(h, 3, Collections.singletonList(
				ConversationAdapter.PAYLOAD_STATE));

		String delivered = ctx.getString(R.string.message_state_delivered);
		assertTrue(meta(h), meta(h).endsWith(" · " + delivered));
		assertEquals("left alone", body.getText().toString());
	}

	@Test
	public void highlightingAMatchDimsTheOthersWithoutRebindingThem() {
		adapter.setItems(messages(0));
		ConversationItemViewHolder other = bound(1);
		ConversationItemViewHolder match = bound(4);
		TextView body = other.itemView.findViewById(R.id.text);
		body.setText("left alone");
		changes.clear();

		adapter.setHighlightedPosition(4);
		assertEquals(Arrays.asList("changed 0+" + MESSAGES + " partly"),
				changes);
		adapter.onBindViewHolder(other, 1, Collections.singletonList(
				ConversationAdapter.PAYLOAD_HIGHLIGHT));
		adapter.onBindViewHolder(match, 4, Collections.singletonList(
				ConversationAdapter.PAYLOAD_HIGHLIGHT));

		assertEquals(0.4f, other.itemView.getAlpha(), 0.001f);
		assertEquals(1.0f, match.itemView.getAlpha(), 0.001f);
		assertEquals("left alone", body.getText().toString());

		changes.clear();
		adapter.setHighlightedPosition(-1);
		assertEquals(Arrays.asList("changed 0+" + MESSAGES + " partly"),
				changes);
		adapter.onBindViewHolder(other, 1, Collections.singletonList(
				ConversationAdapter.PAYLOAD_HIGHLIGHT));
		assertEquals(1.0f, other.itemView.getAlpha(), 0.001f);
	}

	@Test
	public void anAttachmentThatFinishedLoadingIsAChange() {
		AttachmentHeader header = new AttachmentHeader(
				new GroupId(new byte[LENGTH]), id(7), "image/jpeg");
		ConversationMessageItem missing = new ConversationMessageItem(
				R.layout.list_item_conversation_msg_out, header(1, true, false),
				new MutableLiveData<>("Bob"), Collections.singletonList(
				AttachmentItemsForTests.missing(header)));
		ConversationMessageItem available = new ConversationMessageItem(
				R.layout.list_item_conversation_msg_out, header(1, true, false),
				new MutableLiveData<>("Bob"), Collections.singletonList(
				AttachmentItemsForTests.available(header, "jpg")));
		ConversationMessageItem again = new ConversationMessageItem(
				R.layout.list_item_conversation_msg_out, header(1, true, false),
				new MutableLiveData<>("Bob"), Collections.singletonList(
				AttachmentItemsForTests.available(header, "jpg")));

		assertFalse(missing.showsTheSameAs(available));
		assertTrue(available.showsTheSameAs(again));
		adapter.setItems(Collections.singletonList(missing));
		changes.clear();
		adapter.replaceAll(Collections.singletonList(available));
		assertEquals(Arrays.asList("changed 0+1"), changes);
	}

	@Test
	public void anEqualLinkPreviewIsNotAChange() {
		ConversationMessageItem a = message(1, true, false);
		a.setLinkPreview(new LinkPreview("https://zerion.chat", "Zerion",
				"Private messenger", new byte[] {1, 2}));
		ConversationMessageItem b = message(1, true, false);
		b.setLinkPreview(new LinkPreview("https://zerion.chat", "Zerion",
				"Private messenger", new byte[] {1, 2}));
		assertTrue(a.showsTheSameAs(b));
		adapter.setItems(Collections.singletonList(a));
		changes.clear();
		adapter.replaceAll(Collections.singletonList(b));
		assertEquals(Collections.emptyList(), changes);

		ConversationMessageItem c = message(1, true, false);
		c.setLinkPreview(new LinkPreview("https://zerion.chat", "Elsewhere",
				null, null));
		assertFalse(a.showsTheSameAs(c));
		adapter.replaceAll(Collections.singletonList(c));
		assertEquals(Arrays.asList("changed 0+1"), changes);
	}

	@Test
	public void theKeyOfAMessageIsWorkedOutOnce() {
		ConversationItem item = message(1, true, false);
		assertSame(item.getKey(), item.getKey());
		assertEquals(item.getKey(), message(1, false, false).getKey());
	}
}
