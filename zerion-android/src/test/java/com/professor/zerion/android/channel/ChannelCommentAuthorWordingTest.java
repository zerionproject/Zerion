package com.professor.zerion.android.channel;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.event.EventBus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelCommentAuthorWordingTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long HOUR = 60L * 60L * 1000L;
	private static final long DAY = 24L * HOUR;
	private static final long PARENT = 4L;
	private static final Pattern HEX_RUN = Pattern.compile("[0-9a-f]{16}");

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
	private final byte[] aliceKey = key((byte) 7);
	private final byte[] otherAliceKey = key((byte) 8);
	private final byte[] nobodyKey = key((byte) 9);
	private final long now = System.currentTimeMillis() / HOUR * HOUR;
	private ProbeComments activity;

	@Before
	public void setUp() throws Exception {
		io.clear();
		manager = mock(ChannelManager.class);
		when(manager.getQueuedItems(any()))
				.thenReturn(Collections.emptyList());
		when(manager.areDiscussionsEnabled(any())).thenReturn(true);
		when(manager.areCommentsEnabled(any(), anyLong())).thenReturn(true);
		when(manager.getChannel(any())).thenReturn(new ChannelState(
				channelId, new byte[16], ownerKey, new byte[0], "News", "",
				null, HOUR, true, null, "", 1L, false, 0L));
		when(manager.getMyChannelPublicKey(any())).thenReturn(myKey);
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}

	private static ChannelComment comment(long id, String name, byte[] key,
			long hour) {
		return new ChannelComment(PARENT, id, "hi " + id, name, key,
				new byte[0], hour);
	}

	private void comments(ChannelComment... comments) throws Exception {
		when(manager.getComments(any(), anyLong()))
				.thenReturn(Arrays.asList(comments));
	}

	private ProbeComments open() {
		Intent i = ChannelCommentsActivity.intent(
				ApplicationProvider.getApplicationContext(), channelId,
				PARENT);
		i.setClass(ApplicationProvider.getApplicationContext(),
				ProbeComments.class);
		activity = Robolectric.buildActivity(ProbeComments.class, i)
				.setup().get();
		runIo();
		return activity;
	}

	private String author(int position) {
		return line(position, R.id.commentAuthor);
	}

	private String tag(int position) {
		RecyclerView.ViewHolder h = holder(position);
		TextView tag = h.itemView.findViewById(R.id.commentAuthorTag);
		assertNotNull(tag);
		return tag.getVisibility() == View.VISIBLE
				? tag.getText().toString() : "";
	}

	private String line(int position, int id) {
		TextView text = holder(position).itemView.findViewById(id);
		assertNotNull(text);
		return text.getText().toString();
	}

	private RecyclerView.ViewHolder holder(int position) {
		RecyclerView list = activity.findViewById(R.id.commentsRecycler);
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		RecyclerView.ViewHolder h =
				list.findViewHolderForAdapterPosition(position);
		assertNotNull("no comment at " + position, h);
		return h;
	}

	@Test
	public void aCommentIsHeadedByTheNameAloneWithoutAKey()
			throws Exception {
		comments(comment(1L, "Alice", aliceKey, now - HOUR));
		open();
		assertEquals("Alice", author(0));
	}

	@Test
	public void theOwnerAndYouKeepTheirTagsButLoseTheKey() throws Exception {
		comments(comment(1L, "Boss", ownerKey, now - 2 * HOUR),
				comment(2L, "Me", myKey, now - HOUR));
		open();
		assertEquals("Boss", author(0));
		assertEquals("Owner", tag(0));
		assertEquals("Me", author(1));
		assertEquals("You", tag(1));
	}

	@Test
	public void twoAlicesAreToldApartByTheDayTheyFirstWroteHere()
			throws Exception {
		comments(comment(1L, "Alice", aliceKey, now - 9 * DAY),
				comment(2L, "Alice", otherAliceKey, now - 2 * DAY),
				comment(3L, "Alice", aliceKey, now - HOUR));
		open();
		String first = author(0);
		String second = author(1);
		String third = author(2);
		assertTrue(first, first.startsWith("Alice (first wrote "));
		assertTrue(second, second.startsWith("Alice (first wrote "));
		assertNotEquals(first, second);
		assertEquals(first, third);
		assertFalse(first, HEX_RUN.matcher(first).find());
		assertFalse(first, first.contains(":"));
	}

	@Test
	public void twoAlicesFromTheSameDayAreToldApartByTheHour()
			throws Exception {
		java.util.Calendar c = java.util.Calendar.getInstance();
		c.add(java.util.Calendar.DAY_OF_YEAR, -1);
		c.set(java.util.Calendar.HOUR_OF_DAY, 9);
		c.set(java.util.Calendar.MINUTE, 0);
		c.set(java.util.Calendar.SECOND, 0);
		c.set(java.util.Calendar.MILLISECOND, 0);
		long morning = c.getTimeInMillis();
		comments(comment(1L, "Alice", aliceKey, morning),
				comment(2L, "Alice", otherAliceKey, morning + 3 * HOUR));
		open();
		String first = author(0);
		String second = author(1);
		assertTrue(first, first.startsWith("Alice (first wrote "));
		assertNotEquals(first, second);
		assertEquals("Alice (first wrote " + com.professor.zerion.android.util
				.HourTimes.withDay(ApplicationProvider.getApplicationContext(),
						morning, System.currentTimeMillis()) + ")", first);
		assertTrue("the hour reads as an hour, not a minute: " + first,
				first.contains(", around "));
	}

	@Test
	public void aPersonWithoutANameIsCalledThat() throws Exception {
		comments(comment(1L, "", nobodyKey, now - HOUR));
		open();
		assertEquals("Person without a name", author(0));
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
}
