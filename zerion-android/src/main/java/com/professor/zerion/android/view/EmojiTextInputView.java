package com.professor.zerion.android.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.IBinder;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.vanniktech.emoji.EmojiPopup;
import com.vanniktech.emoji.RecentEmoji;

import com.professor.zerion.R;
import com.professor.zerion.android.ZerionApplication;

import java.util.Arrays;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.AppCompatImageButton;

import static android.content.Context.INPUT_METHOD_SERVICE;
import static android.content.Context.LAYOUT_INFLATER_SERVICE;
import static android.view.KeyEvent.KEYCODE_ENTER;
import static android.view.inputmethod.EditorInfo.IME_ACTION_SEND;
import static android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT;
import static java.lang.Character.isWhitespace;
import static java.util.Objects.requireNonNull;
import static org.zerionproject.core.util.StringUtils.utf8IsTooLong;
import static com.professor.zerion.android.util.UiUtils.resolveColorAttribute;

public class EmojiTextInputView extends LinearLayout implements
		TextWatcher {

	@Inject
	RecentEmoji recentEmoji;

	private final AppCompatImageButton emojiToggle;
	private final EmojiPopup emojiPopup;
	private final EditText editText;
	private final boolean textSavedByDefault;
	private final InputMethodManager imm;

	@Nullable
	private TextInputListener listener;
	@Nullable
	private OnKeyboardShownListener keyboardShownListener;
	private int maxLength = Integer.MAX_VALUE;
	private boolean emptyTextAllowed = false;
	private boolean isEmpty = true;
	private boolean keyboardOpen = false;
	private boolean locked = false;

	public EmojiTextInputView(Context context) {
		this(context, null);
	}

	public EmojiTextInputView(Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public EmojiTextInputView(Context context, @Nullable AttributeSet attrs,
			int defStyleAttr) {
		super(context, attrs, defStyleAttr);

		LayoutInflater inflater = (LayoutInflater) requireNonNull(
				context.getSystemService(LAYOUT_INFLATER_SERVICE));
		inflater.inflate(R.layout.emoji_text_input_view, this, true);

		TypedArray a = context.obtainStyledAttributes(attrs,
				R.styleable.EmojiTextInputView);
		int paddingBottom = a.getDimensionPixelSize(
				R.styleable.EmojiTextInputView_textPaddingBottom, 0);
		int paddingEnd = a.getDimensionPixelSize(
				R.styleable.EmojiTextInputView_textPaddingEnd, 0);
		int maxLines =
				a.getInteger(R.styleable.EmojiTextInputView_maxTextLines, 0);
		Drawable textBackground = a.getDrawable(
				R.styleable.EmojiTextInputView_inputTextBackground);
		a.recycle();

		editText = findViewById(R.id.input_text);
		textSavedByDefault = editText.isSaveEnabled();
		int left = editText.getPaddingLeft();
		editText.setPadding(left, 0, paddingEnd, paddingBottom);
		if (textBackground != null) editText.setBackground(textBackground);
		if (maxLines > 0) editText.setMaxLines(maxLines);
		editText.addTextChangedListener(this);
		InputFilter[] filters = editText.getFilters();
		InputFilter[] withLock = Arrays.copyOf(filters, filters.length + 1);
		withLock[filters.length] = (source, start, end, dest, dstart, dend) ->
				locked && end > start ? "" : null;
		editText.setFilters(withLock);
		editText.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == IME_ACTION_SEND) {
				listener.onSendEvent();
				hideSoftKeyboard();
				return true;
			}
			return false;
		});
		editText.setOnKeyListener((v, keyCode, event) -> {
			if (listener != null && keyCode == KEYCODE_ENTER &&
					event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
				listener.onSendEvent();
				return true;
			}
			return false;
		});
		emojiToggle = findViewById(R.id.emoji_toggle);

		if (isInEditMode()) {
			emojiPopup = null;
			imm = null;
			return;
		}
		Object o = getContext().getSystemService(INPUT_METHOD_SERVICE);
		imm = (InputMethodManager) requireNonNull(o);

		ZerionApplication app =
				(ZerionApplication) context.getApplicationContext();
		app.getApplicationComponent().inject(this);
		emojiPopup = EmojiPopup.Builder
				.fromRootView(getRootView())
				.setRecentEmoji(recentEmoji)
				.setOnEmojiPopupShownListener(this::showKeyboardIcon)
				.setOnEmojiPopupDismissListener(this::showEmojiIcon)
				.setKeyboardAnimationStyle(R.style.emoji_fade_animation_style)
				.setOnSoftKeyboardOpenListener(this::onKeyboardOpened)
				.setOnSoftKeyboardCloseListener(this::onKeyboardClosed)
				.setIconColor(resolveColorAttribute(getContext(),
						R.attr.colorControlNormal))
				.setBackgroundColor(resolveColorAttribute(getContext(),
						android.R.attr.colorBackground))
				.build(editText);
		emojiToggle.setOnClickListener(v -> {
			if (emojiPopup.isShowing()) {
				emojiPopup.dismiss();
				showSoftKeyboard();
			} else {
				emojiPopup.show();
			}
		});
		editText.setOnClickListener(v -> {
			if (emojiPopup.isShowing()) {
				emojiPopup.dismiss();
				showSoftKeyboard();
			}
		});
	}

	@Override
	public void beforeTextChanged(CharSequence s, int start, int count,
			int after) {
	}

	@Override
	public void onTextChanged(CharSequence s, int start, int before,
			int count) {
		if (emptyTextAllowed || listener == null) return;
		if (isEmpty) {
			if (countLeadingWhitespace(s, start, count) < count) {
				isEmpty = false;
				listener.onTextIsEmptyChanged(false);
			}
		} else if (before > 0) {
			int length = s.length();
			if (countLeadingWhitespace(s, 0, length) == length) {
				isEmpty = true;
				listener.onTextIsEmptyChanged(true);
			}
		}
	}

	private int countLeadingWhitespace(CharSequence s, int off, int len) {
		for (int i = 0; i < len; i++) {
			if (!isWhitespace(s.charAt(off + i))) return i;
		}
		return len;
	}

	@Override
	public void afterTextChanged(Editable s) {
	}

	@Override
	public void setEnabled(boolean enabled) {
		super.setEnabled(enabled);
		editText.setEnabled(enabled);
		emojiToggle.setEnabled(enabled);
	}

	@Override
	public void setGravity(int gravity) {
		if (editText != null) {
			editText.setGravity(gravity);
		} else {
			super.setGravity(gravity);
		}
	}

	@Override
	public boolean requestFocus(int direction, Rect previouslyFocusedRect) {
		return editText.requestFocus(direction, previouslyFocusedRect);
	}

	@Override
	public void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		if (emojiPopup.isShowing()) emojiPopup.dismiss();
	}

	void setTextInputListener(@Nullable TextInputListener listener) {
		this.listener = listener;
	}

	void setAllowEmptyText(boolean emptyTextAllowed) {
		this.emptyTextAllowed = emptyTextAllowed;
	}

	void setMaxLength(int maxLength) {
		this.maxLength = maxLength;
	}

	void setMaxLines(int maxLines) {
		editText.setMaxLines(maxLines);
	}

	@Nullable
	String getText() {
		Editable editable = editText.getText();
		String str = editable == null ? null : editable.toString().trim();
		if (str == null || str.length() == 0) return null;
		return str;
	}

	void clearText() {
		editText.setText(null);
	}

	void setText(String text) {
		editText.setText(text);
		editText.setSelection(editText.length());
	}

	void setTextSaved(boolean saved) {
		editText.setSaveEnabled(saved && textSavedByDefault);
	}

	boolean isEmpty() {
		return getText() == null;
	}

	boolean isTooLong() {
		return editText.getText() != null &&
				utf8IsTooLong(editText.getText().toString().trim(), maxLength);
	}

	CharSequence getHint() {
		return editText.getHint();
	}

	void setHint(@StringRes int res) {
		setHint(getContext().getString(res));
	}

	void setHint(CharSequence hint) {
		editText.setHint(hint);
	}

	boolean isKeyboardOpen() {
		return keyboardOpen || imm.isFullscreenMode();
	}

	private void showEmojiIcon() {
		emojiToggle.setImageResource(R.drawable.ic_emoji_toggle);
	}

	private void showKeyboardIcon() {
		emojiToggle.setImageResource(R.drawable.ic_keyboard);
	}

	void showSoftKeyboard() {
		if (editText.requestFocus()) imm.showSoftInput(editText, SHOW_IMPLICIT);
	}

	void hideSoftKeyboard() {
		if (emojiPopup.isShowing()) emojiPopup.dismiss();
		IBinder token = editText.getWindowToken();
		imm.hideSoftInputFromWindow(token, 0);
	}

	void setLocked(boolean locked) {
		if (this.locked == locked) return;
		this.locked = locked;
		editText.setFocusable(!locked);
		editText.setFocusableInTouchMode(!locked);
		emojiToggle.setEnabled(!locked);
		if (locked) {
			editText.clearFocus();
			if (emojiPopup != null && imm != null) hideSoftKeyboard();
		}
	}

	boolean isLocked() {
		return locked;
	}

	private void onKeyboardOpened(
			@SuppressWarnings("unused") int keyboardHeight) {
		keyboardOpen = true;
		if (keyboardShownListener != null)
			keyboardShownListener.onKeyboardShown();
	}

	private void onKeyboardClosed() {
		if (imm.isFullscreenMode()) {
			onKeyboardOpened(0);
			return;
		}
		keyboardOpen = false;
	}

	void setOnKeyboardShownListener(
			@Nullable OnKeyboardShownListener listener) {
		keyboardShownListener = listener;
	}

	interface TextInputListener {
		void onTextIsEmptyChanged(boolean isEmpty);

		void onSendEvent();
	}

	public interface OnKeyboardShownListener {
		void onKeyboardShown();
	}

}
