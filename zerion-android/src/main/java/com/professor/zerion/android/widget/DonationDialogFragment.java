package com.professor.zerion.android.widget;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;

import com.professor.zerion.R;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

/**
 * A modern donation dialog that appears randomly once a month.
 * Shows options to donate or dismiss for later.
 */
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

		View v = inflater.inflate(R.layout.fragment_donation_dialog, container, false);

		Button donateButton = v.findViewById(R.id.buttonDonate);
		donateButton.setOnClickListener(v1 -> {
			openDonationPage();
			dismiss();
		});

		Button laterButton = v.findViewById(R.id.buttonLater);
		laterButton.setOnClickListener(v1 -> dismiss());

		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		Dialog dialog = getDialog();
		if (dialog != null) {
			Window window = dialog.getWindow();
			if (window != null) {
				// Set dialog width to 90% of screen width
				int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9);
				window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
				window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
			}
		}
	}

	private void openDonationPage() {
		Context ctx = requireContext();
		Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(DONATION_URL));
		try {
			startActivity(intent);
		} catch (Exception e) {
			// Browser not available, silently fail
		}
	}

	public String getUniqueTag() {
		return TAG;
	}
}
