package com.professor.zerion.android.vault.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;
import com.professor.zerion.R;
import com.professor.zerion.android.util.DisplayNames;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.util.BookmarkRules;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

@NotNullByDefault
public class VaultBookmarksAdapter
		extends RecyclerView.Adapter<VaultBookmarksAdapter.BookmarkViewHolder> {

	private List<VaultItem> items = new ArrayList<>();
	private Map<String, String> urls = new HashMap<>();
	private final OnBookmarkClickListener listener;

	public interface OnBookmarkClickListener {
		void onBookmarkClick(VaultItem item, String url);

		void onBookmarkOptions(VaultItem item, String url);
	}

	public VaultBookmarksAdapter(OnBookmarkClickListener listener) {
		this.listener = listener;
	}

	public void setItems(List<VaultItem> items, Map<String, String> urls) {
		this.items = new ArrayList<>(items);
		this.urls = new HashMap<>(urls);
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public BookmarkViewHolder onCreateViewHolder(@NonNull ViewGroup parent,
			int viewType) {
		View view = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_vault_bookmark, parent, false);
		return new BookmarkViewHolder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull BookmarkViewHolder holder,
			int position) {
		VaultItem item = items.get(position);
		String url = urls.get(item.id);
		holder.bind(item, url == null ? "" : url);
	}

	@Override
	public int getItemCount() {
		return items.size();
	}

	class BookmarkViewHolder extends RecyclerView.ViewHolder {
		private final MaterialCardView cardView;
		private final TextView titleView;
		private final TextView hostView;
		private final ImageView optionsIcon;

		BookmarkViewHolder(View itemView) {
			super(itemView);
			cardView = itemView.findViewById(R.id.bookmark_card);
			titleView = itemView.findViewById(R.id.bookmark_title);
			hostView = itemView.findViewById(R.id.bookmark_host);
			optionsIcon = itemView.findViewById(R.id.bookmark_options);
		}

		void bind(VaultItem item, String url) {
			titleView.setText(DisplayNames.isolate(
					BookmarkRules.cleanTitle(item.name)));
			String host = BookmarkRules.host(url);
			hostView.setText(host == null ? DisplayNames.clean(url) : host);

			cardView.setOnClickListener(v -> listener.onBookmarkClick(item, url));
			cardView.setOnLongClickListener(v -> {
				listener.onBookmarkOptions(item, url);
				return true;
			});
			optionsIcon.setOnClickListener(
					v -> listener.onBookmarkOptions(item, url));
		}
	}
}
