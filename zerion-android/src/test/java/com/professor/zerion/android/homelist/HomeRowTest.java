package com.professor.zerion.android.homelist;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.ContrastMath;
import com.professor.zerion.android.util.UiUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class HomeRowTest {

	private final Context ctx = new ContextThemeWrapper(
			RuntimeEnvironment.getApplication(), R.style.ZerionTheme);

	private View bound(HomeRow row) {
		HomeListAdapter adapter = new HomeListAdapter(
				new HomeListAdapter.Listener() {
					@Override
					public void onRowClick(HomeRow r) {
					}

					@Override
					public boolean onRowLongClick(HomeRow r, View anchor) {
						return false;
					}
				}, Runnable::run);
		HomeRowViewHolder holder = (HomeRowViewHolder) adapter
				.onCreateViewHolder(new FrameLayout(ctx), HomeItem.TYPE_ROW);
		holder.bind(row, System.currentTimeMillis());
		return holder.itemView;
	}

	private static String text(View row, int id) {
		return ((TextView) row.findViewById(id)).getText().toString();
	}

	private static int visibility(View row, int id) {
		return row.findViewById(id).getVisibility();
	}

	@Test
	public void theTimeHasItsOwnPlaceBesideTheName() {
		long now = System.currentTimeMillis();
		View row = bound(HomeRow.builder("contact:1", "Alex").time(now)
				.preview(null, "Are we still on?").build());
		assertEquals("Alex", text(row, R.id.homeRowName));
		assertEquals(HomeRowTime.format(ctx, now, now),
				text(row, R.id.homeRowTime));
		assertTrue(!text(row, R.id.homeRowTime).isEmpty());
		assertEquals("Are we still on?", text(row, R.id.homeRowPreview));
	}

	@Test
	public void thePresenceIsSpokenWithTheRow() {
		long now = System.currentTimeMillis();
		String online = ctx.getString(R.string.online);
		String offline = ctx.getString(R.string.offline);
		View row = bound(HomeRow.builder("contact:1", "Alex").time(now)
				.preview(null, "hi").presence(HomeRow.Presence.ONLINE)
				.build());
		String said = row.getContentDescription().toString();
		assertTrue(said, said.contains(online));
		View away = bound(HomeRow.builder("contact:1", "Alex").time(now)
				.preview(null, "hi").presence(HomeRow.Presence.OFFLINE)
				.build());
		String saidAway = away.getContentDescription().toString();
		assertTrue(saidAway, saidAway.contains(offline));
		View none = bound(HomeRow.builder("group:1", "Club").time(now)
				.preview(null, "hi").build());
		String saidNone = none.getContentDescription().toString();
		assertFalse(saidNone, saidNone.contains(online)
				|| saidNone.contains(offline));
	}

	private static long midnightToday() {
		java.util.Calendar c = java.util.Calendar.getInstance();
		c.set(java.util.Calendar.HOUR_OF_DAY, 0);
		c.set(java.util.Calendar.MINUTE, 0);
		c.set(java.util.Calendar.SECOND, 0);
		c.set(java.util.Calendar.MILLISECOND, 0);
		return c.getTimeInMillis();
	}

	@Test
	public void anHourPrecisionRowInTheMidnightHourShowsTheDay() {
		long midnight = midnightToday();
		long now = midnight + 15L * 60L * 60L * 1000L;
		String shown = HomeRowTime.format(ctx, midnight, now, true);
		assertEquals(android.text.format.DateUtils.formatDateTime(ctx,
				midnight, android.text.format.DateUtils.FORMAT_SHOW_DATE
						| android.text.format.DateUtils.FORMAT_NO_YEAR
						| android.text.format.DateUtils.FORMAT_ABBREV_MONTH),
				shown);
		assertFalse(shown, shown.contains(":"));
		long oneOclock = midnight + 60L * 60L * 1000L;
		assertEquals(com.professor.zerion.android.util.HourTimes.around(ctx,
				oneOclock), HomeRowTime.format(ctx, oneOclock, now, true));
		assertEquals("a chat row at midnight still shows the clock",
				android.text.format.DateUtils.formatDateTime(ctx, midnight,
						android.text.format.DateUtils.FORMAT_SHOW_TIME),
				HomeRowTime.format(ctx, midnight, now, false));
		View row = bound(HomeRow.builder("channel:1", "News").time(midnight)
				.hourPrecision(true).preview(null, "Market").build());
		assertEquals(shown, text(row, R.id.homeRowTime));
	}

	@Test
	public void aRowWithoutActivityShowsNoTime() {
		View row = bound(HomeRow.builder("group:a", "Family")
				.preview(null, "3 members").build());
		assertEquals(View.GONE, visibility(row, R.id.homeRowTime));
	}

	@Test
	public void theUnreadBadgeStopsAt99() {
		View few = bound(HomeRow.builder("group:a", "Family").unread(7)
				.build());
		assertEquals(View.VISIBLE, visibility(few, R.id.homeRowUnread));
		assertEquals("7", text(few, R.id.homeRowUnread));
		assertEquals("99", text(bound(HomeRow.builder("group:a", "Family")
				.unread(99).build()), R.id.homeRowUnread));
		assertEquals("99+", text(bound(HomeRow.builder("group:a", "Family")
				.unread(100).build()), R.id.homeRowUnread));
		assertEquals(View.GONE, visibility(bound(HomeRow.builder("group:a",
				"Family").build()), R.id.homeRowUnread));
	}

	@Test
	public void unreadRowsColourTheTime() {
		long now = System.currentTimeMillis();
		int accent = UiUtils.resolveColorAttribute(ctx,
				R.attr.zerionColorAccentText);
		int muted = UiUtils.resolveColorAttribute(ctx,
				R.attr.zerionColorOnSurfaceMuted);
		TextView unread = bound(HomeRow.builder("contact:1", "Alex")
				.time(now).unread(2).build()).findViewById(R.id.homeRowTime);
		TextView read = bound(HomeRow.builder("contact:1", "Alex")
				.time(now).build()).findViewById(R.id.homeRowTime);
		assertEquals(accent, unread.getCurrentTextColor());
		assertEquals(muted, read.getCurrentTextColor());
	}

	@Test
	public void mutedAndPinnedRowsShowTheirIcons() {
		View row = bound(HomeRow.builder("contact:1", "Alex").muted(true)
				.pinned(true).build());
		assertEquals(View.VISIBLE, visibility(row, R.id.homeRowMuted));
		assertEquals(View.VISIBLE, visibility(row, R.id.homeRowPinned));
		View plain = bound(HomeRow.builder("contact:1", "Alex").build());
		assertEquals(View.GONE, visibility(plain, R.id.homeRowMuted));
		assertEquals(View.GONE, visibility(plain, R.id.homeRowPinned));
	}

	@Test
	public void theSenderComesBeforeThePreview() {
		assertEquals("You: Thanks, see you then", text(bound(
				HomeRow.builder("contact:1", "Maya")
						.preview("You", "Thanks, see you then").build()),
				R.id.homeRowPreview));
		assertEquals("Sam: Two more boxes arrived", text(bound(
				HomeRow.builder("group:a", "Volunteers")
						.preview("Sam", "Two more boxes arrived").build()),
				R.id.homeRowPreview));
	}

	@Test
	public void talkBackHearsTheWholeRow() {
		long now = System.currentTimeMillis();
		View row = bound(HomeRow.builder("group:a", "Volunteers").time(now)
				.preview("Sam", "Two more boxes arrived").unread(150)
				.muted(true).build());
		String when = HomeRowTime.format(ctx, now, now);
		assertEquals("Volunteers, 150 unread, " + when
						+ ", Sam: Two more boxes arrived, Muted",
				String.valueOf(row.getContentDescription()));
		assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
				row.findViewById(R.id.homeRowAvatar)
						.getImportantForAccessibility());
		assertTrue(row.isLongClickable());
	}

	@Test
	public void talkBackHearsQuotedSendersAndRowsWithoutAPreview() {
		long now = System.currentTimeMillis();
		String when = HomeRowTime.format(ctx, now, now);
		View quoted = bound(HomeRow.builder("group:a", "Incog Test").time(now)
				.preview("\"Silver Ibis 41\" (incognito)", "grpreply2025")
				.unread(1).build());
		assertEquals("\"Silver Ibis 41\" (incognito): grpreply2025",
				text(quoted, R.id.homeRowPreview));
		assertEquals("Incog Test, 1 unread, " + when
						+ ", \"Silver Ibis 41\" (incognito): grpreply2025",
				String.valueOf(quoted.getContentDescription()));
		View bare = bound(HomeRow.builder("contact:1", "Alex").time(now)
				.preview("Alex", null).unread(3).pinned(true).build());
		assertEquals("", text(bare, R.id.homeRowPreview));
		assertEquals("Alex, 3 unread, " + when + ", Pinned",
				String.valueOf(bare.getContentDescription()));
		View channel = bound(HomeRow.builder("channel:c", "News")
				.preview(null, "").muted(true).build());
		assertEquals("News, Muted",
				String.valueOf(channel.getContentDescription()));
	}

	@Test
	public void theRowIsRoomyAndItsAvatarIs48dp() {
		View row = bound(HomeRow.builder("contact:1", "Alex").build());
		float density = ctx.getResources().getDisplayMetrics().density;
		assertEquals(Math.round(72 * density), row.getMinimumHeight());
		assertEquals(Math.round(48 * density),
				row.findViewById(R.id.homeRowAvatar).getLayoutParams().width);
		assertEquals(Math.round(48 * density),
				row.findViewById(R.id.homeRowAvatar).getLayoutParams().height);
	}

	@Test
	public void letterAvatarsKeepOneColourPerConversation() {
		View first = bound(HomeRow.builder("group:a", "Family").build());
		View renamed = bound(HomeRow.builder("group:a", "Our family")
				.build());
		ColorStateList a = first.findViewById(R.id.homeRowAvatarText)
				.getBackgroundTintList();
		ColorStateList b = renamed.findViewById(R.id.homeRowAvatarText)
				.getBackgroundTintList();
		assertEquals(a.getDefaultColor(), b.getDefaultColor());
		assertEquals("F", text(first, R.id.homeRowAvatarText));
		assertEquals("O", text(renamed, R.id.homeRowAvatarText));
		Set<Integer> colours = new HashSet<>();
		for (int i = 0; i < 40; i++) {
			colours.add(bound(HomeRow.builder("group:" + i, "G").build())
					.findViewById(R.id.homeRowAvatarText)
					.getBackgroundTintList().getDefaultColor());
		}
		assertTrue("colours " + colours.size(), colours.size() >= 4);
	}

	@Test
	public void everyAvatarColourKeepsItsLetterReadable() {
		HomeAvatarPalette palette = HomeAvatarPalette.load(ctx);
		assertTrue(palette.size() >= 6);
		Set<Integer> distinct = new HashSet<>();
		for (int i = 0; i < 1000 && distinct.size() < palette.size(); i++) {
			distinct.add(palette.fillFor("k" + i));
		}
		assertEquals(palette.size(), distinct.size());
		for (int fill : distinct) {
			double r = ContrastMath.ratio(palette.getLabel(), fill);
			assertTrue(String.format("letter on %08x is %.2f:1", fill, r),
					r >= 4.5);
			assertNotEquals(fill, palette.getLabel());
		}
	}

	@Test
	public void initialsTakeTheFirstLetterOrAQuestionMark() {
		assertEquals("?", HomeRowViewHolder.initial(""));
		assertEquals("?", HomeRowViewHolder.initial("   "));
		assertEquals("Z", HomeRowViewHolder.initial(" zoe"));
		assertEquals("😀", HomeRowViewHolder.initial(
				"😀 party"));
	}

	@Test
	public void longPreviewsAreCutToOneLine() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 30; i++) sb.append("word\n");
		String cut = HomePreview.oneLine(sb.toString());
		assertTrue(cut, cut.indexOf('\n') < 0);
		assertTrue(cut, cut.length() <= HomePreview.MAX_CHARS + 1);
		assertTrue(cut, cut.endsWith("…"));
		assertEquals("hello there", HomePreview.oneLine(" hello\nthere "));
	}
}
