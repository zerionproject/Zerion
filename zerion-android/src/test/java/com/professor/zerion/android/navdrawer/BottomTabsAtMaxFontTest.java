package com.professor.zerion.android.navdrawer;

import android.content.Context;
import android.text.Layout;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.maxfont.MaxFontTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class BottomTabsAtMaxFontTest extends MaxFontTest {

	private static final int[] TABS = {R.id.tabContacts, R.id.tabGroupChats,
			R.id.tabChannels};

	private static View home(Largest a, int heightDp, float textSp) {
		Context c = new ContextThemeWrapper(a.getBaseContext(),
				R.style.ZerionTheme_NoActionBar);
		LayoutInflater inflater = LayoutInflater.from(c);
		inflater.setFactory2(new LayoutInflater.Factory2() {
			@Override
			public View onCreateView(View parent, String name, Context context,
					android.util.AttributeSet attrs) {
				if (name.endsWith("FragmentContainerView")) {
					return new FrameLayout(context, attrs);
				}
				return null;
			}

			@Override
			public View onCreateView(String name, Context context,
					android.util.AttributeSet attrs) {
				return onCreateView(null, name, context, attrs);
			}
		});
		View root = inflater.inflate(R.layout.activity_nav_drawer, null,
				false);
		root.setLayoutParams(new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		NavDrawerActivity.sizeBottomNavigation(
				root.findViewById(R.id.bottomNavigation), heightDp, textSp,
				root.findViewById(R.id.tabContacts),
				root.findViewById(R.id.tabGroupChats),
				root.findViewById(R.id.tabChannels));
		TextView selected = root.findViewById(R.id.tabGroupChats);
		selected.setTypeface(null, android.graphics.Typeface.BOLD);
		layoutIn(root, screenWidth(c), c.getResources().getDisplayMetrics()
				.heightPixels);
		return root;
	}

	private static void assertLabelsFit(Largest a, int heightDp,
			float textSp) {
		View root = home(a, heightDp, textSp);
		View bar = root.findViewById(R.id.bottomNavigation);
		for (int id : TABS) {
			TextView tab = root.findViewById(id);
			String name = tab.getText().toString();
			Layout layout = tab.getLayout();
			assertFalse(name + " is cut short", ellipsized(tab));
			assertNull(name + " breaks inside a word", brokenWord(tab));
			assertEquals(name + " lost its end", name.length(),
					layout.getLineEnd(layout.getLineCount() - 1));
			int room = tab.getWidth() - tab.getCompoundPaddingLeft()
					- tab.getCompoundPaddingRight();
			for (int i = 0; i < layout.getLineCount(); i++) {
				assertTrue(name + " is " + layout.getLineMax(i)
								+ " wide in " + room,
						layout.getLineMax(i) <= room);
			}
			int textBottom = tab.getCompoundPaddingTop() + layout.getHeight();
			assertTrue(name + " text ends at " + textBottom
							+ " in a label of " + tab.getHeight(),
					textBottom <= tab.getHeight() - tab.getPaddingBottom());
			int[] b = boundsIn(tab, root);
			int[] barBounds = boundsIn(bar, root);
			assertTrue(name + " sticks out of the bar",
					b[1] >= barBounds[1] && b[3] <= barBounds[3]);
			assertTrue(name + " is below the screen",
					barBounds[3] <= root.getHeight());
		}
		View fab = root.findViewById(R.id.fabCompose);
		int[] fabBounds = boundsIn(fab, root);
		assertTrue("the compose button covers the tabs",
				fabBounds[3] <= boundsIn(bar, root)[1]);
	}

	@Test
	public void dutchTabLabelsFitAtTheLargestText() {
		Largest a = activity();
		assertLabelsFit(a, 64, 14f);
	}

	@Test
	public void compactAndLargeTabsFitAtTheLargestText() {
		Largest a = activity();
		assertLabelsFit(a, 48, 12f);
		assertLabelsFit(a, 80, 16f);
	}

	@Test
	@Config(qualifiers = "de-w360dp-h740dp-xhdpi")
	public void germanTabLabelsFitAtTheLargestText() {
		assertLabelsFit(activity(), 64, 14f);
	}

	@Test
	@Config(qualifiers = "sv-w360dp-h740dp-xhdpi")
	public void swedishTabLabelsFitAtTheLargestText() {
		assertLabelsFit(activity(), 64, 14f);
	}

	@Test
	public void normalTextKeepsTheIconsAndLabelsWhereTheyWere() {
		Normal a = normalActivity();
		float density = dp(a);
		int[][] sizes = {{48, 12, 4}, {64, 14, 6}, {80, 16, 6}};
		for (int[] size : sizes) {
			View root = home(a, size[0], size[1]);
			for (int id : TABS) {
				TextView tab = root.findViewById(id);
				int top = Math.round(size[2] * density);
				assertEquals(tab.getText() + " icon in a " + size[0]
						+ "dp bar", top, tab.getPaddingTop());
				int labelTop = top + tab.getCompoundDrawables()[1]
						.getBounds().height() + tab.getCompoundDrawablePadding();
				int box = tab.getHeight() - labelTop;
				int expected = labelTop + Math.max(0,
						(box - tab.getLayout().getHeight()) / 2);
				assertEquals(tab.getText() + " label in a " + size[0]
						+ "dp bar", expected, tab.getTotalPaddingTop());
			}
		}
	}

	@Test
	public void normalTextKeepsTheBarHeightsAndTheComposeButtonPlace() {
		Normal a = normalActivity();
		float density = dp(a);
		int[][] sizes = {{48, 12}, {64, 14}, {80, 16}};
		for (int[] size : sizes) {
			View root = home(a, size[0], size[1]);
			View bar = root.findViewById(R.id.bottomNavigation);
			assertEquals(size[0] + "dp bar", Math.round(size[0] * density),
					bar.getHeight());
			View fab = root.findViewById(R.id.fabCompose);
			assertEquals("the compose button above a " + size[0] + "dp bar",
					Math.round(80 * density),
					root.getHeight() - boundsIn(fab, root)[3]);
			for (int id : TABS) {
				TextView tab = root.findViewById(id);
				int textBottom = tab.getCompoundPaddingTop()
						+ tab.getLayout().getHeight();
				assertTrue(tab.getText() + " is clipped in a " + size[0]
						+ "dp bar", textBottom <= tab.getHeight());
			}
		}
	}

	@Test
	@Config(qualifiers = "en-w360dp-h740dp-xhdpi")
	public void aTwoWordLabelWrapsBeforeItShrinks() {
		Largest a = activity();
		View root = home(a, 64, 14f);
		TextView groups = root.findViewById(R.id.tabGroupChats);
		float full = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f,
				a.getResources().getDisplayMetrics());
		assertEquals(groups.getText() + " shrank", full,
				groups.getTextSize(), 0.01f);
		assertEquals(2, groups.getLayout().getLineCount());
		assertNull(brokenWord(groups));
	}

	@Test
	public void theLastHomeRowScrollsClearOfTheComposeButton() {
		Largest a = activity();
		View root = home(a, 64, 14f);
		View container = root.findViewById(R.id.fragmentContainer);
		View fab = root.findViewById(R.id.fabCompose);
		int covered = boundsIn(container, root)[3] - boundsIn(fab, root)[1];
		RecyclerView list = LayoutInflater.from(a).inflate(
				R.layout.fragment_home_list, null, false)
				.findViewById(R.id.homeList);
		assertFalse(list.getClipToPadding());
		assertTrue("the list ends " + list.getPaddingBottom()
						+ "px early but the button covers " + covered + "px",
				list.getPaddingBottom() >= covered);
	}
}
