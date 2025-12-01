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
public class VaultPasswordsAdapter extends RecyclerView.Adapter<VaultPasswordsAdapter.PasswordViewHolder> {

	private List<VaultItem> items = new ArrayList<>();
	private final OnPasswordClickListener listener;
	private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());

	public interface OnPasswordClickListener {
		void onPasswordClick(VaultItem item);
		void onPasswordLongClick(VaultItem item);
	}

	public VaultPasswordsAdapter(OnPasswordClickListener listener) {
		this.listener = listener;
	}

	public void setItems(List<VaultItem> items) {
		this.items = new ArrayList<>();
		for (VaultItem item : items) {
			if (item.type == VaultItem.ItemType.PASSWORD) {
				this.items.add(item);
			}
		}
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public PasswordViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_vault_password, parent, false);
		return new PasswordViewHolder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull PasswordViewHolder holder, int position) {
		VaultItem item = items.get(position);
		holder.bind(item);
	}

	@Override
	public int getItemCount() {
		return items.size();
	}

	class PasswordViewHolder extends RecyclerView.ViewHolder {
		private final MaterialCardView cardView;
		private final ImageView iconView;
		private final TextView titleView;
		private final TextView subtitleView;
		private final TextView dateView;
		private final ImageView copyIcon;

		PasswordViewHolder(View itemView) {
			super(itemView);
			cardView = itemView.findViewById(R.id.password_card);
			iconView = itemView.findViewById(R.id.password_icon);
			titleView = itemView.findViewById(R.id.password_title);
			subtitleView = itemView.findViewById(R.id.password_subtitle);
			dateView = itemView.findViewById(R.id.password_date);
			copyIcon = itemView.findViewById(R.id.copy_icon);
		}

		void bind(VaultItem item) {
			titleView.setText(item.name);
			subtitleView.setText("••••••••");
			dateView.setText(dateFormat.format(new Date(item.modifiedTimestamp)));

			cardView.setOnClickListener(v -> {
				if (listener != null) {
					listener.onPasswordClick(item);
				}
			});

			cardView.setOnLongClickListener(v -> {
				if (listener != null) {
					listener.onPasswordLongClick(item);
					return true;
				}
				return false;
			});

			copyIcon.setOnClickListener(v -> {
				if (listener != null) {
					listener.onPasswordClick(item);
				}
			});
		}
	}
}