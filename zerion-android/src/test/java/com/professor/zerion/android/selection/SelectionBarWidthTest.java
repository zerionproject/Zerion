package com.professor.zerion.android.selection;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Looper;
import android.view.Menu;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import java.util.EnumSet;
import java.util.Set;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SelectionBarWidthTest {

	public static class LargeText extends AppCompatActivity {

		float fontScale() {
			return 1.3f;
		}

		@Override
		protected void attachBaseContext(Context base) {
			Configuration c = new Configuration(
					base.getResources().getConfiguration());
			c.fontScale = fontScale();
			super.attachBaseContext(base.createConfigurationContext(c));
		}

		@Override
		protected void onCreate(Bundle state) {
			setTheme(R.style.ZerionTheme_NoActionBar);
			super.onCreate(state);
			setContentView(new FrameLayout(this));
		}
	}

	public static class NormalText extends LargeText {

		@Override
		float fontScale() {
			return 1.0f;
		}
	}

	private static final Set<SelectionAction> OFFERED = EnumSet.of(
			SelectionAction.REPLY, SelectionAction.REACT, SelectionAction.COPY,
			SelectionAction.DELETE, SelectionAction.INFO);

	private static SelectionBar open(AppCompatActivity activity) {
		SelectionBar bar = new SelectionBar(activity, new SelectionBar.Host() {
			@Override
			public Set<SelectionAction> offeredActions() {
				return OFFERED;
			}

			@Override
			public void onSelectionAction(SelectionAction action) {
			}

			@Override
			public void onSelectionClosed() {
			}
		});
		bar.show(1);
		shadowOf(Looper.getMainLooper()).idle();
		View decor = activity.getWindow().getDecorView();
		int width = Math.round(activity.getResources().getConfiguration()
				.screenWidthDp * activity.getResources().getDisplayMetrics()
				.density);
		decor.measure(View.MeasureSpec.makeMeasureSpec(width,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				2000, View.MeasureSpec.EXACTLY));
		decor.layout(0, 0, width, 2000);
		return bar;
	}

	private static void assertEverythingFitsAndIsReachable(
			AppCompatActivity activity, SelectionBar bar) {
		TextView title = bar.getTitleView();
		assertNotNull(title);
		float needed = SelectionBar.textWidth(title, title.getText(),
				title.getAutoSizeMinTextSize()) + title.getPaddingStart()
				+ title.getPaddingEnd();
		assertTrue("the title needs " + needed + " and has "
				+ title.getWidth(), needed <= title.getWidth());
		EnumSet<SelectionAction> reachable =
				EnumSet.noneOf(SelectionAction.class);
		for (SelectionAction a : OFFERED) {
			View v = bar.getActionView(a);
			if (v == null) continue;
			reachable.add(a);
			assertEquals(a.name(), v.getContentDescription() != null, true);
			TextView label = v.findViewById(R.id.selectionActionLabel);
			if (label.getVisibility() == View.VISIBLE) {
				assertTrue(a.name() + " label is cut off",
						SelectionBar.textWidth(label, label.getText(),
								label.getTextSize()) <= label.getMaxWidth());
			}
		}
		View more = bar.getMoreButton();
		assertNotNull(more);
		PopupMenu popup = bar.openMore();
		assertNotNull(popup);
		Menu menu = popup.getMenu();
		for (int i = 0; i < menu.size(); i++) {
			if (!menu.getItem(i).isVisible()) continue;
			SelectionAction a = SelectionAction.byId(menu.getItem(i)
					.getItemId());
			assertNotNull(a);
			assertFalse(a.name() + " shows twice", reachable.contains(a));
			reachable.add(a);
		}
		assertEquals("every offered action can be reached", OFFERED,
				reachable);
	}

	@Test
	@Config(qualifiers = "de-w360dp-h640dp")
	public void aNarrowScreenInGermanWithLargeTextStillFits() {
		LargeText activity =
				Robolectric.buildActivity(LargeText.class).setup().get();
		assertEquals(1.3f, activity.getResources().getConfiguration()
				.fontScale, 0.001f);
		SelectionBar bar = open(activity);

		assertFalse("German labels at large text do not fit under the "
				+ "icons", bar.showsLabels());
		assertEverythingFitsAndIsReachable(activity, bar);
	}

	@Test
	@Config(qualifiers = "ru-w360dp-h640dp")
	public void aNarrowScreenInRussianWithLargeTextStillFits() {
		LargeText activity =
				Robolectric.buildActivity(LargeText.class).setup().get();
		SelectionBar bar = open(activity);

		assertEverythingFitsAndIsReachable(activity, bar);
	}

	@Test
	@Config(qualifiers = "en-w411dp-h800dp")
	public void aRoomyScreenShowsEveryButtonWithItsLabel() {
		NormalText activity =
				Robolectric.buildActivity(NormalText.class).setup().get();
		SelectionBar bar = open(activity);

		assertTrue(bar.showsLabels());
		for (SelectionAction a : new SelectionAction[] {SelectionAction.REPLY,
				SelectionAction.REACT, SelectionAction.COPY,
				SelectionAction.DELETE}) {
			assertNotNull(a.name(), bar.getActionView(a));
		}
		assertEverythingFitsAndIsReachable(activity, bar);
	}
}
