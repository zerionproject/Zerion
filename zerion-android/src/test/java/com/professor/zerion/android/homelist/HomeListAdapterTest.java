package com.professor.zerion.android.homelist;

import android.content.Context;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.View;

import com.professor.zerion.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import androidx.recyclerview.widget.RecyclerView;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class HomeListAdapterTest {

	private final List<Runnable> background = new ArrayList<>();
	private final List<String> changes = new ArrayList<>();
	private HomeListAdapter adapter;

	@Before
	public void setUp() {
		adapter = new HomeListAdapter(new HomeListAdapter.Listener() {
			@Override
			public void onRowClick(HomeRow row) {
			}

			@Override
			public boolean onRowLongClick(HomeRow row, View anchor) {
				return false;
			}
		}, background::add);
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
							Object payload) {
						changes.add("changed " + start + "+" + count);
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

	private static HomeRow row(String key, String name, int unread) {
		return HomeRow.builder(key, name).time(1000L).preview(null, "hi")
				.unread(unread).payload(new Object()).build();
	}

	private void submit(HomeItem... items) {
		adapter.submit(Arrays.asList(items), null);
		while (!background.isEmpty()) {
			List<Runnable> run = new ArrayList<>(background);
			background.clear();
			for (Runnable r : run) r.run();
		}
		shadowOf(Looper.getMainLooper()).idle();
	}

	@Test
	public void rowsKeepTheirIdsAcrossUpdates() {
		assertTrue(adapter.hasStableIds());
		submit(row("contact:1", "Alex", 0), row("contact:2", "Maya", 0));
		long alex = adapter.getItemId(0);
		long maya = adapter.getItemId(1);
		assertTrue(alex != maya);
		submit(row("contact:2", "Maya", 3), row("contact:1", "Alex", 0));
		assertEquals(maya, adapter.getItemId(0));
		assertEquals(alex, adapter.getItemId(1));
	}

	@Test
	public void aRowWorksOutItsIdKeyOnce() {
		HomeRow row = row("contact:1", "Alex", 0);
		assertSame(row.idKey(), row.idKey());
		assertEquals("1:contact:1", row.idKey());
		assertEquals("2:deleted:News", new HomeNotice("deleted:News",
				"News was deleted", "News").idKey());
	}

	@Test
	public void aNewDayRefreshesEveryRow() {
		long[] now = {System.currentTimeMillis()};
		adapter.setClock(() -> now[0]);
		submit(row("group:a", "Family", 0), row("group:b", "Club", 0));
		changes.clear();
		submit(row("group:a", "Family", 0), row("group:b", "Club", 0));
		assertEquals("the same day changes nothing",
				new ArrayList<String>(), changes);

		now[0] += 24L * 60L * 60L * 1000L;
		submit(row("group:a", "Family", 0), row("group:b", "Club", 0));

		assertEquals("yesterday's times are shown again",
				Arrays.asList("changed 0+2"), changes);
	}

	@Test
	public void onlyTheRowThatChangedIsUpdated() {
		submit(row("group:a", "Family", 0), row("group:b", "Club", 0),
				row("group:c", "Work", 0));
		changes.clear();
		submit(row("group:a", "Family", 0), row("group:b", "Club", 2),
				row("group:c", "Work", 0));
		assertEquals(Arrays.asList("changed 1+1"), changes);
		changes.clear();
		submit(row("group:a", "Family", 0), row("group:b", "Club", 2),
				row("group:c", "Work", 0));
		assertEquals("a fresh payload alone is not a change",
				new ArrayList<String>(), changes);
	}

	@Test
	public void theDifferenceIsWorkedOutAwayFromTheMainThread() {
		submit(row("channel:a", "News", 0));
		adapter.submit(Arrays.asList(row("channel:a", "News", 1)), null);
		assertFalse("the comparison waits for the background executor",
				background.isEmpty());
		shadowOf(Looper.getMainLooper()).idle();
		assertEquals(0, ((HomeRow) adapter.getCurrentList().get(0))
				.getUnread());
		for (Runnable r : new ArrayList<>(background)) r.run();
		background.clear();
		shadowOf(Looper.getMainLooper()).idle();
		assertEquals(1, ((HomeRow) adapter.getCurrentList().get(0))
				.getUnread());
	}

	@Test
	public void repeatedNamesStillGetTheirOwnKeys() {
		HomeKeys keys = new HomeKeys();
		Set<String> seen = new HashSet<>();
		seen.add(keys.next("deleted", "News"));
		seen.add(keys.next("deleted", "News"));
		seen.add(keys.next("deleted", "2:News"));
		seen.add(keys.next("deleted", "1:News"));
		assertEquals(4, seen.size());
		assertEquals("deleted:1:News", new HomeKeys().next("deleted", "News"));
	}

	@Test
	public void noticesAndInvitesAreNotRows() {
		submit(new HomeNotice("deleted:News", "News was deleted", "News"),
				new HomeInvite("invite:aa", "Club, invited by Sam", "Club",
						new byte[] {1}),
				row("group:a", "Family", 0));
		assertEquals(HomeItem.TYPE_NOTICE, adapter.getItemViewType(0));
		assertEquals(HomeItem.TYPE_INVITE, adapter.getItemViewType(1));
		assertEquals(HomeItem.TYPE_ROW, adapter.getItemViewType(2));
		assertEquals(3, adapter.getItemCount());
	}

	@Test
	public void dividersOnlySeparateRows() {
		Context ctx = new ContextThemeWrapper(
				RuntimeEnvironment.getApplication(), R.style.ZerionTheme);
		submit(new HomeNotice("deleted:News", "News was deleted", "News"),
				row("group:a", "Family", 0), row("group:b", "Club", 0));
		assertFalse(HomeListDivider.dividesBelow(adapter, 0));
		assertTrue(HomeListDivider.dividesBelow(adapter, 1));
		assertFalse(HomeListDivider.dividesBelow(adapter, 2));
		HomeListDivider divider = new HomeListDivider(ctx);
		assertEquals(com.professor.zerion.android.util.UiUtils
				.resolveColorAttribute(ctx, R.attr.zerionColorDivider),
				divider.getColor());
		assertTrue(divider.getHeight() >= 1);
	}
}
