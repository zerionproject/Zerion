package com.professor.zerion.android.sticker;

import android.os.Handler;
import android.os.Looper;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.Executor;

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
