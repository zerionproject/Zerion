package com.professor.zerion.android.security;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.professor.zerion.R;

import javax.annotation.Nullable;

/**
 * Shown instead of the normal UI when the Android Keystore is in a failed
 * state that survives app restarts but not a device reboot. The app fails
 * closed: no plaintext fallback for secure storage exists, so nothing can
 * safely run until the keystore recovers. This screen replaces what used to
 * be a crash loop with an explanation and a way out. It deliberately extends
 * the plain framework Activity and injects nothing, so it cannot itself fail
 * for the same reason it is reporting.
 */
public class KeystoreUnavailableActivity extends Activity {

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setGravity(Gravity.CENTER);
		int pad = (int) (32 * getResources().getDisplayMetrics().density);
		root.setPadding(pad, pad, pad, pad);
		root.setBackgroundColor(0xFF14151A);

		TextView title = new TextView(this);
		title.setText(R.string.keystore_unavailable_title);
		title.setTextColor(0xFFFFFFFF);
		title.setTextSize(20);
		title.setGravity(Gravity.CENTER);
		root.addView(title);

		TextView message = new TextView(this);
		message.setText(R.string.keystore_unavailable_message);
		message.setTextColor(0xFF9CA3AF);
		message.setTextSize(15);
		message.setGravity(Gravity.CENTER);
		LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		mp.topMargin = pad / 2;
		root.addView(message, mp);

		Button close = new Button(this);
		close.setText(R.string.keystore_unavailable_close);
		close.setOnClickListener(v -> finishAffinity());
		LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		bp.topMargin = pad;
		bp.gravity = Gravity.CENTER_HORIZONTAL;
		root.addView(close, bp);

		setContentView(root);
	}

	@Override
	public void onBackPressed() {
		finishAffinity();
	}
}
