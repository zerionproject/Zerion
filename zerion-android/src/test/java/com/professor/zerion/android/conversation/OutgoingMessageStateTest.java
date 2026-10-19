package com.professor.zerion.android.conversation;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.conversation.ConversationMessageVisitor;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import androidx.lifecycle.MutableLiveData;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class OutgoingMessageStateTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static Context ctx() {
		return new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
				R.style.ZerionTheme_NoActionBar);
	}

	private static ConversationMessageHeader header(boolean sent,
			boolean seen) {
		return new ConversationMessageHeader(new MessageId(new byte[32]),
				new GroupId(new byte[32]), System.currentTimeMillis(), true,
				true, sent, seen, -1L) {
			@Override
			public <T> T accept(ConversationMessageVisitor<T> v) {
				throw new UnsupportedOperationException();
			}
		};
	}

	private static View bound(boolean sent, boolean seen) {
		Context ctx = ctx();
		ConversationAdapter adapter = new ConversationAdapter(ctx,
				mock(ConversationListener.class),
				mock(org.zerionproject.app.api.attachment.AttachmentReader.class),
				Runnable::run, mock(com.professor.zerion.android.conversation
				.voice.VoiceMemoKeys.class));
		ConversationNoticeItem item = new ConversationNoticeItem(
				R.layout.list_item_conversation_notice_out, "see you",
				new MutableLiveData<>("Bob"), header(sent, seen));
		adapter.setItems(java.util.Collections.singletonList(item));
		ConversationItemViewHolder h = adapter.onCreateViewHolder(
				new FrameLayout(ctx), adapter.getItemViewType(0));
		adapter.onBindViewHolder(h, 0);
		return h.itemView;
	}

	private static String meta(View v) {
		return ((TextView) v.findViewById(R.id.time)).getText().toString();
	}

	private static String spoken(View v) {
		CharSequence spoken = v.findViewById(R.id.time)
				.getContentDescription();
		assertNotNull(spoken);
		return spoken.toString();
	}

	private static int icon(View v) {
		ImageView status = v.findViewById(R.id.status);
		return shadowOf(status.getDrawable()).getCreatedFromResId();
	}

	@Test
	public void aMessageThatIsNotSentYetSaysItIsWaitingToSend() {
		Context ctx = ctx();
		View v = bound(false, false);
		String waiting = ctx.getString(R.string.message_state_waiting);
		assertTrue(meta(v), meta(v).endsWith(" · " + waiting));
		assertEquals(R.drawable.message_stored, icon(v));
		assertTrue(spoken(v), spoken(v).endsWith(", " + waiting));
		ImageView status = v.findViewById(R.id.status);
		assertNull("the icon is not read out twice",
				status.getContentDescription());
		assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
				status.getImportantForAccessibility());
	}

	@Test
	public void sentAndDeliveredMessagesSaySo() {
		Context ctx = ctx();
		View sent = bound(true, false);
		assertTrue(meta(sent).endsWith(" · "
				+ ctx.getString(R.string.message_state_sent)));
		assertTrue(spoken(sent).endsWith(", "
				+ ctx.getString(R.string.message_state_sent)));
		assertEquals(R.drawable.message_sent, icon(sent));
		View delivered = bound(true, true);
		assertTrue(meta(delivered).endsWith(" · "
				+ ctx.getString(R.string.message_state_delivered)));
		assertTrue(spoken(delivered).endsWith(", "
				+ ctx.getString(R.string.message_state_delivered)));
		assertEquals(R.drawable.message_delivered, icon(delivered));
	}

	@Test
	public void refreshingTheTimeKeepsTheState() {
		Context ctx = ctx();
		View v = LayoutInflater.from(ctx).inflate(
				R.layout.list_item_conversation_notice_out,
				new FrameLayout(ctx), false);
		ConversationNoticeViewHolder h = new ConversationNoticeViewHolder(v,
				mock(ConversationListener.class), false);
		ConversationNoticeItem item = new ConversationNoticeItem(
				R.layout.list_item_conversation_notice_out, "see you",
				new MutableLiveData<>("Bob"), header(false, false));
		h.bind(item, false);
		h.bindTimeOnly(item);
		assertTrue(meta(v).endsWith(" · "
				+ ctx.getString(R.string.message_state_waiting)));
	}
}
