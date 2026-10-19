package com.professor.zerion.android.channel;

import android.content.Intent;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.selection.SelectionAction;
import com.professor.zerion.android.selection.SelectionBar;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.event.EventBus;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelCommentBubbleTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long HOUR = 60L * 60L * 1000L;
	private static final long DAY = 24L * HOUR;
	private static final long PARENT = 4L;
	private static final Pattern HEX = Pattern.compile("[0-9a-f]{16}");

	static final List<Runnable> io = new ArrayList<>();
	static ChannelManager manager;

	public static class ProbeComments extends ChannelCommentsActivity {

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			channelManager = manager;
			eventBus = mock(EventBus.class);
			notificationManager = mock(AndroidNotificationManager.class);
			ioExecutor = io::add;
		}
	}

	private final byte[] channelId = new byte[32];
	private final byte[] ownerKey = key((byte) 1);
	private final byte[] myKey = key((byte) 5);
	private final byte[] otherKey = key((byte) 7);
	private ProbeComments activity;

	@Before
	public void setUp() throws Exception {
		io.clear();
		manager = mock(ChannelManager.class);
		when(manager.getQueuedItems(any()))
				.thenReturn(Collections.emptyList());
		when(manager.areDiscussionsEnabled(any())).thenReturn(true);
		when(manager.areCommentsEnabled(any(), anyLong())).thenReturn(true);
		when(manager.getComments(any(), anyLong())).thenReturn(Arrays.asList(
				new ChannelComment(PARENT, 1L, "hello", "Alice", otherKey,
						new byte[0], HOUR),
				new ChannelComment(PARENT, 2L, "mine", "Me", myKey,
						new byte[0], 2 * HOUR),
				new ChannelComment(PARENT, 3L, "thanks", "Owner name",
						ownerKey, new byte[0], 3 * HOUR)));
		when(manager.getChannel(any())).thenReturn(new ChannelState(channelId,
				new byte[16], ownerKey, new byte[0], "News", "", null, HOUR,
				true, null, "", 1L, false, 0L));
		when(manager.getMyChannelPublicKey(any())).thenReturn(myKey);
	}

	private ProbeComments open(Intent i) {
		i.setClass(ApplicationProvider.getApplicationContext(),
				ProbeComments.class);
		ProbeComments a = Robolectric.buildActivity(ProbeComments.class, i)
				.setup().get();
		activity = a;
		runIo();
		return a;
	}

	private ProbeComments open() {
		return open(ChannelCommentsActivity.intent(
				ApplicationProvider.getApplicationContext(), channelId,
				PARENT));
	}

	private LinearLayout.LayoutParams cardParams(int position) {
		View card = holder(position).itemView.findViewById(R.id.commentCard);
		return (LinearLayout.LayoutParams) card.getLayoutParams();
	}

	@Test
	public void othersSitOnTheLeftAndMyCommentsOnTheRight() throws Exception {
		open();

		assertEquals(Gravity.START, cardParams(0).gravity);
		assertEquals(Gravity.END, cardParams(1).gravity);
		assertEquals(Gravity.START, cardParams(2).gravity);
		assertTrue("my bubble keeps the far side free",
				cardParams(1).getMarginStart() > cardParams(1).getMarginEnd());
		assertTrue(cardParams(0).getMarginEnd() > cardParams(0).getMarginStart());
	}

	@Test
	public void theAuthorLineNamesThePersonAndTheirRoleWithoutHex()
			throws Exception {
		open();

		assertEquals("Alice", text(0, R.id.commentAuthor));
		assertEquals(View.GONE, holder(0).itemView.findViewById(
				R.id.commentAuthorTag).getVisibility());
		assertEquals("Me", text(1, R.id.commentAuthor));
		assertEquals(activity.getString(R.string.channels_comment_you),
				text(1, R.id.commentAuthorTag));
		assertEquals("Owner name", text(2, R.id.commentAuthor));
		assertEquals(activity.getString(R.string.channels_comment_owner),
				text(2, R.id.commentAuthorTag));
		for (int i = 0; i < 3; i++) {
			assertFalse(text(i, R.id.commentAuthor),
					HEX.matcher(text(i, R.id.commentAuthor)).find());
		}
	}

	@Test
	public void myCommentWithoutANameReadsYouAndAnotherKeepsItsLabel()
			throws Exception {
		when(manager.getComments(any(), anyLong())).thenReturn(Arrays.asList(
				new ChannelComment(PARENT, 1L, "hello", "", otherKey,
						new byte[0], HOUR),
				new ChannelComment(PARENT, 2L, "mine", "", myKey,
						new byte[0], 2 * HOUR)));
		open();

		String unnamed = activity.getString(
				R.string.channels_delegations_unnamed_person);
		String you = activity.getString(R.string.channels_comment_you);
		assertEquals(unnamed, text(0, R.id.commentAuthor));
		assertEquals(View.GONE, tagVisibility(0));
		assertEquals(you, text(1, R.id.commentAuthor));
		assertEquals("the tag does not say You twice", View.GONE,
				tagVisibility(1));

		assertTrue(holder(1).itemView.performLongClick());
		idle();
		assertTrue(bar().perform(SelectionAction.INFO));
		idle();
		String info = message((AlertDialog) ShadowDialog.getLatestDialog());
		assertTrue(info, info.contains(you));
		assertFalse(info, info.contains(unnamed));
	}

	@Test
	public void myCommentWithANameKeepsTheNameAndTheYouTag()
			throws Exception {
		open();

		assertEquals("Me", text(1, R.id.commentAuthor));
		assertEquals(View.VISIBLE, tagVisibility(1));
		assertEquals(activity.getString(R.string.channels_comment_you),
				text(1, R.id.commentAuthorTag));
		assertEquals("Alice", text(0, R.id.commentAuthor));
		assertEquals(View.GONE, tagVisibility(0));
	}

	@Test
	public void theScreenSaysWhenThePostDisappears() throws Exception {
		open(ChannelCommentsActivity.intent(
				ApplicationProvider.getApplicationContext(), channelId,
				PARENT, System.currentTimeMillis() + 6 * DAY));

		TextView line = activity.findViewById(R.id.commentsDisappearsLine);
		assertEquals(View.VISIBLE, line.getVisibility());
		String shown = line.getText().toString();
		assertTrue(shown, shown.startsWith(activity.getString(
				R.string.channels_post_disappears, "").trim()));
		assertTrue(shown, shown.contains("6 days") || shown.contains("5 days"));
	}

	@Test
	public void aPostWithoutATimerShowsNoDisappearsLine() throws Exception {
		open();

		assertEquals(View.GONE, activity.findViewById(
				R.id.commentsDisappearsLine).getVisibility());
	}

	@Test
	public void theComposerSendsFromALabelledRoundButton() throws Exception {
		open();

		View send = activity.findViewById(R.id.commentsComposeSendButton);
		assertTrue(send instanceof ImageButton);
		assertEquals(activity.getString(R.string.channels_comments_send),
				send.getContentDescription().toString());
		TextView input = activity.findViewById(R.id.commentsComposeInput);
		assertEquals(activity.getString(R.string.channels_comments_hint),
				input.getHint().toString());
		assertNotNull(input.getBackground());
	}

	private int tagVisibility(int position) {
		return holder(position).itemView.findViewById(R.id.commentAuthorTag)
				.getVisibility();
	}

	private SelectionBar bar() throws Exception {
		Field f = ChannelCommentsActivity.class.getDeclaredField(
				"selectionBar");
		f.setAccessible(true);
		return (SelectionBar) f.get(activity);
	}

	private static String message(AlertDialog d) {
		assertNotNull(d);
		TextView m = d.findViewById(android.R.id.message);
		assertNotNull(m);
		return m.getText().toString();
	}

	private String text(int position, int id) {
		return ((TextView) holder(position).itemView.findViewById(id))
				.getText().toString();
	}

	private RecyclerView.ViewHolder holder(int position) {
		RecyclerView list = activity.findViewById(R.id.commentsRecycler);
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		RecyclerView.ViewHolder h =
				list.findViewHolderForAdapterPosition(position);
		assertNotNull("no holder at " + position, h);
		return h;
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static void runIo() {
		idle();
		for (int round = 0; round < 20 && !io.isEmpty(); round++) {
			List<Runnable> pending = new ArrayList<>(io);
			io.clear();
			for (Runnable r : pending) r.run();
			idle();
		}
		idle();
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
