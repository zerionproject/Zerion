package com.professor.zerion.android.conversation;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.api.LockManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.selection.SelectionAction;
import com.professor.zerion.android.selection.SelectionBar;
import com.professor.zerion.android.view.TextInputView;
import com.professor.zerion.android.view.ZerionRecyclerView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.selection.SelectionTracker;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class MessageRowAccessibilityTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String SECRET_WORDS = "the hidden words";
	private static final String SECRET = "SECRET:10:" + SECRET_WORDS;

	public static class ProbeConversation extends ConversationActivity {

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			LockManager lock = mock(LockManager.class);
			when(lock.isLocked()).thenReturn(false);
			when(lock.isLockable()).thenReturn(new MutableLiveData<>(false));
			lockManager = lock;
			notificationManager = mock(AndroidNotificationManager.class);
		}
	}

	private ProbeConversation activity;
	private ConversationAdapter adapter;
	private ConversationMessageItem first;

	private static Object field(Object o, String name) throws Exception {
		Field f = ConversationActivity.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	private static PrivateMessageHeader header(long time, boolean local) {
		return new PrivateMessageHeader(
				new MessageId(getRandomId()), new GroupId(getRandomId()),
				time, local, true, true, false, true,
				Collections.emptyList(), -1L);
	}

	@Before
	public void setUp() throws Exception {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(ConversationActivity.CONTACT_ID, 5);
		activity = Robolectric.buildActivity(ProbeConversation.class, i)
				.setup().get();
		idle();
		adapter = (ConversationAdapter) field(activity, "adapter");
		first = new ConversationMessageItem(
				R.layout.list_item_conversation_msg_out, header(1_000L, true),
				new MutableLiveData<>("Bob"));
		first.setText("see you at ten");
		adapter.add(first);
		idle();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private SelectionBar bar() throws Exception {
		return (SelectionBar) field(activity, "selectionBar");
	}

	@SuppressWarnings("unchecked")
	private int selected() throws Exception {
		SelectionTracker<String> tracker =
				(SelectionTracker<String>) field(activity, "tracker");
		return tracker == null ? 0 : tracker.getSelection().size();
	}

	private RecyclerView.ViewHolder holder(int position) throws Exception {
		RecyclerView list = ((ZerionRecyclerView) field(activity, "list"))
				.getRecyclerView();
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		RecyclerView.ViewHolder h =
				list.findViewHolderForAdapterPosition(position);
		assertNotNull("no holder at " + position, h);
		return h;
	}

	private View bubble(int position) throws Exception {
		return holder(position).itemView.findViewById(R.id.layout);
	}

	private static List<String> customActions(View v) {
		List<String> out = new ArrayList<>();
		for (AccessibilityNodeInfo.AccessibilityAction a :
				v.createAccessibilityNodeInfo().getActionList()) {
			if (SelectionAction.byId(a.getId()) != null) {
				out.add(String.valueOf(a.getLabel()));
			}
		}
		return out;
	}

	private static void collectText(View v, StringBuilder out) {
		if (v.getVisibility() != View.VISIBLE) return;
		AccessibilityNodeInfo info = v.createAccessibilityNodeInfo();
		if (info.getText() != null) out.append(info.getText()).append(' ');
		if (info.getContentDescription() != null) {
			out.append(info.getContentDescription()).append(' ');
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				collectText(g.getChildAt(i), out);
			}
		}
	}

	@Test
	public void aMessageOffersReplyReactCopyAndDeleteToTalkBack()
			throws Exception {
		assertEquals("[Reply, React, Copy, Delete]",
				customActions(bubble(0)).toString());
	}

	@Test
	public void copyFromTheRowCopiesWithoutTheSelectionBar()
			throws Exception {
		assertTrue(bubble(0).performAccessibilityAction(R.id.action_copy,
				null));
		idle();
		ClipboardManager cm = (ClipboardManager) activity.getSystemService(
				Context.CLIPBOARD_SERVICE);
		assertEquals("see you at ten", cm.getPrimaryClip().getItemAt(0)
				.getText().toString());
		assertFalse(bar().isShowing());
		assertEquals(0, selected());
	}

	@Test
	public void replyFromTheRowQuotesTheMessage() throws Exception {
		assertTrue(bubble(0).performAccessibilityAction(R.id.action_reply,
				null));
		idle();
		TextInputView input = (TextInputView) field(activity, "textInputView");
		assertSame(first, input.getReplyingToItem());
		assertFalse(bar().isShowing());
		assertEquals(0, selected());
	}

	@Test
	public void deleteFromTheRowAsksFirstJustLikeTheBar() throws Exception {
		assertTrue(bubble(0).performAccessibilityAction(R.id.action_delete,
				null));
		idle();
		assertTrue("the same question as the bar's Delete",
				ShadowDialog.getLatestDialog().isShowing());
		assertTrue("the message stays selected behind it", bar().isShowing());
		assertEquals(1, selected());
	}

	@Test
	public void rowActionsStepAsideWhileTheBarIsOpen() throws Exception {
		activity.onMessageLongClick(first);
		idle();
		assertTrue(bar().isShowing());
		assertTrue(customActions(bubble(0)).isEmpty());
		assertFalse(bubble(0).performAccessibilityAction(R.id.action_copy,
				null));
		assertEquals(1, selected());
	}

	@Test
	public void aSecretNoteHasNoRowActionsAndNeverReadsItsWords()
			throws Exception {
		PrivateMessageHeader h = header(2_000L, false);
		ConversationSecretNoteItem note = new ConversationSecretNoteItem(h,
				new MutableLiveData<>("Bob"));
		note.setText(SECRET);
		adapter.add(note);
		idle();

		View row = holder(1).itemView;
		View noteBubble = row.findViewById(R.id.layout);
		assertTrue(customActions(noteBubble).isEmpty());
		assertFalse(noteBubble.performAccessibilityAction(R.id.action_copy,
				null));
		assertFalse(noteBubble.performAccessibilityAction(
				R.id.action_delete, null));
		StringBuilder read = new StringBuilder();
		collectText(row, read);
		assertFalse(read.toString(), read.toString().contains(SECRET_WORDS));
		assertFalse(read.toString(), read.toString().contains("SECRET"));
		assertFalse(bar().isShowing());
	}

	@Test
	public void theWordsTimeAndStateReadAsOneItem() throws Exception {
		View row = bubble(0);
		TextView text = row.findViewById(R.id.text);
		AccessibilityNodeInfo words = text.createAccessibilityNodeInfo();
		assertFalse("the words are not a stop of their own",
				words.isLongClickable() || words.isClickable()
						|| words.isFocusable());
		assertTrue(row.createAccessibilityNodeInfo().isScreenReaderFocusable());
		TextView time = row.findViewById(R.id.time);
		assertTrue(String.valueOf(time.getContentDescription()),
				String.valueOf(time.getContentDescription()).contains(
						activity.getString(R.string.message_state_sent)));
	}

	@Test
	public void wordsWithALinkKeepTheirOwnStopSoTheLinkCanOpen()
			throws Exception {
		ConversationMessageItem link = new ConversationMessageItem(
				R.layout.list_item_conversation_msg_out, header(3_000L, true),
				new MutableLiveData<>("Bob"));
		link.setText("look at https://example.com");
		adapter.add(link);
		idle();
		TextView text = holder(1).itemView.findViewById(R.id.text);
		AccessibilityNodeInfo words = text.createAccessibilityNodeInfo();
		assertTrue(words.isFocusable() || words.isClickable()
				|| words.isLongClickable());
		String hold = null;
		for (AccessibilityNodeInfo.AccessibilityAction a :
				words.getActionList()) {
			if (a.getId() == AccessibilityNodeInfo.ACTION_LONG_CLICK) {
				hold = String.valueOf(a.getLabel());
			}
		}
		assertEquals("holding still selects",
				activity.getString(R.string.selection_select), hold);
	}
}
