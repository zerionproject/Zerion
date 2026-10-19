package com.professor.zerion.android.conversation;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.util.StringUtils;
import com.professor.zerion.R;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.attachment.AttachmentKinds;
import com.professor.zerion.android.conversation.glide.ZerionImageTransformation;
import com.bumptech.glide.Glide;
import com.professor.zerion.android.conversation.glide.Radii;
import com.professor.zerion.android.util.UiUtils;
import org.zerionproject.app.api.attachment.Attachment;
import org.zerionproject.app.api.attachment.AttachmentNotYetAvailableException;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;
import androidx.recyclerview.widget.StaggeredGridLayoutManager.LayoutParams;

import static android.widget.ImageView.ScaleType.CENTER_CROP;
import static android.widget.ImageView.ScaleType.FIT_CENTER;
import static com.bumptech.glide.load.engine.DiskCacheStrategy.NONE;
import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;
import static com.professor.zerion.android.attachment.AttachmentItem.State.AVAILABLE;
import static com.professor.zerion.android.attachment.AttachmentItem.State.ERROR;

@NotNullByDefault
public class ImageViewHolder extends ViewHolder {

	@DrawableRes
	private static final int ERROR_RES = R.drawable.ic_image_broken;

	private static final int MAX_THUMBNAIL_RETRY_ATTEMPTS = 5;
	private static final long THUMBNAIL_RETRY_DELAY_MS = 300;

	private static final LruCache<String, Bitmap> VIDEO_THUMB_CACHE =
			new LruCache<String, Bitmap>(12 * 1024 * 1024) {
				@Override
				protected int sizeOf(String key, Bitmap value) {
					return value.getByteCount();
				}
			};

	private static final Executor THUMBNAIL_DECODE_EXECUTOR =
			Executors.newSingleThreadExecutor();

	public static void clearVideoThumbCache() {
		VIDEO_THUMB_CACHE.evictAll();
	}

	public static void evictVideoThumbs(Collection<MessageId> items) {
		if (items.isEmpty()) return;
		Set<String> prefixes = new HashSet<>();
		for (MessageId item : items) prefixes.add(thumbPrefix(item));
		for (String key : VIDEO_THUMB_CACHE.snapshot().keySet()) {
			int split = key.indexOf(':');
			if (split > 0 && prefixes.contains(key.substring(0, split + 1))) {
				VIDEO_THUMB_CACHE.remove(key);
			}
		}
	}

	private static String thumbPrefix(MessageId item) {
		return StringUtils.toHexString(item.getBytes()) + ":";
	}

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
	private boolean incoming = true;
	@Nullable
	private ColorStateList ownIcon = null;
	private Object bound = new Object();

	private final class Broken implements RequestListener<Drawable> {

		private final Object token;

		private Broken(Object token) {
			this.token = token;
		}

		@Override
		public boolean onLoadFailed(@Nullable GlideException e,
				@Nullable Object model, Target<Drawable> target,
				boolean isFirstResource) {
			if (token == bound) iconTint(true);
			return false;
		}

		@Override
		public boolean onResourceReady(Drawable resource, Object model,
				Target<Drawable> target, DataSource dataSource,
				boolean isFirstResource) {
			return false;
		}
	}

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

	void inBubble(boolean incoming) {
		this.incoming = incoming;
	}

	private void pictureTint() {
		tint(AppCompatResources.getColorStateList(imageView.getContext(),
				R.color.selected_media), PorterDuff.Mode.SRC_ATOP);
	}

	private void iconTint(boolean placeholder) {
		ColorStateList list;
		if (incoming) {
			list = AppCompatResources.getColorStateList(imageView.getContext(),
					placeholder ? R.color.selectable_on_bubble_in_muted
							: R.color.selectable_accent_icon);
		} else {
			if (ownIcon == null) {
				ownIcon = ColorStateList.valueOf(UiUtils.resolveColorAttribute(
						imageView.getContext(), R.attr.zerionColorOnBubbleOut));
			}
			list = ownIcon;
		}
		tint(list, PorterDuff.Mode.SRC_IN);
	}

	private void tint(ColorStateList list, PorterDuff.Mode mode) {
		if (ImageViewCompat.getImageTintList(imageView) == list
				&& ImageViewCompat.getImageTintMode(imageView) == mode) {
			return;
		}
		ImageViewCompat.setImageTintList(imageView, list);
		ImageViewCompat.setImageTintMode(imageView, mode);
	}

