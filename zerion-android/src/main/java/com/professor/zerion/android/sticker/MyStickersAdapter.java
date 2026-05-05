package com.professor.zerion.android.sticker;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Imported-stickers grid with tap-to-send and long-press-to-delete. */
@NotNullByDefault
class MyStickersAdapter extends RecyclerView.Adapter<MyStickersAdapter.VH> {

	interface Listener {
		void onStickerPicked(String id, byte[] pngBytes);
		void onStickerDeleteRequested(String id);
	}

	private final StickerStorage storage;
	private final Executor ioExecutor;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final Listener listener;
	private final List<String> ids = new ArrayList<>();

	MyStickersAdapter(StickerStorage storage, Executor ioExecutor,
			Listener listener) {
		this.storage = storage;
		this.ioExecutor = ioExecutor;
		this.listener = listener;
	}

	void reload() {
		ids.clear();
		ids.addAll(storage.listIds());
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View v = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.list_item_my_sticker, parent, false);
		return new VH(v);
	}

	@Override
	public void onBindViewHolder(@NonNull VH holder, int position) {
		String id = ids.get(position);
		holder.image.setImageDrawable(null);
		ioExecutor.execute(() -> {
			try {
				byte[] png = storage.load(id);
				Bitmap bmp = BitmapFactory.decodeByteArray(
						png, 0, png.length);
				main.post(() -> {
					if (holder.getBindingAdapterPosition() == position
							&& bmp != null) {
						holder.image.setImageBitmap(bmp);
					}
				});
			} catch (Exception ignored) {
			}
		});
		holder.itemView.setOnClickListener(v -> {
			ioExecutor.execute(() -> {
				try {
					byte[] png = storage.load(id);
					main.post(() -> listener.onStickerPicked(id, png));
				} catch (Exception ignored) {
				}
			});
		});
		holder.itemView.setOnLongClickListener(v -> {
			listener.onStickerDeleteRequested(id);
			return true;
		});
	}

	@Override
	public int getItemCount() {
		return ids.size();
	}

	static class VH extends RecyclerView.ViewHolder {
		final ImageView image;

		VH(View itemView) {
			super(itemView);
			image = (ImageView) itemView;
		}
	}
}
