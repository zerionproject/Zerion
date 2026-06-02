package com.professor.zerion.android.security;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class HardenedBlockActivity extends ZerionActivity {

	public static final String EXTRA_RESULT_CODE =
			"com.professor.zerion.android.security.RESULT_CODE";

	@Inject
	@AppModule.UiPrefs
	SharedPreferences uiPrefs;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
				WindowManager.LayoutParams.FLAG_SECURE);
		setContentView(R.layout.activity_hardened_block);

		int result = getIntent().getIntExtra(EXTRA_RESULT_CODE,
				SecureBootGuard.RESULT_VERIFIED_BOOT_NOT_GREEN);
		TextView title = findViewById(R.id.hardenedBlockTitle);
		TextView detail = findViewById(R.id.hardenedBlockDetail);
		MaterialButton disableButton =
				findViewById(R.id.hardenedBlockDisableButton);
		MaterialButton quitButton =
				findViewById(R.id.hardenedBlockQuitButton);

		title.setText(R.string.hardened_block_title);
		detail.setText(SecureBootGuard.describe(result, this));
		detail.setMovementMethod(LinkMovementMethod.getInstance());

		disableButton.setOnClickListener(v ->
				promptDisableHardenedMode(result));
		quitButton.setOnClickListener(v -> finishAndRemoveTask());
	}

	private void promptDisableHardenedMode(int result) {
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.hardened_block_disable_title)
				.setMessage(R.string.hardened_block_disable_message)
				.setPositiveButton(
						R.string.hardened_block_disable_confirm,
						(d, w) -> {
							uiPrefs.edit()
									.putBoolean(HardenedModeEvaluator
											.PREF_HARDENED_BOOT, false)
									.putBoolean(HardenedModeEvaluator
											.PREF_HARDENED_TAMPER, false)
									.apply();
							finishAndRemoveTask();
						})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	@Override
	public void onBackPressed() {
		finishAndRemoveTask();
	}
}
