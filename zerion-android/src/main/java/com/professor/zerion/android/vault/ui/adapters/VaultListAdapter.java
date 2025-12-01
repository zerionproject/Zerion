package com.professor.zerion.android.vault.ui.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.professor.zerion.R;
import com.professor.zerion.android.vault.model.VaultItem;

import org.briarproject.nullsafety.NotNullByDefault;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@NotNullByDefault
public class VaultListAdapter extends RecyclerView.Adapter<VaultListAdapter.VaultItemViewHolder> {

	private List<VaultItem> items = new ArrayList<>();
	private final OnItemClickListener listener;
	private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());

	public interface OnItemClickListener {
		void onItemClick(VaultItem item);
		void onItemLongClick(VaultItem item);
	}

	public VaultListAdapter(OnItemClickListener listener) {
		this.listener = listener;
	}

	public void setItems(List<VaultItem> items) {
		this.items = new ArrayList<>(items);
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public VaultItemViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_vault_entry, parent, false);
		return new VaultItemViewHolder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull VaultItemViewHolder holder, int position) {
		VaultItem item = items.get(position);
		holder.bind(item);
	}

	@Override
	public int getItemCount() {
		return items.size();
	}

	class VaultItemViewHolder extends RecyclerView.ViewHolder {
		private final MaterialCardView cardView;
		private final ImageView iconView;
		private final TextView titleView;
		private final TextView subtitleView;
		private final TextView dateView;
		private final ImageView lockIcon;

		VaultItemViewHolder(View itemView) {
			super(itemView);
			cardView = itemView.findViewById(R.id.item_card);
			iconView = itemView.findViewById(R.id.item_icon);
			titleView = itemView.findViewById(R.id.item_title);
			subtitleView = itemView.findViewById(R.id.item_subtitle);
			dateView = itemView.findViewById(R.id.item_date);
			lockIcon = itemView.findViewById(R.id.lock_icon);
		}

		void bind(VaultItem item) {
			String displayTitle = item.name.startsWith("🔒 ") ?
					item.name.substring(2) : item.name;
			titleView.setText(displayTitle);

			lockIcon.setVisibility(item.name.startsWith("🔒 ") ? View.VISIBLE : View.GONE);

			switch (item.type) {
				case NOTE:
					iconView.setImageResource(R.drawable.ic_note);
					subtitleView.setText("Secure Note");
					break;
				case IMAGE:
					iconView.setImageResource(R.drawable.ic_photo);
					subtitleView.setText("Image • " + formatFileSize(item.size));
					break;
				case VIDEO:
					iconView.setImageResource(R.drawable.ic_video);
					subtitleView.setText("Video • " + formatFileSize(item.size));
					break;
				case DOCUMENT:
					iconView.setImageResource(R.drawable.ic_document);
					subtitleView.setText("Document • " + formatFileSize(item.size));
					break;
				case PASSWORD:
					iconView.setImageResource(R.drawable.ic_key);
					subtitleView.setText("Password Entry");
					break;
			}

			dateView.setText(dateFormat.format(new Date(item.modifiedTimestamp)));

			cardView.setOnClickListener(v -> {
				if (listener != null) {
					listener.onItemClick(item);
				}
			});

			cardView.setOnLongClickListener(v -> {
				if (listener != null) {
					listener.onItemLongClick(item);
					return true;
				}
				return false;
			});
		}

		private String formatFileSize(long bytes) {
			if (bytes < 1024) return bytes + " B";
			int exp = (int) (Math.log(bytes) / Math.log(1024));
			String pre = "KMGTPE".charAt(exp - 1) + "";
			return String.format(Locale.getDefault(), "%.1f %sB",
					bytes / Math.pow(1024, exp), pre);
		}
	}
}