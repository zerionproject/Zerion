package com.professor.zerion.android.homelist;

import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.annotation.UiThread;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

@NotNullByDefault
public final class HomeList {

	static final long EXPIRY_SLACK_MS = 500L;
	static final String EMPTY_KEY = "empty";
	static final String ERROR_KEY = "error";

	private final View root;
	private final RecyclerView list;
	private final HomeListAdapter adapter;
	private final HomeState empty;
	private final HomeState error;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable expired = this::onExpired;
	@Nullable
	private Runnable reload;
	private List<HomeItem> requested = new ArrayList<>();
	private boolean failed = false;

	private HomeList(View root, HomeListAdapter adapter, HomeState empty,
			HomeState error) {
		this.root = root;
		this.adapter = adapter;
		this.empty = empty;
		this.error = error;
		list = root.findViewById(R.id.homeList);
		list.setLayoutManager(new LinearLayoutManager(root.getContext()));
		list.setAdapter(adapter);
		list.addItemDecoration(new HomeListDivider(root.getContext()));
		RecyclerView.ItemAnimator animator = list.getItemAnimator();
		if (animator instanceof SimpleItemAnimator) {
			((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
		}
	}

	public static HomeList inflate(LayoutInflater inflater,
			@Nullable ViewGroup container, HomeListAdapter.Listener listener,
			Executor diffExecutor, @DrawableRes int emptyIcon,
			@StringRes int emptyTitle, @StringRes int emptyBody) {
		View root = inflater.inflate(R.layout.fragment_home_list, container,
				false);
		return new HomeList(root, new HomeListAdapter(listener, diffExecutor),
				new HomeState(EMPTY_KEY, emptyIcon, emptyTitle, emptyBody,
						false),
				new HomeState(ERROR_KEY, emptyIcon, R.string.home_list_error,
						0, true));
	}

	public View getView() {
		return root;
	}

	public RecyclerView getList() {
		return list;
	}

	public HomeListAdapter getAdapter() {
		return adapter;
	}

	@UiThread
	public void reloadWhenAPreviewExpires(Runnable reload) {
		this.reload = reload;
	}

	@UiThread
	public void release() {
		handler.removeCallbacks(expired);
		reload = null;
	}

	private void onExpired() {
		Runnable r = reload;
		if (r != null) r.run();
	}

	private void scheduleExpiry(List<? extends HomeItem> items) {
		handler.removeCallbacks(expired);
		long next = Long.MAX_VALUE;
		for (HomeItem item : items) {
			if (item instanceof HomeRow) {
				next = Math.min(next, ((HomeRow) item).getExpiresAt());
			}
		}
		if (next == Long.MAX_VALUE || reload == null) return;
		long wait = Math.max(0L, next - System.currentTimeMillis());
		handler.postDelayed(expired, wait + EXPIRY_SLACK_MS);
	}

	@UiThread
	public void show(List<? extends HomeItem> items) {
		failed = false;
		present(items);
	}

	@UiThread
	public void showError(List<? extends HomeItem> kept) {
		failed = true;
		present(kept);
	}

	private void present(List<? extends HomeItem> items) {
		requested = new ArrayList<>(items);
		scheduleExpiry(items);
		List<HomeItem> shown = new ArrayList<>(items);
		if (failed) {
			shown.add(error);
		} else if (!hasContent(items)) {
			shown.add(empty);
		}
		adapter.submit(shown, null);
	}

	private static boolean hasContent(List<? extends HomeItem> items) {
		for (HomeItem item : items) {
			if (item instanceof HomeRow || item instanceof HomeInvite) {
				return true;
			}
		}
		return false;
	}

	@UiThread
	public void remove(String key) {
		List<HomeItem> kept = new ArrayList<>();
		for (HomeItem item : requested) {
			if (!item.getKey().equals(key)) kept.add(item);
		}
		present(kept);
	}
}
