package com.professor.zerion.android.sticker;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Curated 36-emoji grid for the Standard sticker tab. */
@NotNullByDefault
class StandardStickerAdapter extends RecyclerView.Adapter<StandardStickerAdapter.VH> {

	interface OnEmojiClickListener {
		void onEmojiPicked(String emoji);
	}

	private final OnEmojiClickListener listener;

	StandardStickerAdapter(OnEmojiClickListener listener) {
		this.listener = listener;
	}

	@NonNull
	@Override
	public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View v = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.list_item_emoji_sticker, parent, false);
		return new VH(v);
	}

	@Override
	public void onBindViewHolder(@NonNull VH holder, int position) {
		String emoji = StickerUtils.STANDARD_PACK[position];
		holder.text.setText(emoji);
		holder.itemView.setOnClickListener(v -> listener.onEmojiPicked(emoji));
	}

	@Override
	public int getItemCount() {
		return StickerUtils.STANDARD_PACK.length;
	}

	static class VH extends RecyclerView.ViewHolder {
		final TextView text;

		VH(View itemView) {
			super(itemView);
			text = (TextView) itemView;
		}
	}
}
