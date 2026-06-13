package com.professor.zerion.android.vault.ui.adapters;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.professor.zerion.R;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.ui.VaultViewModel;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@NotNullByDefault
public class VaultGalleryAdapter extends RecyclerView.Adapter<VaultGalleryAdapter.GalleryViewHolder> {

	private List<VaultItem> items = new ArrayList<>();
	private final OnImageClickListener listener;
	private final VaultViewModel viewModel;

	private static final LruCache<String, Bitmap> thumbnailCache =
			new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
				@Override
				protected int sizeOf(String key, Bitmap bitmap) {
					return bitmap.getByteCount();
				}
			};

	public static void clearThumbnailCache() {
		thumbnailCache.evictAll();
	}

	public interface OnImageClickListener {
		void onImageClick(VaultItem item);
		void onImageLongClick(VaultItem item);
	}

	public VaultGalleryAdapter(VaultViewModel viewModel, OnImageClickListener listener) {
		this.viewModel = viewModel;
		this.listener = listener;
	}

	public void setItems(List<VaultItem> items) {
		this.items = new ArrayList<>();
		for (VaultItem item : items) {
			if (item != null && item.type != null &&
				(item.type == VaultItem.ItemType.IMAGE ||
				 item.type == VaultItem.ItemType.VIDEO)) {
				this.items.add(item);
			}
		}
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public GalleryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_gallery_image, parent, false);
		return new GalleryViewHolder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull GalleryViewHolder holder, int position) {
		VaultItem item = items.get(position);
		holder.bind(item);
	}

	@Override
	public int getItemCount() {
		return items.size();
	}

	class GalleryViewHolder extends RecyclerView.ViewHolder {
		private final MaterialCardView cardView;
		private final ImageView imageView;
		private final ImageView videoIcon;

		private Bitmap currentBitmap;

		GalleryViewHolder(View itemView) {
			super(itemView);
			cardView = itemView.findViewById(R.id.gallery_card);
			imageView = itemView.findViewById(R.id.gallery_image);
			videoIcon = itemView.findViewById(R.id.video_icon);
		}

		void bind(VaultItem item) {
			currentBitmap = null;

			final String expectedItemId = item.id;

			videoIcon.setVisibility(item.type == VaultItem.ItemType.VIDEO ?
					View.VISIBLE : View.GONE);

			imageView.setImageResource(R.drawable.ic_photo);
			loadThumbnail(item, expectedItemId);

			cardView.setOnClickListener(v -> {
				if (listener != null) {
					listener.onImageClick(item);
				}
			});

			cardView.setOnLongClickListener(v -> {
				if (listener != null) {
					listener.onImageLongClick(item);
					return true;
				}
				return false;
			});
		}

		private void loadThumbnail(VaultItem item, String expectedItemId) {
			if (item == null || item.id == null) {
				return;
			}

			Bitmap cached = thumbnailCache.get(item.id);
			if (cached != null && !cached.isRecycled()) {
				currentBitmap = cached;
				imageView.post(() -> {
					imageView.setAlpha(0f);
					imageView.setImageBitmap(cached);
					imageView.animate().alpha(1f).setDuration(150).start();
				});
				return;
			}

			if (item.type == VaultItem.ItemType.VIDEO) {
				return;
			}

			viewModel.getThumbnail(item.id, new VaultViewModel.ThumbnailCallback() {
				@Override
				public void onThumbnailRetrieved(byte[] content) {
					int currentPosition = getAdapterPosition();
					if (currentPosition == RecyclerView.NO_POSITION ||
						currentPosition >= items.size() ||
						!expectedItemId.equals(items.get(currentPosition).id)) {
						Arrays.fill(content, (byte) 0);
						return;
					}

					if (!com.professor.zerion.android.util
							.SafeImageDecoder.hasAllowedMagic(content)) {
						Arrays.fill(content, (byte) 0);
						return;
					}
					BitmapFactory.Options bounds = com.professor.zerion.android.util
							.SafeImageDecoder.probeBounds(content);
					if (bounds == null) {
						Arrays.fill(content, (byte) 0);
						return;
					}
					BitmapFactory.Options options = new BitmapFactory.Options();
					options.outWidth = bounds.outWidth;
					options.outHeight = bounds.outHeight;
					options.inSampleSize = calculateInSampleSize(options, 300, 300);
					options.inJustDecodeBounds = false;

					Bitmap bitmap = BitmapFactory.decodeByteArray(content, 0, content.length, options);

					if (bitmap != null) {
						thumbnailCache.put(item.id, bitmap);
						currentBitmap = bitmap;

						imageView.post(() -> {
							imageView.setAlpha(0f);
							imageView.setImageBitmap(bitmap);
							imageView.animate().alpha(1f).setDuration(150).start();
						});
					}

					Arrays.fill(content, (byte) 0);
				}

				@Override
				public void onError(String error) {
					imageView.post(() -> {
						imageView.setImageResource(R.drawable.ic_photo);
					});
				}
			});
		}

		private int calculateInSampleSize(BitmapFactory.Options options,
				int reqWidth, int reqHeight) {
			final int height = options.outHeight;
			final int width = options.outWidth;
			int inSampleSize = 1;

			if (height > reqHeight || width > reqWidth) {
				final int halfHeight = height / 2;
				final int halfWidth = width / 2;

				while ((halfHeight / inSampleSize) >= reqHeight
						&& (halfWidth / inSampleSize) >= reqWidth) {
					inSampleSize *= 2;
				}
			}

			return inSampleSize;
		}
	}
}