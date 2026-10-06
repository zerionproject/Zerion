package com.professor.zerion.android.conversation;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import com.professor.zerion.R;
import com.professor.zerion.android.attachment.AttachmentRetriever;
import com.professor.zerion.android.util.ExternalHandoff;
import com.professor.zerion.android.util.ExternalViewerTypes;
import com.professor.zerion.android.vault.utils.SecureMemory;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.attachment.Attachment;
import org.zerionproject.app.api.attachment.AttachmentHeader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executor;

@NotNullByDefault
final class DocumentOpener {

	static final String STAGING_DIR = "media_docs";

	private static final int MAX_OPEN_BYTES = 64 * 1024 * 1024;

	private DocumentOpener() {
	}

	static void open(Activity a, Executor dbExecutor,
			AttachmentRetriever retriever, AttachmentHeader header) {
		dbExecutor.execute(() -> {
			try {
				Attachment att = retriever.getMessageAttachment(header);
				byte[] content;
				try (InputStream is = att.getStream()) {
					content = readBounded(is);
				}
				String viewType = ExternalViewerTypes.typeForContent(
						header.getContentType(), content);
				if (viewType == null) {
					a.runOnUiThread(() -> toast(a,
							R.string.media_document_open_failed));
					return;
				}
				a.runOnUiThread(() -> {
					if (a.isFinishing() || a.isDestroyed()) return;
					ExternalHandoff.open(a, dbExecutor, content, viewType,
							() -> toast(a, R.string.media_document_no_app));
				});
			} catch (Exception e) {
				a.runOnUiThread(() -> toast(a,
						R.string.media_document_open_failed));
			}
		});
	}

	static void wipe(Context c) {
		SecureMemory.secureDeleteDir(new File(c.getCacheDir(), STAGING_DIR),
				0L);
	}

	private static byte[] readBounded(InputStream is) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = is.read(buf)) != -1) {
			out.write(buf, 0, n);
			if (out.size() > MAX_OPEN_BYTES) {
				throw new IOException("file too large to open");
			}
		}
		return out.toByteArray();
	}

	private static void toast(Context c, int res) {
		Toast.makeText(c, res, Toast.LENGTH_SHORT).show();
	}
}
