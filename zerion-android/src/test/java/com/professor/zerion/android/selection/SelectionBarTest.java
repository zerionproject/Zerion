package com.professor.zerion.android.selection;

import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.professor.zerion.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SelectionBarTest {

	public static class Probe extends AppCompatActivity {

		RecyclerView list;

		@Override
		protected void onCreate(Bundle state) {
			setTheme(R.style.ZerionTheme_NoActionBar);
			super.onCreate(state);
			list = new RecyclerView(this);
			list.setLayoutManager(new LinearLayoutManager(this));
			list.setAdapter(new Rows());
			setContentView(list);
		}
	}

	private static class Rows extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(
				@NonNull ViewGroup parent, int viewType) {
			View v = new View(parent.getContext());
			v.setLayoutParams(new RecyclerView.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, 100));
			return new RecyclerView.ViewHolder(v) {
			};
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,
				int position) {
		}

		@Override
		public int getItemCount() {
			return 2;
		}
	}

	private static class Host implements SelectionBar.Host {

		Set<SelectionAction> offered = EnumSet.noneOf(SelectionAction.class);
		final List<SelectionAction> done = new ArrayList<>();
		int closed = 0;

		@Override
		public Set<SelectionAction> offeredActions() {
			return offered;
		}

		@Override
		public void onSelectionAction(SelectionAction action) {
			done.add(action);
		}

		@Override
		public void onSelectionClosed() {
			closed++;
		}
	}

	private Probe activity;
	private Host host;
	private SelectionBar bar;

	@Before
	public void setUp() {
		activity = Robolectric.buildActivity(Probe.class).setup().get();
		host = new Host();
		bar = new SelectionBar(activity, host);
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	@Test
	public void theBarSaysHowManyAreSelectedAndTellsTalkBack() {
		host.offered = EnumSet.of(SelectionAction.COPY);
		bar.show(1);
		idle();

		TextView title = bar.getTitleView();
		assertNotNull(title);
		assertEquals("1 selected", title.getText().toString());
		View pane = (View) title.getParent();
		assertEquals("1 selected",
				String.valueOf(ViewCompat.getAccessibilityPaneTitle(pane)));

		bar.show(3);
		idle();
		assertEquals("3 selected", title.getText().toString());
		assertEquals("the pane keeps the count it appeared with",
				"1 selected",
				String.valueOf(ViewCompat.getAccessibilityPaneTitle(pane)));
	}

	@Test
	public void onlyOfferedActionsAppearAndEachIsLabelled() {
		host.offered = EnumSet.of(SelectionAction.REPLY, SelectionAction.COPY,
				SelectionAction.DELETE);
		bar.show(1);
		idle();

		assertLabelled(SelectionAction.REPLY, R.string.btn_reply);
		assertLabelled(SelectionAction.COPY, R.string.copy_text);
		assertLabelled(SelectionAction.DELETE, R.string.delete);
		assertNull(bar.getActionView(SelectionAction.REACT));
		assertNull("nothing for More to hold", bar.getMoreButton());

		host.offered = EnumSet.of(SelectionAction.DELETE, SelectionAction.INFO);
		bar.refresh();
		idle();
		View more = bar.getMoreButton();
		assertNotNull(more);
		assertEquals(activity.getString(R.string.selection_more_description),
				more.getContentDescription().toString());
		assertNull(bar.getActionView(SelectionAction.REPLY));
	}

	private void assertLabelled(SelectionAction action, int label) {
		View v = bar.getActionView(action);
		assertNotNull(action.name(), v);
		assertEquals(activity.getString(label),
				v.getContentDescription().toString());
		assertEquals(activity.getString(label), ((TextView) v.findViewById(
				R.id.selectionActionLabel)).getText().toString());
		assertTrue(v.isClickable());
		assertTrue(v.getMinimumWidth() >= dp(48));
		assertTrue(v.getMinimumHeight() >= dp(48));
	}

	private int dp(int dp) {
		return Math.round(dp * activity.getResources().getDisplayMetrics()
				.density);
	}

	@Test
	public void tappingAnActionRunsIt() {
		host.offered = EnumSet.of(SelectionAction.COPY);
		bar.show(1);
		idle();

		View copy = bar.getActionView(SelectionAction.COPY);
		assertNotNull(copy);
		copy.performClick();
		idle();

		assertEquals(1, host.done.size());
		assertEquals(SelectionAction.COPY, host.done.get(0));
	}

	@Test
	public void moreListsOnlyTheOfferedActions() {
		host.offered = EnumSet.of(SelectionAction.DELETE, SelectionAction.EDIT,
				SelectionAction.INFO);
		bar.show(1);
		idle();

		View more = bar.getMoreButton();
		assertNotNull(more);
		more.performClick();
		idle();
		PopupMenu popup = bar.openMore();
		assertNotNull(popup);
		Menu menu = popup.getMenu();
		List<Integer> visible = new ArrayList<>();
		for (int i = 0; i < menu.size(); i++) {
			if (menu.getItem(i).isVisible()) {
				visible.add(menu.getItem(i).getItemId());
			}
		}
		List<Integer> expected = new ArrayList<>();
		expected.add(R.id.action_edit);
		expected.add(R.id.action_info);
		assertEquals(expected, visible);

		assertTrue(menu.performIdentifierAction(R.id.action_info, 0));
		idle();
		assertEquals(1, host.done.size());
		assertEquals(SelectionAction.INFO, host.done.get(0));
	}

	@Test
	public void severalItemsHideTheActionsForOneItem() {
		host.offered = EnumSet.allOf(SelectionAction.class);
		bar.show(2);
		idle();

		assertNull(bar.getActionView(SelectionAction.REPLY));
		assertNull(bar.getActionView(SelectionAction.REACT));
		assertNotNull(bar.getActionView(SelectionAction.COPY));
		assertNotNull(bar.getActionView(SelectionAction.DELETE));
		assertEquals(EnumSet.of(SelectionAction.COPY, SelectionAction.DELETE,
				SelectionAction.FORWARD), bar.shownActions());
		PopupMenu popup = bar.openMore();
		assertNotNull(popup);
		int shown = 0;
		for (int i = 0; i < popup.getMenu().size(); i++) {
			if (popup.getMenu().getItem(i).isVisible()) {
				shown++;
				assertEquals(R.id.action_forward,
						popup.getMenu().getItem(i).getItemId());
			}
		}
		assertEquals(1, shown);
	}

	@Test
	public void deleteKeepsItsWarningColourInsideMore() {
		host.offered = EnumSet.allOf(SelectionAction.class);
		bar.show(1);
		idle();
		assertNull("on this narrow screen Delete moves into More",
				bar.getActionView(SelectionAction.DELETE));

		PopupMenu popup = bar.openMore();
		assertNotNull(popup);
		android.view.MenuItem delete = popup.getMenu().findItem(
				R.id.action_delete);
		assertTrue(delete.isVisible());
		CharSequence title = delete.getTitle();
		assertTrue("Delete is painted like a warning",
				title instanceof android.text.Spanned);
		android.text.style.ForegroundColorSpan[] spans =
				((android.text.Spanned) title).getSpans(0, title.length(),
						android.text.style.ForegroundColorSpan.class);
		assertEquals(1, spans.length);
		assertEquals(com.professor.zerion.android.util.UiUtils
				.resolveColorAttribute(activity,
						R.attr.zerionColorDangerText),
				spans[0].getForegroundColor());
		assertEquals(activity.getString(R.string.delete), title.toString());
		assertFalse("other entries keep the plain text colour",
				popup.getMenu().findItem(R.id.action_info).getTitle()
						instanceof android.text.Spanned);
	}

	@Test
	public void anActionThatIsNotShownNeverRuns() {
		host.offered = EnumSet.of(SelectionAction.COPY, SelectionAction.EDIT);
		bar.show(2);
		idle();

		assertFalse(bar.perform(SelectionAction.EDIT));
		assertFalse(bar.perform(SelectionAction.REACT));
		assertTrue(host.done.isEmpty());
	}

	@Test
	public void theCloseButtonClearsTheSelection() {
		host.offered = EnumSet.of(SelectionAction.COPY);
		bar.show(1);
		idle();

		View close = activity.findViewById(
				androidx.appcompat.R.id.action_mode_close_button);
		assertNotNull(close);
		assertEquals(activity.getString(R.string.selection_clear),
				close.getContentDescription().toString());
		close.performClick();
		idle();

		assertFalse(bar.isShowing());
		assertEquals(1, host.closed);
		assertEquals(0, bar.getCount());
	}

	@Test
	public void backClearsTheSelection() {
		host.offered = EnumSet.of(SelectionAction.COPY);
		bar.show(1);
		idle();

		pressBack(activity);

		assertFalse(bar.isShowing());
		assertEquals(1, host.closed);
		assertFalse(activity.isFinishing());
	}

	@Test
	public void tappingBesideTheRowsClearsTheSelection() {
		host.offered = EnumSet.of(SelectionAction.COPY);
		SelectionRows.clearOnEmptyTap(activity.list, bar);
		layOut(activity.list);
		bar.show(1);
		idle();

		tap(activity.list, 50);
		assertTrue("a tap on a row keeps the selection", bar.isShowing());

		tap(activity.list, 900);
		assertFalse(bar.isShowing());
		assertEquals(1, host.closed);
	}

	@Test
	public void aSelectedRowIsMarkedForTheEyeAndForTalkBack() {
		View row = new View(activity);
		SelectionRows.mark(row, true);
		assertTrue(row.isActivated());
		assertTrue(row.isSelected());

		SelectionRows.mark(row, false);
		assertFalse(row.isActivated());
		assertFalse(row.isSelected());
	}

	private static float[] startOfText(View v) {
		if (v instanceof TextView && ((TextView) v).getLayout() != null) {
			TextView t = (TextView) v;
			android.text.Layout l = t.getLayout();
			return new float[] {t.getTotalPaddingLeft() + 4f,
					t.getTotalPaddingTop()
							+ (l.getLineTop(0) + l.getLineBottom(0)) / 2f};
		}
		return new float[] {v.getWidth() / 2f, v.getHeight() / 2f};
	}

	private static void touch(View v, long downAt, long at, int action) {
		float[] p = startOfText(v);
		View through = v;
		for (android.view.ViewParent up = v.getParent(); up instanceof View;
				up = up.getParent()) {
			p[0] += through.getLeft() - ((View) up).getScrollX();
			p[1] += through.getTop() - ((View) up).getScrollY();
			through = (View) up;
			if (through instanceof RecyclerView) break;
		}
		MotionEvent e = MotionEvent.obtain(downAt, at, action, p[0], p[1], 0);
		through.dispatchTouchEvent(e);
		e.recycle();
	}

	private static long afterEarlierTouches() {
		shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(
				android.view.ViewConfiguration.getDoubleTapTimeout() + 100L));
		return SystemClock.uptimeMillis();
	}

	public static void longPress(View v) {
		long now = afterEarlierTouches();
		touch(v, now, now, MotionEvent.ACTION_DOWN);
		long held = android.view.ViewConfiguration.getLongPressTimeout()
				+ 100L;
		shadowOf(Looper.getMainLooper()).idleFor(
				java.time.Duration.ofMillis(held));
		touch(v, now, now + held, MotionEvent.ACTION_UP);
		idle();
	}

	public static void tap(View v) {
		long now = afterEarlierTouches();
		touch(v, now, now, MotionEvent.ACTION_DOWN);
		shadowOf(Looper.getMainLooper()).idleFor(
				java.time.Duration.ofMillis(30));
		touch(v, now, now + 30, MotionEvent.ACTION_UP);
		idle();
	}

	public static boolean drawsHighlight(View row) {
		return row.getBackground() == null && SelectedLook.drawsSelected(row);
	}

	public static void pressBack(android.app.Activity activity) {
		android.view.Window.Callback window =
				activity.getWindow().getCallback();
		window.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
				KeyEvent.KEYCODE_BACK));
		window.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,
				KeyEvent.KEYCODE_BACK));
		idle();
	}

	@Test
	public void longPressIsNamedSelect() {
		View row = new View(activity);
		row.setOnLongClickListener(v -> true);
		SelectionRows.nameLongPress(row);

		boolean named = false;
		for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
				a : row.createAccessibilityNodeInfo().getActionList()) {
			if (a.getId() == android.view.accessibility.AccessibilityNodeInfo
					.ACTION_LONG_CLICK && a.getLabel() != null) {
				assertEquals(activity.getString(R.string.selection_select),
						a.getLabel().toString());
				named = true;
			}
		}
		assertTrue(named);
	}

	@Test
	public void aRowThatCannotBeSelectedOffersNoSelect() {
		View row = new View(activity);
		row.setOnLongClickListener(v -> true);
		SelectionRows.nameLongPress(row);
		SelectionRows.notSelectable(row);

		assertFalse(row.isLongClickable());
		for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
				a : row.createAccessibilityNodeInfo().getActionList()) {
			assertFalse(a.getId() == android.view.accessibility
					.AccessibilityNodeInfo.ACTION_LONG_CLICK);
		}
	}

	private static void layOut(View v) {
		v.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1000, View.MeasureSpec.EXACTLY));
		v.layout(0, 0, 1080, 1000);
	}

	private static void tap(View v, float y) {
		long now = SystemClock.uptimeMillis();
		MotionEvent down = MotionEvent.obtain(now, now,
				MotionEvent.ACTION_DOWN, 500f, y, 0);
		MotionEvent up = MotionEvent.obtain(now, now + 50,
				MotionEvent.ACTION_UP, 500f, y, 0);
		v.dispatchTouchEvent(down);
		v.dispatchTouchEvent(up);
		down.recycle();
		up.recycle();
		idle();
	}
}
