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

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@NotNullByDefault
public class VaultDocumentsAdapter extends RecyclerView.Adapter<VaultDocumentsAdapter.DocumentViewHolder> {

	private List<VaultItem> items = new ArrayList<>();
	private final OnDocumentClickListener listener;
	private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.US);

	public interface OnDocumentClickListener {
		void onDocumentClick(VaultItem item);
		void onDocumentLongClick(VaultItem item);
	}

	public VaultDocumentsAdapter(OnDocumentClickListener listener) {
		this.listener = listener;
	}

	public void setItems(List<VaultItem> items) {
		this.items = new ArrayList<>();
		for (VaultItem item : items) {
			if (item != null && item.type == VaultItem.ItemType.DOCUMENT) {
				this.items.add(item);
			}
		}
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public DocumentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_vault_document, parent, false);
		return new DocumentViewHolder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull DocumentViewHolder holder, int position) {
		VaultItem item = items.get(position);
		holder.bind(item);
	}

	@Override
	public int getItemCount() {
		return items.size();
	}

	class DocumentViewHolder extends RecyclerView.ViewHolder {
		private final MaterialCardView cardView;
		private final ImageView iconView;
		private final TextView titleView;
		private final TextView sizeView;
		private final TextView dateView;

		DocumentViewHolder(View itemView) {
			super(itemView);
			cardView = itemView.findViewById(R.id.document_card);
			iconView = itemView.findViewById(R.id.document_icon);
			titleView = itemView.findViewById(R.id.document_title);
			sizeView = itemView.findViewById(R.id.document_size);
			dateView = itemView.findViewById(R.id.document_date);
		}

		void bind(VaultItem item) {
			setDocumentIcon(item.name);

			titleView.setText(item.name);
			dateView.setText(dateFormat.format(new Date(item.modifiedTimestamp)));

			sizeView.setText(formatFileSize(item.size));

			cardView.setOnClickListener(v -> {
				if (listener != null) {
					listener.onDocumentClick(item);
				}
			});

			cardView.setOnLongClickListener(v -> {
				if (listener != null) {
					listener.onDocumentLongClick(item);
					return true;
				}
				return false;
			});
		}

		private void setDocumentIcon(String filename) {
			if (filename == null) {
				iconView.setImageResource(R.drawable.ic_file);
				return;
			}

			String extension = "";
			int lastDot = filename.lastIndexOf('.');
			if (lastDot > 0) {
				extension = filename.substring(lastDot + 1).toLowerCase();
			}

			int iconRes;
			switch (extension) {
				case "pdf":
					iconRes = R.drawable.ic_pdf;
					break;
				case "doc":
				case "docx":
					iconRes = R.drawable.ic_doc;
					break;
				case "xls":
				case "xlsx":
				case "csv":
					iconRes = R.drawable.ic_spreadsheet;
					break;
				case "ppt":
				case "pptx":
					iconRes = R.drawable.ic_document;
					break;
				case "txt":
				case "md":
					iconRes = R.drawable.ic_text;
					break;
				case "zip":
				case "rar":
				case "7z":
					iconRes = R.drawable.ic_archive;
					break;
				default:
					iconRes = R.drawable.ic_file;
					break;
			}
			iconView.setImageResource(iconRes);
		}

		private String formatFileSize(long size) {
			if (size <= 0) return "0 B";
			final String[] units = new String[]{"B", "KB", "MB", "GB"};
			int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
			return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups))
					+ " " + units[digitGroups];
		}
	}
}