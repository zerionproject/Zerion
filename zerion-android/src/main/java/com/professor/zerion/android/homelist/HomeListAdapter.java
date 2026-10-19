package com.professor.zerion.android.homelist;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;

import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.AsyncDifferConfig;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

@NotNullByDefault
public final class HomeListAdapter
		extends ListAdapter<HomeItem, RecyclerView.ViewHolder> {

	public interface Listener {

		void onRowClick(HomeRow row);

		boolean onRowLongClick(HomeRow row, View anchor);

		default void onNoticeDismissed(HomeNotice notice) {
		}

		default void onInviteAccepted(HomeInvite invite) {
		}

		default void onInviteDeclined(HomeInvite invite) {
		}

		default void onRetry() {
		}
	}

	static final DiffUtil.ItemCallback<HomeItem> DIFF =
			new DiffUtil.ItemCallback<HomeItem>() {
				@Override
				public boolean areItemsTheSame(HomeItem a, HomeItem b) {
					return a.getViewType() == b.getViewType()
							&& a.getKey().equals(b.getKey());
				}

				@Override
				public boolean areContentsTheSame(HomeItem a, HomeItem b) {
					return a.showsTheSameAs(b);
				}
			};

	private static final long DAY_MS = 24L * 60L * 60L * 1000L;

	private final Listener listener;
	private final Map<String, Long> ids = new HashMap<>();
	private long nextId = 1;
	private LongSupplier clock = System::currentTimeMillis;
	private long shownDay = Long.MIN_VALUE;
	@Nullable
	private HomeAvatarPalette palette;

	public HomeListAdapter(Listener listener, Executor diffExecutor) {
		super(new AsyncDifferConfig.Builder<>(DIFF)
				.setBackgroundThreadExecutor(diffExecutor).build());
		this.listener = listener;
		setHasStableIds(true);
	}

	public void submit(List<? extends HomeItem> items,
			@Nullable Runnable committed) {
		long day = dayOf(clock.getAsLong());
		if (day != shownDay) {
			if (shownDay != Long.MIN_VALUE && getItemCount() > 0) {
				notifyItemRangeChanged(0, getItemCount());
			}
			shownDay = day;
		}
		submitList(new ArrayList<>(items), committed);
	}

	@VisibleForTesting
	void setClock(LongSupplier clock) {
		this.clock = clock;
	}

	static long dayOf(long now) {
		return Math.floorDiv(now + TimeZone.getDefault().getOffset(now),
				DAY_MS);
	}

	@Override
	public int getItemViewType(int position) {
		return getItem(position).getViewType();
	}

	@Override
	public long getItemId(int position) {
		String key = getItem(position).idKey();
		Long id = ids.get(key);
		if (id == null) {
			id = nextId++;
			ids.put(key, id);
		}
		return id;
	}

	@Override
	public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent,
			int viewType) {
		LayoutInflater inflater = LayoutInflater.from(parent.getContext());
		if (viewType == HomeItem.TYPE_NOTICE) {
			NoticeHolder h = new NoticeHolder(inflater.inflate(
					R.layout.list_item_home_notice, parent, false));
			h.close.setOnClickListener(v -> {
				HomeItem item = itemAt(h);
				if (item instanceof HomeNotice) {
					listener.onNoticeDismissed((HomeNotice) item);
				}
			});
			return h;
		}
		if (viewType == HomeItem.TYPE_STATE) {
			StateHolder h = new StateHolder(inflater.inflate(
					R.layout.view_home_empty, parent, false));
			h.retry.setOnClickListener(v -> listener.onRetry());
			return h;
		}
		if (viewType == HomeItem.TYPE_INVITE) {
			InviteHolder h = new InviteHolder(inflater.inflate(
					R.layout.list_item_home_invite, parent, false));
			h.accept.setOnClickListener(v -> {
				HomeItem item = itemAt(h);
				if (item instanceof HomeInvite) {
					listener.onInviteAccepted((HomeInvite) item);
				}
			});
			h.decline.setOnClickListener(v -> {
				HomeItem item = itemAt(h);
				if (item instanceof HomeInvite) {
					listener.onInviteDeclined((HomeInvite) item);
				}
			});
			return h;
		}
		if (palette == null) palette = HomeAvatarPalette.load(parent.getContext());
		HomeRowViewHolder h = new HomeRowViewHolder(inflater.inflate(
				R.layout.list_item_home_row, parent, false), palette);
		h.itemView.setOnClickListener(v -> {
			HomeItem item = itemAt(h);
			if (item instanceof HomeRow) listener.onRowClick((HomeRow) item);
		});
		h.itemView.setOnLongClickListener(v -> {
			HomeItem item = itemAt(h);
			return item instanceof HomeRow
					&& listener.onRowLongClick((HomeRow) item, v);
		});
		return h;
	}

	@Nullable
	private HomeItem itemAt(RecyclerView.ViewHolder h) {
		int pos = h.getBindingAdapterPosition();
		if (pos == RecyclerView.NO_POSITION || pos >= getItemCount()) {
			return null;
		}
		return getItem(pos);
	}

	@Override
	public void onBindViewHolder(RecyclerView.ViewHolder holder,
			int position) {
		HomeItem item = getItem(position);
		if (holder instanceof HomeRowViewHolder) {
			((HomeRowViewHolder) holder).bind((HomeRow) item,
					clock.getAsLong());
		} else if (holder instanceof NoticeHolder) {
			((NoticeHolder) holder).text.setText(((HomeNotice) item).getText());
		} else if (holder instanceof InviteHolder) {
			((InviteHolder) holder).title.setText(
					((HomeInvite) item).getTitle());
		} else if (holder instanceof StateHolder) {
			((StateHolder) holder).bind((HomeState) item);
		}
	}

	@NotNullByDefault
	static final class NoticeHolder extends RecyclerView.ViewHolder {

		final TextView text;
		final View close;

		NoticeHolder(View v) {
			super(v);
			text = v.findViewById(R.id.homeNoticeText);
			close = v.findViewById(R.id.homeNoticeClose);
		}
	}

	@NotNullByDefault
	static final class StateHolder extends RecyclerView.ViewHolder {

		final ImageView icon;
		final TextView title;
		final TextView body;
		final View retry;

		StateHolder(View v) {
			super(v);
			icon = v.findViewById(R.id.homeEmptyIcon);
			title = v.findViewById(R.id.homeEmptyTitle);
			body = v.findViewById(R.id.homeEmptyBody);
			retry = v.findViewById(R.id.homeEmptyRetry);
		}

		void bind(HomeState state) {
			icon.setImageResource(state.icon);
			title.setText(state.title);
			if (state.body == 0) {
				body.setVisibility(View.GONE);
			} else {
				body.setText(state.body);
				body.setVisibility(View.VISIBLE);
			}
			retry.setVisibility(state.retry ? View.VISIBLE : View.GONE);
		}
	}

	@NotNullByDefault
	static final class InviteHolder extends RecyclerView.ViewHolder {

		final TextView title;
		final View accept;
		final View decline;

		InviteHolder(View v) {
			super(v);
			title = v.findViewById(R.id.homeInviteTitle);
			accept = v.findViewById(R.id.homeInviteAccept);
			decline = v.findViewById(R.id.homeInviteDecline);
		}
	}
}
