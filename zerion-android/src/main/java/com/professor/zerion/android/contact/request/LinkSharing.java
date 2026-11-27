package com.professor.zerion.android.contact.request;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.graphics.Bitmap;
import android.widget.ImageView;
import android.widget.Toast;

import com.professor.zerion.R;
import com.professor.zerion.android.contact.add.remote.QrCodeUtils;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.professor.zerion.android.util.SecureClipboard;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.core.app.ShareCompat.IntentBuilder;

import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;

@NotNullByDefault
public final class LinkSharing {

	private LinkSharing() {
	}

	public static void share(Activity activity, String link) {
		try {
			new IntentBuilder(activity)
					.setText(link)
					.setType("text/plain")
					.startChooser();
		} catch (ActivityNotFoundException e) {
			Toast.makeText(activity, R.string.error_start_activity,
					LENGTH_LONG).show();
		}
	}

	public static void copy(Activity activity, String link) {
		SecureClipboard.copy(activity,
				activity.getString(R.string.link_clip_label), link);
		Toast.makeText(activity, R.string.link_copied_toast, LENGTH_SHORT)
				.show();
	}

	public static void showQr(Activity activity, String link) {
		Bitmap qr = QrCodeUtils.generateQrCode(link);
		ImageView image = new ImageView(activity);
		image.setId(R.id.linkQrImage);
		image.setAdjustViewBounds(true);
		image.setContentDescription(activity.getString(R.string.qr_dialog_title));
		int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
		image.setPadding(pad, pad, pad, pad);
		if (qr != null) image.setImageBitmap(qr);
		new SecureAlertDialogBuilder(activity, R.style.ZerionDialogTheme)
				.setTitle(R.string.qr_dialog_title)
				.setView(image)
				.setPositiveButton(R.string.ok, null)
				.show();
	}
}
