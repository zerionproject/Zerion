package com.professor.zerion.android.vault.ui;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.professor.zerion.R;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class DocumentPasswordDialog extends DialogFragment {

	public interface PasswordCallback {
		void onPasswordEntered(@Nullable char[] password);
		void onPasswordCancelled();
	}

	private static final String ARG_TITLE = "title";
	private static final String ARG_MESSAGE = "message";
	private static final String ARG_ALLOW_EMPTY = "allow_empty";
	private static final String ARG_CONFIRM_MODE = "confirm_mode";

	private EditText passwordInput;
	private EditText confirmPasswordInput;
	private CheckBox showPasswordCheckbox;
	private TextView messageText;
	private PasswordCallback callback;

	public static DocumentPasswordDialog newPasswordDialog(String title, String message) {
		DocumentPasswordDialog dialog = new DocumentPasswordDialog();
		Bundle args = new Bundle();
		args.putString(ARG_TITLE, title);
		args.putString(ARG_MESSAGE, message);
		args.putBoolean(ARG_ALLOW_EMPTY, true);
		args.putBoolean(ARG_CONFIRM_MODE, true);
		dialog.setArguments(args);
		return dialog;
	}

	public static DocumentPasswordDialog newUnlockDialog(String title, String message) {
		DocumentPasswordDialog dialog = new DocumentPasswordDialog();
		Bundle args = new Bundle();
		args.putString(ARG_TITLE, title);
		args.putString(ARG_MESSAGE, message);
		args.putBoolean(ARG_ALLOW_EMPTY, false);
		args.putBoolean(ARG_CONFIRM_MODE, false);
		dialog.setArguments(args);
		return dialog;
	}

	public void setCallback(PasswordCallback callback) {
		this.callback = callback;
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		Context context = requireContext();
		Bundle args = getArguments();
		if (args == null) {
			throw new IllegalStateException("Arguments not set");
		}

		String title = args.getString(ARG_TITLE,
				context.getString(R.string.vault_password_dialog_default_title));
		String message = args.getString(ARG_MESSAGE, "");
		boolean allowEmpty = args.getBoolean(ARG_ALLOW_EMPTY, false);
		boolean confirmMode = args.getBoolean(ARG_CONFIRM_MODE, false);

		LayoutInflater inflater = LayoutInflater.from(context);
		View view = inflater.inflate(R.layout.dialog_password, null);

		messageText = view.findViewById(R.id.password_message);
		passwordInput = view.findViewById(R.id.password_input_1);
		confirmPasswordInput = view.findViewById(R.id.password_input_2);
		View confirmLayout = view.findViewById(R.id.password_layout_2);
		showPasswordCheckbox = view.findViewById(R.id.show_password_checkbox);

		if (message != null && !message.isEmpty()) {
			messageText.setText(message);
			messageText.setVisibility(View.VISIBLE);
		} else {
			messageText.setVisibility(View.GONE);
		}

		if (confirmMode) {
			confirmLayout.setVisibility(View.VISIBLE);
		} else {
			confirmLayout.setVisibility(View.GONE);
		}

		showPasswordCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
			int inputType = isChecked
					? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
					: InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
			passwordInput.setInputType(inputType);
			if (confirmMode) {
				confirmPasswordInput.setInputType(inputType);
			}
		});

		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
				.setTitle(title)
				.setView(view)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					char[] password = readChars(passwordInput);
					char[] confirmPassword = confirmMode
							? readChars(confirmPasswordInput)
							: password;

					if (!allowEmpty && password.length == 0) {
						java.util.Arrays.fill(password, '\0');
						if (confirmMode) {
							java.util.Arrays.fill(confirmPassword, '\0');
						}
						if (callback != null) {
							callback.onPasswordCancelled();
						}
						return;
					}

					if (confirmMode
							&& !java.util.Arrays.equals(password,
									confirmPassword)) {
						java.util.Arrays.fill(password, '\0');
						java.util.Arrays.fill(confirmPassword, '\0');
						passwordInput.setError(context.getString(
								R.string.vault_password_mismatch));
						confirmPasswordInput.setError(context.getString(
								R.string.vault_password_mismatch));
						DocumentPasswordDialog newDialog = confirmMode
								? newPasswordDialog(title, message)
								: newUnlockDialog(title, message);
						newDialog.setCallback(callback);
						newDialog.show(getParentFragmentManager(), "password_retry");
						return;
					}

					char[] passwordChars = password.length == 0 ? null
							: password;

					if (callback != null) {
						callback.onPasswordEntered(passwordChars);
					}
					if (confirmMode) {
						java.util.Arrays.fill(confirmPassword, '\0');
					}

					passwordInput.setText("");
					confirmPasswordInput.setText("");
				})
				.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
					if (callback != null) {
						callback.onPasswordCancelled();
					}
				});

		if (allowEmpty) {
			builder.setNeutralButton(R.string.vault_password_dialog_no_password,
					(dialog, which) -> {
				if (callback != null) {
					callback.onPasswordEntered(null);
				}
			});
		}

		return builder.create();
	}

	private static char[] readChars(android.widget.EditText input) {
		android.text.Editable e = input.getText();
		if (e == null) return new char[0];
		char[] out = new char[e.length()];
		e.getChars(0, e.length(), out, 0);
		return out;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (passwordInput != null) {
			passwordInput.setText("");
		}
		if (confirmPasswordInput != null) {
			confirmPasswordInput.setText("");
		}
	}
}
