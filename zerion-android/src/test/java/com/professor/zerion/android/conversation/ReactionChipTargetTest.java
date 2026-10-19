package com.professor.zerion.android.conversation;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewConfiguration;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.api.LockManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;
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
import java.time.Duration;
import java.util.Collections;

import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.selection.SelectionTracker;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, qualifiers = "w411dp-h915dp-xxhdpi")
public class ReactionChipTargetTest {

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

	@Before
	public void setUp() throws Exception {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(ConversationActivity.CONTACT_ID, 5);
		activity = Robolectric.buildActivity(ProbeConversation.class, i)
				.setup().get();
		idle();
		Field f = ConversationActivity.class.getDeclaredField("adapter");
		f.setAccessible(true);
		adapter = (ConversationAdapter) f.get(activity);
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private ConversationMessageItem add(int layout, boolean local, long time,
			boolean reaction) {
		ConversationMessageItem item = new ConversationMessageItem(layout,
				new PrivateMessageHeader(new MessageId(getRandomId()),
						new GroupId(getRandomId()), time, local, true, true,
						false, true, Collections.emptyList(), -1L),
				new MutableLiveData<>("Bob"));
		item.setText("night test moto to pixel");
		if (reaction) item.addReaction("👍");
		adapter.add(item);
		idle();
		return item;
	}

	private View row(int position) throws Exception {
		Field f = ConversationActivity.class.getDeclaredField("list");
		f.setAccessible(true);
		RecyclerView list = ((ZerionRecyclerView) f.get(activity))
				.getRecyclerView();
		list.measure(View.MeasureSpec.makeMeasureSpec(
				activity.getResources().getDisplayMetrics().widthPixels,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				4000, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, list.getMeasuredWidth(), 4000);
		idle();
		RecyclerView.ViewHolder h =
				list.findViewHolderForAdapterPosition(position);
		assertNotNull(h);
		return h.itemView;
	}

	private static void tap(View v, float x, float y) {
		long now = SystemClock.uptimeMillis();
		MotionEvent down = MotionEvent.obtain(now, now,
				MotionEvent.ACTION_DOWN, x, y, 0);
		MotionEvent up = MotionEvent.obtain(now, now + 50,
				MotionEvent.ACTION_UP, x, y, 0);
		v.dispatchTouchEvent(down);
		v.dispatchTouchEvent(up);
		down.recycle();
		up.recycle();
		idle();
	}

	private void assertChipTarget(View row) throws Exception {
		float density = activity.getResources().getDisplayMetrics().density;
		int min = Math.round(48 * density);
		View bubble = row.findViewById(R.id.layout);
		View chip = row.findViewById(R.id.reactionsView);
		assertTrue(chip.getVisibility() == View.VISIBLE);
		TouchDelegate delegate = bubble.getTouchDelegate();
		assertNotNull("the chip has a wider target", delegate);
		Field bounds = TouchDelegate.class.getDeclaredField("mBounds");
		bounds.setAccessible(true);
		Rect target = (Rect) bounds.get(delegate);
		int below = bubble.getHeight()
				- row.findViewById(R.id.statusLayout).getBottom();
		assertTrue("target " + target.width() / density + "x"
						+ target.height() / density + "dp",
				target.width() >= min
						&& target.height() >= Math.min(min, below));
		assertTrue("the time line keeps the bubble's own touches",
				target.top >= row.findViewById(R.id.statusLayout)
						.getBottom());
		Rect drawn = new Rect();
		chip.getHitRect(drawn);
		assertTrue("the target covers the chip", target.contains(drawn));
		assertTrue("the target stays in the bubble", target.left >= 0
				&& target.right <= bubble.getWidth()
				&& target.bottom <= bubble.getHeight());
		ShadowDialog.reset();
		tap(bubble, drawn.right + 4 * density, drawn.centerY());
		assertNotNull("a tap just beside the chip still opens reactions",
				ShadowDialog.getLatestDialog());
		ShadowDialog.getLatestDialog().dismiss();
		idle();
	}

	private static void longPress(View v, float x, float y) {
		long now = SystemClock.uptimeMillis();
		MotionEvent down = MotionEvent.obtain(now, now,
				MotionEvent.ACTION_DOWN, x, y, 0);
		v.dispatchTouchEvent(down);
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
				ViewConfiguration.getLongPressTimeout() + 200L));
		MotionEvent up = MotionEvent.obtain(now, SystemClock.uptimeMillis(),
				MotionEvent.ACTION_UP, x, y, 0);
		v.dispatchTouchEvent(up);
		down.recycle();
		up.recycle();
		idle();
	}

