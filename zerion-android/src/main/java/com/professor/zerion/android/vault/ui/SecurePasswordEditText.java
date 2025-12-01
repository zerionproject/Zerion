package com.professor.zerion.android.vault.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;

import com.google.android.material.textfield.TextInputEditText;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Arrays;

import javax.annotation.Nullable;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SecurePasswordEditText extends TextInputEditText {

	private char[] passwordChars = new char[0];
	private boolean passwordCleared = false;

	public SecurePasswordEditText(Context context) {
		super(context);
		init();
	}

	public SecurePasswordEditText(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	public SecurePasswordEditText(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init();
	}

	private void init() {
		setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

		addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
				clearPasswordChars();
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				if (s != null) {
					passwordChars = new char[s.length()];
					for (int i = 0; i < s.length(); i++) {
						passwordChars[i] = s.charAt(i);
					}
					passwordCleared = false;
				}
			}

			@Override
			public void afterTextChanged(Editable s) {
			}
		});
	}

	public char[] getPasswordChars() {
		if (passwordCleared) {
			return new char[0];
		}
		return Arrays.copyOf(passwordChars, passwordChars.length);
	}

	public int getPasswordLength() {
		if (passwordCleared) {
			return 0;
		}
		return passwordChars.length;
	}

	public boolean isPasswordEmpty() {
		return getPasswordLength() == 0;
	}

	public void clearPassword() {
		setText("");

		clearPasswordChars();

		passwordCleared = true;
	}

	private void clearPasswordChars() {
		if (passwordChars.length > 0) {
			Arrays.fill(passwordChars, '\0');
		}
	}

	@Deprecated
	public String getPasswordString() {
		if (passwordCleared || passwordChars.length == 0) {
			return "";
		}
		return new String(passwordChars);
	}

	@Override
	protected void onDetachedFromWindow() {
		clearPassword();
		super.onDetachedFromWindow();
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			clearPasswordChars();
		} finally {
			super.finalize();
		}
	}
}
