package com.professor.zerion.android.selection;

import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import com.professor.zerion.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class RowActionsTest {

	private static class Host implements SelectionBar.Host {

		final Set<String> selected = new LinkedHashSet<>();
		Set<SelectionAction> offered = EnumSet.noneOf(SelectionAction.class);
		final List<SelectionAction> done = new ArrayList<>();
		final List<Boolean> barShownWhenDone = new ArrayList<>();
		boolean keepSelection = false;
		SelectionBar bar;
		int closed = 0;

		@Override
		public Set<SelectionAction> offeredActions() {
			return selected.isEmpty() ? EnumSet.noneOf(SelectionAction.class)
					: offered;
		}

		@Override
		public void onSelectionAction(SelectionAction action) {
			done.add(action);
			barShownWhenDone.add(bar.isShowing());
			if (!keepSelection) {
				if (bar.isShowing()) bar.close();
				else onSelectionClosed();
			}
		}

		@Override
		public void onSelectionClosed() {
			closed++;
			selected.clear();
		}

		void select(String key) {
			selected.add(key);
			bar.show(selected.size());
		}
	}

	private SelectionBarTest.Probe activity;
	private Host host;
	private SelectionBar bar;

	@Before
	public void setUp() {
		activity = Robolectric.buildActivity(SelectionBarTest.Probe.class)
				.setup().get();
		host = new Host();
		bar = new SelectionBar(activity, host);
		host.bar = bar;
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static List<String> customActions(View v) {
		AccessibilityNodeInfo info = v.createAccessibilityNodeInfo();
		List<String> out = new ArrayList<>();
		for (AccessibilityNodeInfo.AccessibilityAction a :
				info.getActionList()) {
			if (SelectionAction.byId(a.getId()) != null) {
				out.add(String.valueOf(a.getLabel()));
			}
		}
		return out;
	}

	@Test
	public void onlyTheFourEverydayActionsAndMemberReachARow() {
		Set<SelectionAction> all = EnumSet.allOf(SelectionAction.class);
		assertEquals(EnumSet.of(SelectionAction.REPLY, SelectionAction.REACT,
				SelectionAction.COPY, SelectionAction.DELETE,
				SelectionAction.MEMBER), RowActions.forRow(all));
		assertTrue(RowActions.forRow(EnumSet.of(SelectionAction.INFO,
				SelectionAction.FORWARD, SelectionAction.EDIT)).isEmpty());
	}

	@Test
	public void aRowListsItsActionsByTheBarsNames() {
		View row = new View(activity);
		Set<SelectionAction> offered = EnumSet.of(SelectionAction.DELETE,
				SelectionAction.COPY, SelectionAction.REPLY,
				SelectionAction.REACT, SelectionAction.INFO);
		RowActions.bind(row, new RowActions.Row() {
			@Override
			public Set<SelectionAction> offered() {
				return offered;
			}

			@Override
			public boolean perform(SelectionAction action) {
				return true;
			}
		});
		List<String> names = customActions(row);
		assertEquals("[Reply, React, Copy, Delete]", names.toString());

		RowActions.bind(row, null);
		assertTrue(customActions(row).isEmpty());
	}

	@Test
	public void aRowActionRunsOnlyWhenTheRowStillOffersIt() {
		View row = new View(activity);
		List<SelectionAction> ran = new ArrayList<>();
		Set<SelectionAction> offered = EnumSet.of(SelectionAction.COPY);
		RowActions.bind(row, new RowActions.Row() {
			@Override
			public Set<SelectionAction> offered() {
				return offered;
			}

			@Override
			public boolean perform(SelectionAction action) {
				ran.add(action);
				return true;
			}
		});
		assertTrue(row.performAccessibilityAction(R.id.action_copy, null));
		assertFalse(row.performAccessibilityAction(R.id.action_delete, null));
		assertEquals("[COPY]", ran.toString());
	}

	@Test
	public void aRowActionRunsTheBarsCodeWithoutShowingTheBar() {
		host.offered = EnumSet.of(SelectionAction.COPY);
		assertTrue(bar.performOnly(() -> host.select("a"),
				SelectionAction.COPY, host.selected::size));
		idle();
		assertEquals("[COPY]", host.done.toString());
		assertEquals("[false]", host.barShownWhenDone.toString());
		assertFalse(bar.isShowing());
		assertTrue(host.selected.isEmpty());
	}

	@Test
	public void anActionThatAsksFirstLeavesTheBarUpLikeALongPress() {
		host.offered = EnumSet.of(SelectionAction.DELETE);
		host.keepSelection = true;
		assertTrue(bar.performOnly(() -> host.select("a"),
				SelectionAction.DELETE, host.selected::size));
		idle();
		assertTrue("the bar shows behind the question", bar.isShowing());
		assertEquals(1, bar.getCount());
	}

	@Test
	public void anActionTheBarWouldNotOfferIsRefusedAndClearsTheRow() {
		host.offered = EnumSet.of(SelectionAction.COPY);
		assertFalse(bar.performOnly(() -> host.select("a"),
				SelectionAction.DELETE, host.selected::size));
		idle();
		assertTrue(host.done.isEmpty());
		assertTrue(host.selected.isEmpty());
		assertFalse(bar.isShowing());
	}

	@Test
	public void rowActionsWaitWhileTheBarIsOpen() {
		host.offered = EnumSet.of(SelectionAction.COPY);
		host.select("a");
		idle();
		assertTrue(bar.isShowing());
		assertFalse(bar.performOnly(() -> host.select("b"),
				SelectionAction.COPY, host.selected::size));
		assertEquals(1, host.selected.size());
		assertTrue(host.done.isEmpty());
	}
}
