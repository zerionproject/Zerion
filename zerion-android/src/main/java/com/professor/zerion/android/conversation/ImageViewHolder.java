package com.professor.zerion.android.conversation;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.bumptech.glide.load.Transformation;

import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.sync.MessageId;
import com.professor.zerion.R;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.conversation.glide.ZerionImageTransformation;
import com.bumptech.glide.Glide;
import com.professor.zerion.android.conversation.glide.Radii;
import org.briarproject.briar.api.attachment.Attachment;
import org.briarproject.briar.api.attachment.AttachmentNotYetAvailableException;
import org.briarproject.briar.api.attachment.AttachmentReader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.Executor;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;
import androidx.recyclerview.widget.StaggeredGridLayoutManager.LayoutParams;

import static android.widget.ImageView.ScaleType.CENTER_CROP;
import static android.widget.ImageView.ScaleType.FIT_CENTER;
import static com.bumptech.glide.load.engine.DiskCacheStrategy.NONE;
import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;
import static com.professor.zerion.android.attachment.AttachmentItem.State.AVAILABLE;
import static com.professor.zerion.android.attachment.AttachmentItem.State.ERROR;

@NotNullByDefault
class ImageViewHolder extends ViewHolder {

	@DrawableRes
	private static final int ERROR_RES = R.drawable.ic_image_broken;

	private static final int MAX_THUMBNAIL_RETRY_ATTEMPTS = 5;
	private static final long THUMBNAIL_RETRY_DELAY_MS = 300;

	protected final ImageView imageView;
	@Nullable
	protected final ImageView playOverlay;
	private final int imageSize;
	private final MessageId conversationItemId;
	@Nullable
	private final AttachmentReader attachmentReader;
	@Nullable
	@DatabaseExecutor
	private final Executor dbExecutor;

	ImageViewHolder(View v, int imageSize, MessageId conversationItemId) {
		this(v, imageSize, conversationItemId, null, null);
	}

	ImageViewHolder(View v, int imageSize, MessageId conversationItemId,
			@Nullable AttachmentReader attachmentReader,
			@Nullable @DatabaseExecutor Executor dbExecutor) {
		super(v);
		imageView = v.findViewById(R.id.imageView);
		playOverlay = v.findViewById(R.id.playOverlay);
		this.imageSize = imageSize;
		this.conversationItemId = conversationItemId;
		this.attachmentReader = attachmentReader;
		this.dbExecutor = dbExecutor;
	}

	void bind(AttachmentItem attachment, Radii r, boolean single,
			boolean needsStretch) {
		boolean isSticker = attachment.isSticker();
		setViewDimensions(attachment, single, needsStretch, isSticker);

		boolean isVideo = attachment.isVideo();
		if (playOverlay != null) {
			playOverlay.setVisibility(
					isVideo && !isSticker ? View.VISIBLE : View.GONE);
		}

		if (attachment.getState() != AVAILABLE) {
			Glide.with(imageView).clear(imageView);
			if (attachment.getState() == ERROR) {
				imageView.setImageResource(ERROR_RES);
			} else {
				imageView.setImageResource(R.drawable.ic_image_missing);
			}
			imageView.setScaleType(FIT_CENTER);
			if (playOverlay != null) playOverlay.setVisibility(View.GONE);
		} else if (isSticker) {

			loadStickerImage(attachment);
			imageView.setScaleType(FIT_CENTER);
		} else if (isVideo) {
			loadVideoThumbnail(attachment, r);
			imageView.setScaleType(CENTER_CROP);
		} else {
			loadImage(attachment, r);
			imageView.setScaleType(CENTER_CROP);
		}
		imageView.setTransitionName(
				attachment.getTransitionName(conversationItemId));
	}