	@SuppressWarnings("unchecked")
	private int selected() throws Exception {
		Field f = ConversationActivity.class.getDeclaredField("tracker");
		f.setAccessible(true);
		SelectionTracker<String> tracker =
				(SelectionTracker<String>) f.get(activity);
		return tracker == null ? 0 : tracker.getSelection().size();
	}

	private float[] timeLine(View row) {
		View bubble = row.findViewById(R.id.layout);
		View status = row.findViewById(R.id.statusLayout);
		View chip = row.findViewById(R.id.reactionsView);
		Rect drawn = new Rect();
		chip.getHitRect(drawn);
		assertTrue(status.getBottom() <= drawn.top);
		assertTrue(bubble.getWidth() > 0);
		return new float[] {drawn.centerX(),
				(status.getTop() + status.getBottom()) / 2f};
	}

	private float[] chipMiddle(View row) {
		Rect drawn = new Rect();
		row.findViewById(R.id.reactionsView).getHitRect(drawn);
		return new float[] {drawn.centerX(), drawn.centerY()};
	}

	@Test
	public void holdingTheTimeLineSelectsTheMessage() throws Exception {
		add(R.layout.list_item_conversation_msg_out, true, 1_000L, true);
		View row = row(0);
		float[] p = timeLine(row);
		ShadowDialog.reset();
		longPress(row.findViewById(R.id.layout), p[0], p[1]);
		assertEquals(1, selected());
		assertNull("no reactions open", ShadowDialog.getLatestDialog());
	}

	@Test
	public void tappingTheTimeLineWhileSelectingTogglesTheMessage()
			throws Exception {
		ConversationMessageItem other = add(
				R.layout.list_item_conversation_msg_in, false, 1_000L, false);
		add(R.layout.list_item_conversation_msg_out, true, 2_000L, true);
		activity.onMessageLongClick(other);
		idle();
		assertEquals(1, selected());
		View row = row(1);
		float[] p = timeLine(row);
		ShadowDialog.reset();
		tap(row.findViewById(R.id.layout), p[0], p[1]);
		assertEquals(2, selected());
		assertNull("no reactions open", ShadowDialog.getLatestDialog());
	}

	@Test
	public void holdingTheChipSelectsTheMessage() throws Exception {
		add(R.layout.list_item_conversation_msg_in, false, 1_000L, true);
		View row = row(0);
		float[] p = chipMiddle(row);
		ShadowDialog.reset();
		longPress(row.findViewById(R.id.layout), p[0], p[1]);
		assertEquals(1, selected());
		assertNull("no reactions open", ShadowDialog.getLatestDialog());
	}

	@Test
	public void tappingTheChipWhileSelectingTogglesTheMessage()
			throws Exception {
		ConversationMessageItem other = add(
				R.layout.list_item_conversation_msg_in, false, 1_000L, false);
		add(R.layout.list_item_conversation_msg_out, true, 2_000L, true);
		activity.onMessageLongClick(other);
		idle();
		View row = row(1);
		float[] p = chipMiddle(row);
		ShadowDialog.reset();
		tap(row.findViewById(R.id.layout), p[0], p[1]);
		assertEquals(2, selected());
		assertNull("no reactions open", ShadowDialog.getLatestDialog());
	}

	@Test
	public void tappingTheChipOtherwiseOpensReactions() throws Exception {
		add(R.layout.list_item_conversation_msg_in, false, 1_000L, true);
		View row = row(0);
		float[] p = chipMiddle(row);
		ShadowDialog.reset();
		tap(row.findViewById(R.id.layout), p[0], p[1]);
		assertNotNull(ShadowDialog.getLatestDialog());
		assertEquals(0, selected());
	}

	@Test
	public void theReactionChipIsAtLeast48dpToTouch() throws Exception {
		add(R.layout.list_item_conversation_msg_in, false, 1_000L, true);
		add(R.layout.list_item_conversation_msg_out, true, 2_000L, true);
		assertChipTarget(row(0));
		assertChipTarget(row(1));
	}

	@Test
	public void aRowWithoutReactionsKeepsNoChipTarget() throws Exception {
		ConversationMessageItem item = add(
				R.layout.list_item_conversation_msg_in, false, 1_000L, true);
		assertChipTarget(row(0));
		item.removeReaction("👍");
		adapter.notifyItemChanged(0);
		idle();
		View row = row(0);
		assertNull("no target is left behind for a hidden chip",
				row.findViewById(R.id.layout).getTouchDelegate());
	}
}
