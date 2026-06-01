package com.professor.zerion.android.widget;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.professor.zerion.R;
import com.professor.zerion.android.contact.add.remote.QrCodeUtils;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class DonationDialogFragment extends DialogFragment {

	public static final String TAG = DonationDialogFragment.class.getName();

	private static final String DONATION_URL = "https://zerion.chat/donate.html";

	public static DonationDialogFragment newInstance() {
		return new DonationDialogFragment();
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setStyle(STYLE_NO_TITLE, R.style.ZerionDialogTheme);
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		Dialog dialog = super.onCreateDialog(savedInstanceState);
		Window window = dialog.getWindow();
		if (window != null) {
			window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
			window.requestFeature(Window.FEATURE_NO_TITLE);
		}
		return dialog;
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {

		View v = inflater.inflate(R.layout.fragment_donation_dialog, container,
				false);

		ImageView qr = v.findViewById(R.id.donationQr);
		Bitmap bmp = QrCodeUtils.generateQrCode(DONATION_URL, 512);
		if (bmp != null) qr.setImageBitmap(bmp);

		TextView urlView = v.findViewById(R.id.donationUrl);
		urlView.setText(DONATION_URL);

		MaterialButton copyButton = v.findViewById(R.id.buttonCopyLink);
		copyButton.setOnClickListener(view -> copyDonationUrl());

		MaterialButton openButton = v.findViewById(R.id.buttonOpenInBrowser);
		openButton.setOnClickListener(view -> showBrowserWarning());

		MaterialButton laterButton = v.findViewById(R.id.buttonLater);
		laterButton.setOnClickListener(view -> dismiss());

		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		Dialog dialog = getDialog();
		if (dialog != null) {
			Window window = dialog.getWindow();
			if (window != null) {
				int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
				window.setLayout(width,
						WindowManager.LayoutParams.WRAP_CONTENT);
				window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
			}
		}
	}

	private void copyDonationUrl() {
		Context ctx = requireContext();
		ClipboardManager cm = (ClipboardManager)
				ctx.getSystemService(Context.CLIPBOARD_SERVICE);
		if (cm == null) return;
		ClipData clip = ClipData.newPlainText(
				getString(R.string.donation_url_label), DONATION_URL);
		cm.setPrimaryClip(clip);
		Toast.makeText(ctx, R.string.donation_link_copied,
				Toast.LENGTH_SHORT).show();
	}

	private void showBrowserWarning() {
		android.content.Context ctx = getContext();
		if (ctx != null) {
			com.professor.zerion.android.util.BrowserGuard.openUrl(ctx,
					DONATION_URL);
		}
		dismiss();
	}

	public String getUniqueTag() {
		return TAG;
	}
}
