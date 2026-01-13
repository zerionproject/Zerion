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
		setViewDimensions(attachment, single, needsStretch);

		boolean isVideo = attachment.isVideo();
		if (playOverlay != null) {
			playOverlay.setVisibility(isVideo ? View.VISIBLE : View.GONE);
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
			boolean needsStretch) {
		View container = itemView;
		if (container instanceof ViewGroup) {
			LayoutParams params = (LayoutParams) container.getLayoutParams();
			int width = needsStretch ? imageSize * 2 : imageSize;
			params.width = single ? a.getThumbnailWidth() : width;
			params.height = single ? a.getThumbnailHeight() : imageSize;
			params.setFullSpan(!single && needsStretch);
			container.setLayoutParams(params);
		} else {
			LayoutParams params = (LayoutParams) imageView.getLayoutParams();
			int width = needsStretch ? imageSize * 2 : imageSize;
			params.width = single ? a.getThumbnailWidth() : width;
			params.height = single ? a.getThumbnailHeight() : imageSize;
			params.setFullSpan(!single && needsStretch);
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

	private void loadVideoThumbnail(AttachmentItem a, Radii r) {
		if (attachmentReader == null || dbExecutor == null) {
			imageView.setImageResource(R.drawable.ic_video);
			imageView.setScaleType(FIT_CENTER);
			return;
		}

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
			} catch (Exception e) {
				imageView.post(() -> {
					imageView.setImageResource(R.drawable.ic_video);
					imageView.setScaleType(FIT_CENTER);
				});
			}
		});
	}
}
