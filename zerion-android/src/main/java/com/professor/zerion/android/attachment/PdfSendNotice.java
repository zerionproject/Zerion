package com.professor.zerion.android.attachment;

import android.app.Activity;
import android.content.ContentResolver;
import android.net.Uri;

import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@NotNullByDefault
public final class PdfSendNotice {

	private static final Executor TYPES = Executors.newSingleThreadExecutor(
			r -> {
				Thread t = new Thread(r, "pdf-send-notice");
				t.setDaemon(true);
				return t;
			});

	private PdfSendNotice() {
	}

	public static void confirm(Activity activity, List<Uri> uris,
			Runnable send) {
		ContentResolver resolver = activity.getContentResolver();
		List<Uri> picked = new ArrayList<>(uris);
		TYPES.execute(() -> {
			boolean pdf = containsPdf(resolver, picked);
			activity.runOnUiThread(() -> {
				if (activity.isFinishing() || activity.isDestroyed()) return;
				if (!pdf) {
					send.run();
					return;
				}
				new SecureAlertDialogBuilder(activity,
						R.style.ZerionDialogTheme)
						.setTitle(R.string.dialog_title_send_pdf)
						.setMessage(R.string.dialog_message_send_pdf)
						.setPositiveButton(R.string.send, (d, w) -> send.run())
						.setNegativeButton(R.string.cancel, null)
						.show();
			});
		});
	}

	static boolean containsPdf(ContentResolver resolver, List<Uri> uris) {
		for (Uri uri : uris) {
			String type;
			try {
				type = resolver.getType(uri);
			} catch (RuntimeException e) {
				type = null;
			}
			if (AttachmentDocuments.PDF.equals(
					SharedMediaSanitizer.base(type))) {
				return true;
			}
		}
		return false;
	}
}