	void bind(AttachmentItem attachment, Radii r, boolean single,
			boolean needsStretch) {
		bound = new Object();
		boolean isSticker = attachment.isSticker();
		setViewDimensions(attachment, single, needsStretch, isSticker);

		boolean isVideo = attachment.isVideo();
		if (playOverlay != null) {
			playOverlay.setVisibility(
					isVideo && !isSticker ? View.VISIBLE : View.GONE);
		}

		imageView.setContentDescription(AttachmentKinds.describe(
				imageView.getContext(), attachment.getMimeType()));
		if (attachment.isDocument() && attachment.getState() == AVAILABLE) {
			Glide.with(imageView).clear(imageView);
			iconTint(false);
			imageView.setImageResource("pdf".equals(attachment.getExtension())
					? R.drawable.ic_pdf : R.drawable.ic_document);
			imageView.setScaleType(FIT_CENTER);
			if (playOverlay != null) playOverlay.setVisibility(View.GONE);
		} else if (attachment.getState() != AVAILABLE) {
			Glide.with(imageView).clear(imageView);
			iconTint(true);
			if (attachment.getState() == ERROR) {
				imageView.setImageResource(ERROR_RES);
			} else {
				imageView.setImageResource(R.drawable.ic_image_missing);
			}
			imageView.setScaleType(FIT_CENTER);
			if (playOverlay != null) playOverlay.setVisibility(View.GONE);
		} else if (isSticker) {
			pictureTint();
			loadStickerImage(attachment);
			imageView.setScaleType(FIT_CENTER);
		} else if (isVideo) {
			pictureTint();
			loadVideoThumbnail(attachment, r);
			imageView.setScaleType(CENTER_CROP);
		} else {
			pictureTint();
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
				.listener(new Broken(bound))
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
				.listener(new Broken(bound))
				.transition(withCrossFade())
				.into(imageView)
				.waitForLayout();
	}

	private void loadVideoThumbnail(AttachmentItem a, Radii r) {
		if (attachmentReader == null || dbExecutor == null) {
			iconTint(false);
			imageView.setImageResource(R.drawable.ic_video);
			imageView.setScaleType(FIT_CENTER);
			return;
		}

		loadVideoThumbnailWithRetry(a, r, 0, bound);
	}

	private void renderVideoThumbnail(Bitmap thumbnail, Radii r,
			Object token) {
		imageView.post(() -> {
			if (token != bound) return;
			Transformation<Bitmap> transformation =
					new ZerionImageTransformation(r);
			Glide.with(imageView)
					.load(thumbnail)
					.diskCacheStrategy(NONE)
					.error(ERROR_RES)
					.listener(new Broken(token))
					.transform(transformation)
					.transition(withCrossFade())
					.into(imageView);
		});
	}

	private void loadVideoThumbnailWithRetry(AttachmentItem a, Radii r,
			int attemptNumber, Object token) {
		if (token != bound) return;
		if (dbExecutor == null || attachmentReader == null) return;

		final String thumbKey = thumbPrefix(conversationItemId)
				+ StringUtils.toHexString(a.getHeader().getMessageId()
						.getBytes());
		Bitmap cached = VIDEO_THUMB_CACHE.get(thumbKey);
		if (cached != null) {
			renderVideoThumbnail(cached, r, token);
			return;
		}

		dbExecutor.execute(() -> {
			File tempFile = null;
			try {
				Attachment attachment = attachmentReader.getAttachment(a.getHeader());
				InputStream is = attachment.getStream();

				tempFile = File.createTempFile("video_thumb_", ".tmp",
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

				File decodeFile = tempFile;
				tempFile = null;
				THUMBNAIL_DECODE_EXECUTOR.execute(
						() -> decodeThumbnail(decodeFile, thumbKey, r, token));
			} catch (AttachmentNotYetAvailableException e) {
				if (attemptNumber < MAX_THUMBNAIL_RETRY_ATTEMPTS) {
					imageView.postDelayed(() -> loadVideoThumbnailWithRetry(
							a, r, attemptNumber + 1, token),
							THUMBNAIL_RETRY_DELAY_MS);
				} else {
					showVideoFallback(token);
				}
			} catch (Exception e) {
				showVideoFallback(token);
			} finally {
				if (tempFile != null) {
					com.professor.zerion.android.vault.utils.SecureMemory
							.secureDeleteFile(tempFile, 0L, false);
				}
			}
		});
	}

	private void decodeThumbnail(File file, String thumbKey, Radii r,
			Object token) {
		MediaMetadataRetriever retriever = null;
		try {
			retriever = new MediaMetadataRetriever();
			retriever.setDataSource(file.getAbsolutePath());
			Bitmap thumbnail = retriever.getFrameAtTime(0,
					MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
			if (thumbnail == null) {
				thumbnail = retriever.getFrameAtTime();
			}
			if (thumbnail != null) {
				VIDEO_THUMB_CACHE.put(thumbKey, thumbnail);
				renderVideoThumbnail(thumbnail, r, token);
			} else {
				showVideoFallback(token);
			}
		} catch (Exception e) {
			showVideoFallback(token);
		} finally {
			if (retriever != null) {
				try {
					retriever.release();
				} catch (Exception ignored) {
				}
			}
			com.professor.zerion.android.vault.utils.SecureMemory
					.secureDeleteFile(file, 0L, false);
		}
	}

	private void showVideoFallback(Object token) {
		imageView.post(() -> {
			if (token != bound) return;
			iconTint(false);
			imageView.setImageResource(R.drawable.ic_video);
			imageView.setScaleType(FIT_CENTER);
		});
	}
}
