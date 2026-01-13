package com.professor.zerion.android.view;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import com.professor.zerion.R;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.attachment.media.VideoThumbnailExtractor;
import com.bumptech.glide.Glide;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static com.bumptech.glide.load.engine.DiskCacheStrategy.NONE;
import static com.bumptech.glide.load.resource.bitmap.DownsampleStrategy.FIT_CENTER;
import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

@NotNullByDefault
class ImagePreviewViewHolder extends ViewHolder {

	@DrawableRes
	private static final int ERROR_RES = R.drawable.ic_image_broken;

	private final ImageView imageView;
	private final ProgressBar progressBar;
	@Nullable
	private final ImageView playOverlay;
	@Nullable
	private final VideoThumbnailExtractor videoThumbnailExtractor;
	@Nullable
	private final Executor ioExecutor;

	ImagePreviewViewHolder(View v) {
		this(v, null, null);
	}

	ImagePreviewViewHolder(View v,
			@Nullable VideoThumbnailExtractor videoThumbnailExtractor,
			@Nullable Executor ioExecutor) {
		super(v);
		this.imageView = v.findViewById(R.id.imageView);
		this.progressBar = v.findViewById(R.id.progressBar);
		this.playOverlay = v.findViewById(R.id.playOverlay);
		this.videoThumbnailExtractor = videoThumbnailExtractor;
		this.ioExecutor = ioExecutor;
	}

	void bind(ImagePreviewItem item) {
		if (item.getItem() == null) {
			progressBar.setVisibility(VISIBLE);
			if (playOverlay != null) playOverlay.setVisibility(GONE);
			Glide.with(imageView).clear(imageView);
		} else {
			AttachmentItem attachmentItem = item.getItem();
			boolean isVideo = attachmentItem.isVideo();
			if (playOverlay != null) {
				playOverlay.setVisibility(isVideo ? VISIBLE : GONE);
			}
			if (isVideo) {
				loadVideoThumbnail(item.getUri());
			} else {
				loadImage(attachmentItem);
			}
		}
	}

	private void loadImage(AttachmentItem attachmentItem) {
		Glide.with(imageView)
				.load(attachmentItem.getHeader())
				.diskCacheStrategy(NONE)
				.error(ERROR_RES)
				.downsample(FIT_CENTER)
				.transition(withCrossFade())
				.addListener(new RequestListener<Drawable>() {
					@Override
					public boolean onLoadFailed(@Nullable GlideException e,
							Object model, Target<Drawable> target,
							boolean isFirstResource) {
						progressBar.setVisibility(INVISIBLE);
						return false;
					}

					@Override
					public boolean onResourceReady(Drawable resource,
							Object model, Target<Drawable> target,
							DataSource dataSource,
							boolean isFirstResource) {
						progressBar.setVisibility(INVISIBLE);
						return false;
					}
				})
				.into(imageView);
	}

	private void loadVideoThumbnail(Uri videoUri) {
		if (videoThumbnailExtractor == null || ioExecutor == null) {
			progressBar.setVisibility(INVISIBLE);
			imageView.setImageResource(R.drawable.ic_video);
			return;
		}

		progressBar.setVisibility(VISIBLE);
		ioExecutor.execute(() -> {
			Bitmap thumbnail = videoThumbnailExtractor.extractThumbnail(videoUri);
			imageView.post(() -> {
				progressBar.setVisibility(INVISIBLE);
				if (thumbnail != null) {
					Glide.with(imageView)
							.load(thumbnail)
							.diskCacheStrategy(NONE)
							.error(ERROR_RES)
							.downsample(FIT_CENTER)
							.transition(withCrossFade())
							.into(imageView);
				} else {
					imageView.setImageResource(R.drawable.ic_video);
				}
			});
		});
	}

}
