package com.professor.zerion.android.account;

import android.os.Bundle;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.professor.zerion.R;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;

import java.text.Normalizer;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import static org.zerionproject.core.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import org.zerionproject.core.api.identity.ReservedNames;
import static org.zerionproject.core.util.StringUtils.toUtf8;
import static com.professor.zerion.android.util.UiUtils.hideViewOnSmallScreen;
import static com.professor.zerion.android.util.UiUtils.setError;
import static com.professor.zerion.android.util.UiUtils.showOnboardingDialog;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class AuthorNameFragment extends SetupFragment {

	private final static String TAG = AuthorNameFragment.class.getName();

	private TextInputLayout authorNameWrapper;
	private TextInputEditText authorNameInput;
	private Button nextButton;

	public static AuthorNameFragment newInstance() {
		return new AuthorNameFragment();
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_setup_author_name,
				container, false);
		authorNameWrapper = v.findViewById(R.id.nickname_entry_wrapper);
		authorNameInput = v.findViewById(R.id.nickname_entry);
		Button infoButton = v.findViewById(R.id.info_button);
		nextButton = v.findViewById(R.id.next);

		authorNameInput.addTextChangedListener(this);
		infoButton.setOnClickListener(view ->
				showOnboardingDialog(requireContext(), getHelpText()));
		nextButton.setOnClickListener(this);
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
		hideViewOnSmallScreen(requireView().findViewById(R.id.logo));
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	protected String getHelpText() {
		return getString(R.string.setup_name_explanation);
	}

	@Override
	public void onTextChanged(CharSequence authorName, int i, int i1, int i2) {
		String sanitized = sanitizeAuthorName(authorName.toString());
		boolean hasInvalidChars = !sanitized.equals(authorName.toString().trim());
		int authorNameLength = toUtf8(sanitized).length;
		boolean tooLong = authorNameLength > MAX_AUTHOR_NAME_LENGTH;
		boolean reserved = ReservedNames.isReserved(sanitized);

		if (hasInvalidChars) {
			setError(authorNameWrapper, getString(R.string.name_invalid_characters), true);
		} else if (tooLong) {
			setError(authorNameWrapper, getString(R.string.name_too_long), true);
		} else if (reserved) {
			setError(authorNameWrapper, getString(R.string.name_reserved), true);
		} else {
			setError(authorNameWrapper, "", false);
		}

		boolean enabled = authorNameLength > 0 && !tooLong && !hasInvalidChars && !reserved;
		authorNameInput.setOnEditorActionListener(enabled ? this : null);
		nextButton.setEnabled(enabled);
	}

	@Override
	public void onClick(View view) {
		Editable text = authorNameInput.getText();
		if (text != null) {
			String sanitized = sanitizeAuthorName(text.toString());
			int authorNameLength = toUtf8(sanitized).length;
			boolean hasInvalidChars = !sanitized.equals(text.toString().trim());
			boolean tooLong = authorNameLength > MAX_AUTHOR_NAME_LENGTH;
			boolean reserved = ReservedNames.isReserved(sanitized);

			if (authorNameLength > 0 && !tooLong && !hasInvalidChars
					&& !reserved) {
				viewModel.setAuthorName(sanitized);
			}
		}
	}

	private String sanitizeAuthorName(String input) {
		if (input == null || input.isEmpty()) return "";

		String trimmed = input.trim();
		String normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFC);

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

}
