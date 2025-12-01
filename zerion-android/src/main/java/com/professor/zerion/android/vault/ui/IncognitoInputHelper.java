package com.professor.zerion.android.vault.ui;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class IncognitoInputHelper {

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

		editText.setPrivateImeOptions(
			"nm=1," +
			"com.google.android.inputmethod.latin.noMicrophoneKey," +
			"noLearning=1," +
			"disableStickers=true," +
			"disableGifKeyboard=true," +
			"disableEmoji=true," +
			"noSuggestions=true"
		);

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
}