	private void setViewDimensions(AttachmentItem a, boolean single,
			boolean needsStretch, boolean isSticker) {

		int stickerPx = (int) (160 * itemView.getResources()
				.getDisplayMetrics().density);
		View container = itemView;
		if (container instanceof ViewGroup) {
			LayoutParams params = (LayoutParams) container.getLayoutParams();
			if (isSticker) {
				params.width = stickerPx;
				params.height = stickerPx;
				params.setFullSpan(false);
			} else {
				int width = needsStretch ? imageSize * 2 : imageSize;
				params.width = single ? a.getThumbnailWidth() : width;
				params.height = single ? a.getThumbnailHeight() : imageSize;
				params.setFullSpan(!single && needsStretch);
			}
			container.setLayoutParams(params);
		} else {
			LayoutParams params = (LayoutParams) imageView.getLayoutParams();
			if (isSticker) {
				params.width = stickerPx;
				params.height = stickerPx;
				params.setFullSpan(false);
			} else {
				int width = needsStretch ? imageSize * 2 : imageSize;
				params.width = single ? a.getThumbnailWidth() : width;
				params.height = single ? a.getThumbnailHeight() : imageSize;
				params.setFullSpan(!single && needsStretch);
			}
			imageView.setLayoutParams(params);
		}
	}

	private void loadImage(AttachmentItem a, Radii r) {
		Transformation<Bitmap> transformation = new ZerionImageTransformation(r);
		Glide.with(imageView)
				.load(a.getHeader())
				.diskCacheStrategy(NONE)
				.error(ERROR_RES)
				.transform(transformation)
				.transition(withCrossFade())
				.into(imageView)
				.waitForLayout();
	}

	private void loadStickerImage(AttachmentItem a) {
		Glide.with(imageView)
				.load(a.getHeader())
				.diskCacheStrategy(NONE)
				.error(ERROR_RES)
				.transition(withCrossFade())
				.into(imageView)
				.waitForLayout();
	}

	private void loadVideoThumbnail(AttachmentItem a, Radii r) {
		if (attachmentReader == null || dbExecutor == null) {
			imageView.setImageResource(R.drawable.ic_video);
			imageView.setScaleType(FIT_CENTER);
			return;
		}

		loadVideoThumbnailWithRetry(a, r, 0);
	}

	private void loadVideoThumbnailWithRetry(AttachmentItem a, Radii r,
			int attemptNumber) {
		if (dbExecutor == null || attachmentReader == null) return;

		dbExecutor.execute(() -> {
			try {
				Attachment attachment = attachmentReader.getAttachment(a.getHeader());
				InputStream is = attachment.getStream();

				File tempFile = File.createTempFile("video_thumb_", ".tmp",
						imageView.getContext().getCacheDir());
				tempFile.deleteOnExit();

				FileOutputStream fos = new FileOutputStream(tempFile);
				byte[] buffer = new byte[8192];
				int bytesRead;
				while ((bytesRead = is.read(buffer)) != -1) {
					fos.write(buffer, 0, bytesRead);
				}
				fos.close();
				is.close();

				MediaMetadataRetriever retriever = new MediaMetadataRetriever();
				retriever.setDataSource(tempFile.getAbsolutePath());
				Bitmap thumbnail = retriever.getFrameAtTime(0,
						MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
				if (thumbnail == null) {
					thumbnail = retriever.getFrameAtTime();
				}
				retriever.release();
				tempFile.delete();

				if (thumbnail != null) {
					final Bitmap finalThumbnail = thumbnail;
					imageView.post(() -> {
						Transformation<Bitmap> transformation =
								new ZerionImageTransformation(r);
						Glide.with(imageView)
								.load(finalThumbnail)
								.diskCacheStrategy(NONE)
								.error(ERROR_RES)
								.transform(transformation)
								.transition(withCrossFade())
								.into(imageView);
					});
				} else {
					imageView.post(() -> {
						imageView.setImageResource(R.drawable.ic_video);
						imageView.setScaleType(FIT_CENTER);
					});
				}
			} catch (AttachmentNotYetAvailableException e) {
				if (attemptNumber < MAX_THUMBNAIL_RETRY_ATTEMPTS) {
					try {
						Thread.sleep(THUMBNAIL_RETRY_DELAY_MS);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						return;
					}
					loadVideoThumbnailWithRetry(a, r, attemptNumber + 1);
				} else {
					imageView.post(() -> {
						imageView.setImageResource(R.drawable.ic_video);
						imageView.setScaleType(FIT_CENTER);
					});
				}
			} catch (Exception e) {
				imageView.post(() -> {
					imageView.setImageResource(R.drawable.ic_video);
					imageView.setScaleType(FIT_CENTER);
				});
			}
		});
	}
}
