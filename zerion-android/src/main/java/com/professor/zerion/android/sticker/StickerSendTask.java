package com.professor.zerion.android.sticker;

import android.os.Handler;
import android.os.Looper;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.Executor;

/**
 * One-shot task that takes pre-encoded sticker PNG bytes and ships them
 * via the existing inline-attachment write path with the sticker
 * contentType ("image/png; profile=sticker"). The contentType parameter
 * is what tells iOS / a future Android version that this attachment is
 * a sticker; older clients see it as a regular small photo.
 */
@NotNullByDefault
public final class StickerSendTask {

	public interface Callback {
		void onStickerHeaderReady(AttachmentHeader header);
		void onStickerSendFailed(Exception e);
	}

	private final Executor ioExecutor;
	private final MessagingManager messagingManager;
	private final Handler main = new Handler(Looper.getMainLooper());

	public StickerSendTask(@IoExecutor Executor ioExecutor,
			MessagingManager messagingManager) {
		this.ioExecutor = ioExecutor;
		this.messagingManager = messagingManager;
	}

	public void send(GroupId groupId, byte[] pngBytes, Callback cb) {
		ioExecutor.execute(() -> {
			try {
				long ts = System.currentTimeMillis();
				AttachmentHeader header = messagingManager.addLocalAttachment(
						groupId, ts, StickerUtils.STICKER_PNG_MIME,
						new ByteArrayInputStream(pngBytes));
				main.post(() -> cb.onStickerHeaderReady(header));
			} catch (DbException | IOException e) {
				main.post(() -> cb.onStickerSendFailed(e));
			}
		});
	}
}
