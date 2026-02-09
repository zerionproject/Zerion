package com.professor.zerion.android.vault.ui;

import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class IncognitoInputHelper {

	private static final String PRIVATE_IME_OPTIONS =
			"nm=1," +
			"com.google.android.inputmethod.latin.noMicrophoneKey," +
			"noLearning=1," +
			"disableStickers=true," +
			"disableGifKeyboard=true," +
			"disableEmoji=true," +
			"noSuggestions=true";

	public static void configureIncognitoInput(EditText editText, boolean isPassword) {
		int inputType;

		if (isPassword) {
			inputType = InputType.TYPE_CLASS_TEXT |
					InputType.TYPE_TEXT_VARIATION_PASSWORD;
		} else {
			inputType = InputType.TYPE_CLASS_TEXT |
					InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS |
					InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
		}

		if (editText.getMaxLines() > 1) {
			inputType |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
		}

		editText.setInputType(inputType);

		editText.setPrivateImeOptions(PRIVATE_IME_OPTIONS);

		int imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING |
				EditorInfo.IME_FLAG_NO_EXTRACT_UI;

		int existingAction = editText.getImeOptions() & EditorInfo.IME_MASK_ACTION;
		if (existingAction != 0) {
			imeOptions |= existingAction;
		} else {
			imeOptions |= EditorInfo.IME_ACTION_DONE;
		}

		editText.setImeOptions(imeOptions);

		if (isPassword) {
			editText.setLongClickable(false);
			editText.setTextIsSelectable(false);
		}

		editText.setInputType(editText.getInputType() |
				InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
	}

	public static void configureForVault(EditText editText) {
		configureIncognitoInput(editText, false);
	}

	public static void configurePasswordField(EditText editText) {
		configureIncognitoInput(editText, true);
	}

	/**
	 * Recursively walks the view tree and enforces secure keyboard flags
	 * on every EditText found. This is the global enforcement mechanism
	 * called from BaseActivity to ensure no input field leaks data to IMEs.
	 *
	 * This method applies a lighter touch than configureIncognitoInput()
	 * to avoid breaking field-specific inputType configurations:
	 * - Adds IME_FLAG_NO_PERSONALIZED_LEARNING to existing imeOptions
	 * - Sets privateImeOptions to disable IME learning/telemetry
	 * - Adds TYPE_TEXT_FLAG_NO_SUGGESTIONS to existing inputType
	 * - Disables autofill (API 26+)
	 */
	public static void enforceSecureInputsOnViewTree(View root) {
		if (root instanceof EditText) {
			enforceSecureInput((EditText) root);
		}
		if (root instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) root;
			for (int i = 0, count = group.getChildCount(); i < count; i++) {
				enforceSecureInputsOnViewTree(group.getChildAt(i));
			}
		}
	}

	private static void enforceSecureInput(EditText editText) {
		// Add NO_PERSONALIZED_LEARNING to existing imeOptions
		int currentIme = editText.getImeOptions();
		if ((currentIme & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0) {
			editText.setImeOptions(currentIme |
					EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
		}

		// Set private IME options to disable learning/telemetry
		String currentPrivate = editText.getPrivateImeOptions();
		if (currentPrivate == null || !currentPrivate.contains("nm=1")) {
			editText.setPrivateImeOptions(PRIVATE_IME_OPTIONS);
		}

		// Add NO_SUGGESTIONS to existing inputType (preserve other flags)
		int currentType = editText.getInputType();
		if ((currentType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
			if ((currentType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0) {
				editText.setInputType(currentType |
						InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
			}
		}

		// Disable autofill (API 26+)
		if (Build.VERSION.SDK_INT >= 26) {
			editText.setImportantForAutofill(
					View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
		}
	}
}