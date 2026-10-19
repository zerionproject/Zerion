package com.professor.zerion.android.homelist;

import android.app.Dialog;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;

import org.robolectric.Robolectric;
import org.robolectric.shadows.ShadowDialog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;

import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

public final class HomeListProbes {

	private HomeListProbes() {
	}

	public static class ProbeHost extends FragmentActivity
			implements BaseFragment.BaseFragmentListener {

		@Override
		public void runOnDbThread(Runnable runnable) {
		}

		@Override
		public ActivityComponent getActivityComponent() {
			return null;
		}

		@Override
		public void showNextFragment(BaseFragment f) {
		}

		@Override
		public void handleException(Exception e) {
		}
	}

	public static ProbeHost host(BaseFragment fragment, List<Runnable> io) {
		ShadowDialog.reset();
		ProbeHost host = Robolectric.buildActivity(ProbeHost.class)
				.setup().get();
		host.setTheme(R.style.ZerionTheme_NoActionBar);
		host.getSupportFragmentManager().beginTransaction()
				.add(android.R.id.content, fragment).commitNow();
		run(io);
		return host;
	}

	public static void run(List<Runnable> io) {
		for (int round = 0; round < 10; round++) {
			shadowOf(Looper.getMainLooper()).idle();
			if (io.isEmpty()) break;
			List<Runnable> pending = new ArrayList<>(io);
			io.clear();
			for (Runnable r : pending) r.run();
		}
		shadowOf(Looper.getMainLooper()).idle();
	}

	public static void afterDebounce(List<Runnable> io) {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
		run(io);
	}

	public static RecyclerView list(BaseFragment fragment) {
		RecyclerView list = fragment.requireView().findViewById(R.id.homeList);
		assertNotNull("no shared list", list);
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				4000, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 4000);
		return list;
	}

	public static View child(BaseFragment fragment, int index) {
		RecyclerView list = list(fragment);
		View v = list.getChildAt(index);
		assertNotNull("no list item " + index, v);
		return v;
	}

	public static List<View> rows(BaseFragment fragment) {
		RecyclerView list = list(fragment);
		List<View> rows = new ArrayList<>();
		for (int i = 0; i < list.getChildCount(); i++) {
			View v = list.getChildAt(i);
			if (v.getId() == R.id.homeRow) rows.add(v);
		}
		return rows;
	}

	public static String text(View v, int id) {
		TextView t = v.findViewById(id);
		assertNotNull("no view " + id, t);
		return t.getText().toString();
	}

	public static View empty(BaseFragment fragment) {
		list(fragment);
		return fragment.requireView().findViewById(R.id.homeEmpty);
	}

	public static String dialogTitle(Dialog d) {
		TextView title = d.findViewById(androidx.appcompat.R.id.alertTitle);
		assertNotNull("no dialog title", title);
		return title.getText().toString();
	}
}
