package com.professor.zerion.android.conversation;

import android.content.Intent;
import android.os.Looper;
import android.view.ActionMode;
import android.view.Menu;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.api.LockManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.fakes.RoboMenuItem;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.lang.reflect.Field;
import java.util.Collections;

import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.selection.SelectionTracker;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class MessageSelectionTest {

	static {
		TestAndroidKeyStore.register();
	}

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
	private ConversationMessageItem second;

	private static Object field(Object o, String name) throws Exception {
		Field f = ConversationActivity.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	private static ConversationMessageItem item(String text, long time) {
		PrivateMessageHeader h = new PrivateMessageHeader(
				new MessageId(getRandomId()), new GroupId(getRandomId()),
				time, true, true, true, false, true,
				Collections.emptyList(), -1L);
		ConversationMessageItem item = new ConversationMessageItem(
				R.layout.list_item_conversation_msg_out, h,
				new MutableLiveData<>("Bob"));
		item.setText(text);
		return item;
	}

	@Before
	public void setUp() throws Exception {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(ConversationActivity.CONTACT_ID, 5);
		ActivityController<ProbeConversation> c =
				Robolectric.buildActivity(ProbeConversation.class, i).setup();
		activity = c.get();
		idle();
		adapter = (ConversationAdapter) field(activity, "adapter");
		first = item("first", 1_000L);
		second = item("second", 2_000L);
		adapter.add(first);
		adapter.add(second);
		idle();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	@SuppressWarnings("unchecked")
	private int selected() throws Exception {
		SelectionTracker<String> tracker =
				(SelectionTracker<String>) field(activity, "tracker");
		return tracker == null ? 0 : tracker.getSelection().size();
	}

	private ActionMode mode() throws Exception {
		return (ActionMode) field(activity, "actionMode");
	}

	private void select(ConversationMessageItem item) {
		activity.onMessageLongClick(item);
		idle();
	}

	@Test
	public void closingTheMenuLeavesNothingSelected() throws Exception {
		select(first);
		assertEquals(1, selected());
		assertNotNull(mode());

		mode().finish();
		idle();

		assertNull(mode());
		assertEquals(0, selected());
		select(second);
		assertEquals("only the message pressed now is selected", 1,
				selected());
	}

	@Test
	public void anActionThatClosesTheMenuLeavesNothingSelected()
			throws Exception {
		for (int action : new int[] {R.id.action_copy, R.id.action_react}) {
			select(first);
			ActionMode m = mode();
			assertNotNull(m);
			activity.onActionItemClicked(m, new RoboMenuItem(action));
			idle();
			dismissDialogs();
			assertNull(mode());
			assertEquals(0, selected());
		}
		select(second);
		assertEquals(1, selected());
	}

	@Test
	public void cancellingTheDeleteKeepsTheSelectionVisible()
			throws Exception {
		select(first);
		activity.onActionItemClicked(mode(), new RoboMenuItem(
				R.id.action_delete));
		idle();
		dismissDialogs();

		assertNotNull("the selection stays on screen", mode());
		assertEquals(1, selected());
	}

	@Test
	public void editIsOnlyOfferedWhenTheContactCanReceiveEdits()
			throws Exception {
		select(first);
		Menu menu = new org.robolectric.fakes.RoboMenu(activity);
		activity.getMenuInflater().inflate(
				R.menu.conversation_message_actions, menu);
		activity.onPrepareActionMode(mode(), menu);
		assertEquals(false, menu.findItem(R.id.action_edit).isVisible());
	}

	private static void dismissDialogs() {
		android.app.Dialog d =
				org.robolectric.shadows.ShadowDialog.getLatestDialog();
		if (d != null && d.isShowing()) d.dismiss();
		idle();
	}
}
