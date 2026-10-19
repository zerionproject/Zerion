package com.professor.zerion.android.homelist;

import android.content.Context;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class HomeListTest {

	private final Context ctx = new ContextThemeWrapper(
			RuntimeEnvironment.getApplication(), R.style.ZerionTheme);
	private final List<Runnable> background = new ArrayList<>();

	private HomeList home() {
		return HomeList.inflate(LayoutInflater.from(ctx), null,
				new HomeListAdapter.Listener() {
					@Override
					public void onRowClick(HomeRow row) {
					}

					@Override
					public boolean onRowLongClick(HomeRow row, View anchor) {
						return false;
					}
				}, background::add, R.drawable.ic_channels,
				R.string.channels_list_empty, 0);
	}

	private void settle() {
		for (int round = 0; round < 5; round++) {
			List<Runnable> run = new ArrayList<>(background);
			background.clear();
			for (Runnable r : run) r.run();
			shadowOf(Looper.getMainLooper()).idle();
		}
	}

	private static HomeRow row(String key, String name) {
		return HomeRow.builder(key, name).preview(null, "hi").build();
	}

	@Test
	public void removingANoticeKeepsANewerListStillBeingCompared() {
		HomeList home = home();
		HomeNotice notice = new HomeNotice("deleted:1:Old", "Old was deleted",
				"Old");
		home.show(Arrays.asList(notice, row("channel:a", "News")));
		settle();
		home.show(Arrays.asList(notice, row("channel:a", "News"),
				row("channel:b", "Club")));
		home.remove(notice.getKey());
		settle();
		List<HomeItem> shown = home.getAdapter().getCurrentList();
		assertEquals(2, shown.size());
		assertEquals("channel:a", shown.get(0).getKey());
		assertEquals("channel:b", shown.get(1).getKey());
	}
}
