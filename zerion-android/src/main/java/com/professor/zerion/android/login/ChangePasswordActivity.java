package com.professor.zerion.android.login;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputLayout;

import org.zerionproject.core.api.crypto.DecryptionResult;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import javax.inject.Inject;

import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.ViewModelProvider;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_STRENGTHENER_ERROR;
import static org.zerionproject.core.api.crypto.DecryptionResult.SUCCESS;
import static org.zerionproject.core.api.crypto.PasswordStrengthEstimator.QUITE_WEAK;
import static org.zerionproject.core.api.crypto.PasswordStrengthEstimator.STRONG;
import static com.professor.zerion.android.login.LoginUtils.createKeyStrengthenerErrorDialog;
import static com.professor.zerion.android.util.UiUtils.hideSoftKeyboard;
import static com.professor.zerion.android.util.UiUtils.setError;
import static com.professor.zerion.android.util.UiUtils.showSoftKeyboard;

public class ChangePasswordActivity extends ZerionActivity
		implements OnClickListener, OnEditorActionListener {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private TextInputLayout currentPasswordEntryWrapper;
	private TextInputLayout newPasswordEntryWrapper;
	private TextInputLayout newPasswordConfirmationWrapper;
	private EditText currentPassword;
	private EditText newPassword;
	private EditText newPasswordConfirmation;
	private StrengthMeter strengthMeter;
	private Button changePasswordButton;
	private ProgressBar progress;

	@VisibleForTesting
	ChangePasswordViewModel viewModel;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(ChangePasswordViewModel.class);
	}

	@Override
	protected boolean forceScreenshotProtection() {
		return true;
	}

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_change_password);

		androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		androidx.appcompat.app.ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
			actionBar.setHomeButtonEnabled(true);
			actionBar.setTitle(R.string.change_password);
		}

		currentPasswordEntryWrapper =
				findViewById(R.id.current_password_entry_wrapper);
		newPasswordEntryWrapper = findViewById(R.id.new_password_entry_wrapper);
		newPasswordConfirmationWrapper =
				findViewById(R.id.new_password_confirm_wrapper);
		currentPassword = findViewById(R.id.current_password_entry);
		newPassword = findViewById(R.id.new_password_entry);
		newPasswordConfirmation = findViewById(R.id.new_password_confirm);
		strengthMeter = findViewById(R.id.strength_meter);
		changePasswordButton = findViewById(R.id.change_password);
		progress = findViewById(R.id.progress_wheel);

		TextWatcher tw = new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count,
					int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before,
					int count) {
				enableOrDisableContinueButton();
			}

			@Override
			public void afterTextChanged(Editable s) {
			}
		};

		currentPassword.addTextChangedListener(tw);
		newPassword.addTextChangedListener(tw);
		newPasswordConfirmation.addTextChangedListener(tw);
		newPasswordConfirmation.setOnEditorActionListener(this);
		changePasswordButton.setOnClickListener(this);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void enableOrDisableContinueButton() {
		if (progress == null) return;
		if (newPassword.getText().length() > 0 && newPassword.hasFocus())
			strengthMeter.setVisibility(VISIBLE);
		else strengthMeter.setVisibility(INVISIBLE);

		int firstLen = newPassword.length();
		int secondLen = newPasswordConfirmation.length();
		char[] firstChars = new char[firstLen];
		char[] secondChars = new char[secondLen];
		if (firstLen > 0) newPassword.getText().getChars(0, firstLen, firstChars, 0);
		if (secondLen > 0) newPasswordConfirmation.getText().getChars(0, secondLen, secondChars, 0);

		boolean passwordsMatch = java.util.Arrays.equals(firstChars, secondChars);
		float strength = viewModel.estimatePasswordStrength(firstChars);
		strengthMeter.setStrength(strength);

		if (firstLen > 0) {
			if (strength >= STRONG) {
				newPasswordEntryWrapper.setHelperText(
						getString(R.string.password_strong));
			} else if (strength >= QUITE_WEAK) {
				newPasswordEntryWrapper.setHelperText(
						getString(R.string.password_quite_strong));
			} else {
				newPasswordEntryWrapper.setHelperTextEnabled(false);
			}
		}

		setError(newPasswordEntryWrapper,
				getString(R.string.password_too_weak),
				firstLen > 0 && strength < QUITE_WEAK);
		setError(newPasswordConfirmationWrapper,
				getString(R.string.passwords_do_not_match),
				secondLen > 0 && !passwordsMatch);
		changePasswordButton.setEnabled(
				currentPassword.length() > 0 &&
						passwordsMatch && strength >= QUITE_WEAK);

		java.util.Arrays.fill(firstChars, '\0');
		java.util.Arrays.fill(secondChars, '\0');
	}

	@Override
	public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
		hideSoftKeyboard(v);
		return true;
	}

	@Override
	public void onClick(View view) {
		changePasswordButton.setVisibility(INVISIBLE);
		progress.setVisibility(VISIBLE);

		char[] curTyped = new char[currentPassword.length()];
		currentPassword.getText().getChars(0, curTyped.length, curTyped, 0);
		char[] newTyped = new char[newPassword.length()];
		newPassword.getText().getChars(0, newTyped.length, newTyped, 0);
		currentPassword.setText("");
		newPassword.setText("");
		char[] curPwd = com.professor.zerion.android.account
				.PasswordSanitizer.sanitize(curTyped);
		char[] newPwd = com.professor.zerion.android.account
				.PasswordSanitizer.sanitize(newTyped);
		java.util.Arrays.fill(curTyped, '\0');
		java.util.Arrays.fill(newTyped, '\0');
		viewModel.changePassword(curPwd, newPwd).observeEvent(this, result -> {
					if (result == SUCCESS) {
						Toast.makeText(ChangePasswordActivity.this,
								R.string.password_changed,
								LENGTH_LONG).show();
						setResult(RESULT_OK);
						supportFinishAfterTransition();
					} else {
						tryAgain(result);
					}
				}
		);
	}

	private void tryAgain(DecryptionResult result) {
		changePasswordButton.setVisibility(VISIBLE);
		progress.setVisibility(INVISIBLE);
		if (result == KEY_STRENGTHENER_ERROR) {
			createKeyStrengthenerErrorDialog(this).show();
		} else {
			setError(currentPasswordEntryWrapper,
					getString(R.string.try_again), true);
			currentPassword.setText("");
			showSoftKeyboard(currentPassword);
		}
	}
}
