package com.professor.zerion.android.security;

import android.text.InputType;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.android.vault.ui.IncognitoInputHelper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class PasswordAccessibilityTest {

	private static final String SYNTHETIC = "zt-synthetic-pass";

	private static FragmentActivity host() {
		FragmentActivity a = Robolectric.buildActivity(FragmentActivity.class)
				.setup().get();
		a.setTheme(com.google.android.material.R.style
				.Theme_MaterialComponents_DayNight_NoActionBar);
		return a;
	}

	private static String nodeText(EditText field) {
		AccessibilityNodeInfo info = field.createAccessibilityNodeInfo();
		CharSequence t = info.getText();
		return t == null ? "" : t.toString();
	}

	@Test
	public void aPasswordInATextInputLayoutIsNotGivenToAccessibility() {
		FragmentActivity a = host();
		TextInputLayout til = new TextInputLayout(a);
		TextInputEditText field = new TextInputEditText(til.getContext());
		field.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		til.addView(field);
		LinearLayout root = new LinearLayout(a);
		root.addView(til);
		a.setContentView(root);
		field.setText(SYNTHETIC);

		IncognitoInputHelper.enforceSecureInputsOnViewTree(root);

		String reported = nodeText(field);
		assertFalse("the password reached accessibility services",
				reported.contains(SYNTHETIC));
		assertEquals("a screen reader still learns the length",
				SYNTHETIC.length(), reported.length());
	}

	@Test
	public void aPlainPasswordFieldIsNotGivenToAccessibility() {
		FragmentActivity a = host();
		EditText field = new EditText(a);
		field.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		a.setContentView(field);
		field.setText(SYNTHETIC);

		IncognitoInputHelper.enforceSecureInputsOnViewTree(field);

		assertFalse(nodeText(field).contains(SYNTHETIC));
		AccessibilityEvent event = AccessibilityEvent.obtain(
				AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
		field.onInitializeAccessibilityEvent(event);
		field.onPopulateAccessibilityEvent(event);
		for (CharSequence t : event.getText()) {
			assertFalse(String.valueOf(t).contains(SYNTHETIC));
		}
		assertTrue(event.isPassword());
	}

	@Test
	public void anEmptyPasswordFieldStillReportsItsHint() {
		FragmentActivity a = host();
		EditText field = new EditText(a);
		field.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		field.setHint("Password");
		a.setContentView(field);

		IncognitoInputHelper.enforceSecureInputsOnViewTree(field);

		assertEquals("Password", nodeText(field));
	}

	@Test
	public void anOrdinaryTextFieldIsReportedUnchanged() {
		FragmentActivity a = host();
		EditText field = new EditText(a);
		field.setInputType(InputType.TYPE_CLASS_TEXT);
		a.setContentView(field);
		field.setText("hello");

		IncognitoInputHelper.enforceSecureInputsOnViewTree(field);

		assertEquals("hello", nodeText(field));
	}
}
