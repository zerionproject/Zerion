package com.professor.zerion.android.vault.ui;

import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

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

	private static final char MASK = '•';

	private static final ViewTreeObserver.OnGlobalFocusChangeListener
			ON_FOCUS = (oldFocus, newFocus) -> {
				if (newFocus instanceof EditText) {
					enforceSecureInput((EditText) newFocus);
				}
			};

	private static final View.OnAttachStateChangeListener ON_ATTACH =
			new View.OnAttachStateChangeListener() {
				@Override
				public void onViewAttachedToWindow(View v) {
					v.getViewTreeObserver()
							.addOnGlobalFocusChangeListener(ON_FOCUS);
					enforceSecureInputsOnViewTree(v);
				}

				@Override
				public void onViewDetachedFromWindow(View v) {
					v.getViewTreeObserver()
							.removeOnGlobalFocusChangeListener(ON_FOCUS);
				}
			};

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

		if (!isPassword && editText.getMaxLines() > 1) {
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
			protectFromAccessibility(editText);
		}

		editText.setInputType(editText.getInputType() |
				InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
	}

	public static void configureForVault(EditText editText) {
		configureIncognitoInput(editText, false);
	}

	public static void configurePasswordField(EditText editText) {
		configureIncognitoInput(editText, true);
		editText.setSaveEnabled(false);
	}

	public static void install(View root) {
		enforceSecureInputsOnViewTree(root);
		root.removeOnAttachStateChangeListener(ON_ATTACH);
		root.addOnAttachStateChangeListener(ON_ATTACH);
		if (root.isAttachedToWindow()) {
			ViewTreeObserver observer = root.getViewTreeObserver();
			observer.removeOnGlobalFocusChangeListener(ON_FOCUS);
			observer.addOnGlobalFocusChangeListener(ON_FOCUS);
		}
	}

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

		int currentIme = editText.getImeOptions();
		if ((currentIme & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0) {
			editText.setImeOptions(currentIme |
					EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
		}

		String currentPrivate = editText.getPrivateImeOptions();
		if (currentPrivate == null || !currentPrivate.contains("nm=1")) {
			editText.setPrivateImeOptions(PRIVATE_IME_OPTIONS);
		}

		int currentType = editText.getInputType();
		if ((currentType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
			if ((currentType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0) {
				editText.setInputType(currentType |
						InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
			}
		}

		if (Build.VERSION.SDK_INT >= 26) {
			editText.setImportantForAutofill(
					View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
		}

		if (isPasswordInput(editText.getInputType())) {
			protectFromAccessibility(editText);
		}
	}

	static boolean isPasswordInput(int inputType) {
		int cls = inputType & InputType.TYPE_MASK_CLASS;
		int variation = inputType & InputType.TYPE_MASK_VARIATION;
		if (cls == InputType.TYPE_CLASS_TEXT) {
			return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
					|| variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
		}
		if (cls == InputType.TYPE_CLASS_NUMBER) {
			return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
		}
		return false;
	}

	public static void protectFromAccessibility(EditText field) {
		if (Build.VERSION.SDK_INT >= 34) {
			field.setAccessibilityDataSensitive(
					View.ACCESSIBILITY_DATA_SENSITIVE_YES);
			return;
		}
		View.AccessibilityDelegate current = field.getAccessibilityDelegate();
		if (current instanceof MaskingDelegate) return;
		field.setAccessibilityDelegate(new MaskingDelegate(current));
	}

	static CharSequence mask(int length) {
		char[] c = new char[length];
		Arrays.fill(c, MASK);
		return new String(c);
	}

	private static final class MaskingDelegate
			extends View.AccessibilityDelegate {

		@Nullable
		private final View.AccessibilityDelegate inner;

		MaskingDelegate(@Nullable View.AccessibilityDelegate inner) {
			this.inner = inner;
		}

		private static boolean holdsText(View host) {
			return host instanceof EditText && ((EditText) host).length() > 0;
		}

		@Override
		public void onInitializeAccessibilityNodeInfo(View host,
				AccessibilityNodeInfo info) {
			if (inner != null) inner.onInitializeAccessibilityNodeInfo(host, info);
			else super.onInitializeAccessibilityNodeInfo(host, info);
			info.setPassword(true);
			if (holdsText(host)) {
				info.setText(mask(((EditText) host).length()));
			}
		}

		@Override
		public void onInitializeAccessibilityEvent(View host,
				AccessibilityEvent event) {
			if (inner != null) inner.onInitializeAccessibilityEvent(host, event);
			else super.onInitializeAccessibilityEvent(host, event);
			maskEvent(host, event);
		}

		@Override
		public void onPopulateAccessibilityEvent(View host,
				AccessibilityEvent event) {
			if (inner != null) inner.onPopulateAccessibilityEvent(host, event);
			else super.onPopulateAccessibilityEvent(host, event);
			maskEvent(host, event);
		}

		@Override
		public boolean dispatchPopulateAccessibilityEvent(View host,
				AccessibilityEvent event) {
			boolean handled = inner != null
					? inner.dispatchPopulateAccessibilityEvent(host, event)
					: super.dispatchPopulateAccessibilityEvent(host, event);
			maskEvent(host, event);
			return handled;
		}

		@Override
		public void sendAccessibilityEvent(View host, int eventType) {
			if (inner != null) inner.sendAccessibilityEvent(host, eventType);
			else super.sendAccessibilityEvent(host, eventType);
		}

		@Override
		public void sendAccessibilityEventUnchecked(View host,
				AccessibilityEvent event) {
			maskEvent(host, event);
			if (inner != null) inner.sendAccessibilityEventUnchecked(host, event);
			else super.sendAccessibilityEventUnchecked(host, event);
		}

		@Override
		public boolean performAccessibilityAction(View host, int action,
				@Nullable Bundle args) {
			return inner != null
					? inner.performAccessibilityAction(host, action, args)
					: super.performAccessibilityAction(host, action, args);
		}

		@Override
		@Nullable
		public AccessibilityNodeProvider getAccessibilityNodeProvider(
				View host) {
			return inner != null ? inner.getAccessibilityNodeProvider(host)
					: super.getAccessibilityNodeProvider(host);
		}

		@Override
		public boolean onRequestSendAccessibilityEvent(ViewGroup host,
				View child, AccessibilityEvent event) {
			return inner != null
					? inner.onRequestSendAccessibilityEvent(host, child, event)
					: super.onRequestSendAccessibilityEvent(host, child, event);
		}

		private static void maskEvent(View host, AccessibilityEvent event) {
			event.setPassword(true);
			if (holdsText(host)) {
				List<CharSequence> text = event.getText();
				for (int i = 0; i < text.size(); i++) {
					CharSequence t = text.get(i);
					if (t != null && t.length() > 0) text.set(i, mask(t.length()));
				}
			}
			CharSequence before = event.getBeforeText();
			if (before != null && before.length() > 0) {
				event.setBeforeText(mask(before.length()));
			}
		}
	}
}
