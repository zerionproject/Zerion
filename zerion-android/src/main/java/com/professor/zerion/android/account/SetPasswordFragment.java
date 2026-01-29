package com.professor.zerion.android.account;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ProgressBar;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.professor.zerion.R;
import com.professor.zerion.android.login.StrengthMeter;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import static android.content.Context.INPUT_METHOD_SERVICE;
import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static org.briarproject.bramble.api.crypto.PasswordStrengthEstimator.QUITE_WEAK;
import static org.briarproject.bramble.api.crypto.PasswordStrengthEstimator.STRONG;
import static com.professor.zerion.android.util.UiUtils.hideViewOnSmallScreen;
import static com.professor.zerion.android.util.UiUtils.setError;
import static com.professor.zerion.android.util.UiUtils.showOnboardingDialog;

import java.text.Normalizer;
import java.util.Arrays;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SetPasswordFragment extends SetupFragment {
	private final static String TAG = SetPasswordFragment.class.getName();

	private static final int BRUTE_FORCE_DELAY_MS = 800;

	private TextInputLayout passwordEntryWrapper;
	private TextInputLayout passwordConfirmationWrapper;
	private TextInputEditText passwordEntry;
	private TextInputEditText passwordConfirmation;
	private StrengthMeter strengthMeter;
	private Button nextButton;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	public static SetPasswordFragment newInstance() {
		return new SetPasswordFragment();
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_setup_password, container,
				false);

		strengthMeter = v.findViewById(R.id.strength_meter);
		passwordEntryWrapper = v.findViewById(R.id.password_entry_wrapper);
		passwordEntry = v.findViewById(R.id.password_entry);
		passwordConfirmationWrapper =
				v.findViewById(R.id.password_confirm_wrapper);
		passwordConfirmation = v.findViewById(R.id.password_confirm);
		Button infoButton = v.findViewById(R.id.info_button);
		nextButton = v.findViewById(R.id.next);
		ProgressBar progressBar = v.findViewById(R.id.progress);

		passwordEntry.addTextChangedListener(this);
		passwordConfirmation.addTextChangedListener(this);
		infoButton.setOnClickListener(view ->
				showOnboardingDialog(requireContext(), getHelpText()));
		nextButton.setOnClickListener(this);

		if (!viewModel.needToShowDozeFragment()) {
			nextButton.setText(R.string.create_account_button);
		}

		viewModel.getIsCreatingAccount()
				.observe(getViewLifecycleOwner(), isCreatingAccount -> {
					if (isCreatingAccount) {
						nextButton.setVisibility(INVISIBLE);
						progressBar.setVisibility(VISIBLE);
						passwordEntry.setFocusable(false);
						passwordConfirmation.setFocusable(false);
					}
				});
		setupKeyboardInsetsHandling(v);

		return v;
	}

	
	private void setupKeyboardInsetsHandling(View rootView) {
		View scrollContent = rootView.findViewById(R.id.scroll_content);
		if (scrollContent == null) return;

		ViewCompat.setOnApplyWindowInsetsListener(scrollContent, (v, windowInsets) -> {
			Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
			Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
			int bottomPadding = Math.max(ime.bottom, systemBars.bottom);
			v.setPadding(
					v.getPaddingLeft(),
					systemBars.top,
					v.getPaddingRight(),
					bottomPadding
			);

			return windowInsets;
		});
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

		hideViewOnSmallScreen(requireView().findViewById(R.id.logo));

		if (Settings.canDrawOverlays(requireContext())) {
			strengthMeter.setVisibility(GONE);
		}
	}

	@Override
	public void onStop() {
		super.onStop();
		clearPasswordFields();
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	protected String getHelpText() {
		return getString(R.string.setup_password_explanation);
	}

	@Override
	public void onTextChanged(CharSequence authorName, int i, int i1, int i2) {
		char[] password1Chars = getPasswordChars(passwordEntry);
		char[] password2Chars = getPasswordChars(passwordConfirmation);

		try {
			String password1 = new String(password1Chars);
			String password2 = new String(password2Chars);

			String sanitized1 = sanitizePassword(password1);
			String sanitized2 = sanitizePassword(password2);

			boolean passwordsMatch = sanitized1.equals(sanitized2);

			if (!Settings.canDrawOverlays(requireContext())) {
				strengthMeter.setVisibility(!sanitized1.isEmpty() ? VISIBLE : INVISIBLE);
			}

			float strength = viewModel.estimatePasswordStrength(sanitized1);
			strengthMeter.setStrength(strength);
			boolean strongEnough = strength >= QUITE_WEAK;

			if (!sanitized1.isEmpty()) {
				if (strength >= STRONG) {
					passwordEntryWrapper.setHelperText(
							getString(R.string.password_strong));
				} else if (strength >= QUITE_WEAK) {
					passwordEntryWrapper.setHelperText(
							getString(R.string.password_quite_strong));
				} else {
					passwordEntryWrapper.setHelperTextEnabled(false);
				}
			}
			setError(passwordEntryWrapper, getString(R.string.password_too_weak),
					!sanitized1.isEmpty() && !strongEnough);
			setError(passwordConfirmationWrapper,
					getString(R.string.passwords_do_not_match),
					!sanitized2.isEmpty() && !passwordsMatch);

			boolean enabled = passwordsMatch && strongEnough;
			nextButton.setEnabled(enabled);
			passwordConfirmation.setOnEditorActionListener(enabled ? this : null);
		} finally {
			Arrays.fill(password1Chars, '\0');
			Arrays.fill(password2Chars, '\0');
		}
	}

	@Override
	public void onClick(View view) {
		IBinder token = passwordEntry.getWindowToken();
		Object o = requireContext().getSystemService(INPUT_METHOD_SERVICE);
		((InputMethodManager) o).hideSoftInputFromWindow(token, 0);

		nextButton.setEnabled(false);
		mainHandler.postDelayed(() -> {
			if (isAdded()) nextButton.setEnabled(true);
		}, BRUTE_FORCE_DELAY_MS);

		setPassword();
	}

	private void setPassword() {
		char[] passwordChars = getPasswordChars(passwordEntry);

		try {
			String password = new String(passwordChars);
			String sanitized = sanitizePassword(password);
			viewModel.setPassword(sanitized);
		} finally {
			Arrays.fill(passwordChars, '\0');
		}
	}

	private char[] getPasswordChars(TextInputEditText editText) {
		int length = editText.length();
		char[] chars = new char[length];
		if (length > 0 && editText.getText() != null) {
			editText.getText().getChars(0, length, chars, 0);
		}
		return chars;
	}

	private String sanitizePassword(String password) {
		if (password.isEmpty()) return "";

		String normalized = Normalizer.normalize(password, Normalizer.Form.NFC);

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < normalized.length(); i++) {
			char c = normalized.charAt(i);
			int type = Character.getType(c);

			if (type == Character.CONTROL ||
				type == Character.FORMAT ||
				type == Character.PRIVATE_USE ||
				type == Character.SURROGATE ||
				type == Character.UNASSIGNED ||
				c == '\u200B' ||
				c == '\u200C' ||
				c == '\u200D' ||
				c == '\u200E' ||
				c == '\u200F' ||
				c == '\u202A' ||
				c == '\u202B' ||
				c == '\u202C' ||
				c == '\u202D' ||
				c == '\u202E' ||
				c == '\u2066' ||
				c == '\u2067' ||
				c == '\u2068' ||
				c == '\u2069' ||
				c == '\uFEFF') {
				continue;
			}
			sb.append(c);
		}

		return sb.toString();
	}

	private void clearPasswordFields() {
		if (passwordEntry != null && passwordEntry.getText() != null) {
			passwordEntry.getText().clear();
		}
		if (passwordConfirmation != null && passwordConfirmation.getText() != null) {
			passwordConfirmation.getText().clear();
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		mainHandler.removeCallbacksAndMessages(null);
		clearPasswordFields();
	}

}
