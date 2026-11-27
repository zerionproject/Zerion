package com.professor.zerion.android.vault.ui;

import android.content.Context;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SecurePasswordEditTextAccessibilityTest {

	private static final String SYNTHETIC = "zt-synthetic-pass";

	private static final class Catcher extends LinearLayout {

		final List<String> seen = new ArrayList<>();
		int events = 0;
		boolean password = false;

		Catcher(Context context) {
			super(context);
		}

		@Override
		public boolean requestSendAccessibilityEvent(View child,
				AccessibilityEvent event) {
			events++;
			password = event.isPassword();
			seen.add(String.valueOf(event.getBeforeText()));
			for (CharSequence t : event.getText()) {
				seen.add(String.valueOf(t));
			}
			return false;
		}
	}

	private static FragmentActivity host() {
		FragmentActivity a = Robolectric.buildActivity(FragmentActivity.class)
				.setup().get();
		a.setTheme(R.style.ZerionTheme_NoActionBar);
		return a;
	}

	private static <T extends EditText> T shown(FragmentActivity a, T field) {
		TextInputLayout til = new TextInputLayout(a);
		til.addView(field);
		LinearLayout root = new LinearLayout(a);
		root.addView(til);
		a.setContentView(root);
		return field;
	}

	private static SecurePasswordEditText filled() {
		FragmentActivity a = host();
		SecurePasswordEditText field =
				shown(a, new SecurePasswordEditText(a));
		field.setText(SYNTHETIC);
		return field;
	}

	private static String nodeText(EditText field) {
		CharSequence t = field.createAccessibilityNodeInfo().getText();
		return t == null ? "" : t.toString();
	}

	private static void assertMasked(EditText field) {
		AccessibilityNodeInfo info = field.createAccessibilityNodeInfo();
		String reported = String.valueOf(info.getText());
		assertFalse("the password reached accessibility services",
				reported.contains(SYNTHETIC));
		assertEquals("a screen reader still learns the length",
				SYNTHETIC.length(), reported.length());
		assertTrue("a screen reader is told it is a password",
				info.isPassword());
		assertEquals(EditText.class.getName(),
				String.valueOf(info.getClassName()));
	}

	private static void collect(View v, List<SecurePasswordEditText> out) {
		if (v instanceof SecurePasswordEditText) {
			out.add((SecurePasswordEditText) v);
		}
		if (v instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) v;
			for (int i = 0; i < group.getChildCount(); i++) {
				collect(group.getChildAt(i), out);
			}
		}
	}

	@Test
	public void theTextInputLayoutHandsTheTypedTextOfAnOrdinaryFieldOn() {
		FragmentActivity a = host();
		TextInputEditText field = shown(a, new TextInputEditText(a));
		field.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		field.setText(SYNTHETIC);

		assertTrue(field.getTransformationMethod()
				instanceof PasswordTransformationMethod);
		assertEquals(SYNTHETIC, nodeText(field));

		IncognitoInputHelper.enforceSecureInputsOnViewTree(field);

		assertFalse(nodeText(field).contains(SYNTHETIC));
	}

	@Test
	public void aFilledFieldGivesAccessibilityNoTypedText() {
		SecurePasswordEditText field = filled();

		assertTrue(field.getTransformationMethod()
				instanceof PasswordTransformationMethod);
		assertMasked(field);
		assertEquals(SYNTHETIC, new String(field.getPasswordChars()));
	}

	@Test
	public void aFieldSetUpByTheScreensGivesAccessibilityNoTypedText() {
		SecurePasswordEditText field = filled();

		IncognitoInputHelper.configurePasswordField(field);
		IncognitoInputHelper.enforceSecureInputsOnViewTree(field);

		assertMasked(field);
	}

	@Test
	public void aPasswordShownOnTheScreenIsStillNotGivenToAccessibility() {
		SecurePasswordEditText field = filled();

		field.setTransformationMethod(null);

		assertMasked(field);
	}

	@Test
	public void theEventsOfAFilledFieldCarryNoTypedText() {
		SecurePasswordEditText field = filled();
		field.setTransformationMethod(null);
		AccessibilityEvent event = AccessibilityEvent.obtain(
				AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
		event.setBeforeText(SYNTHETIC);

		field.onInitializeAccessibilityEvent(event);
		field.onPopulateAccessibilityEvent(event);

		assertFalse(event.getText().isEmpty());
		for (CharSequence t : event.getText()) {
			assertFalse(String.valueOf(t).contains(SYNTHETIC));
		}
		assertFalse(String.valueOf(event.getBeforeText())
				.contains(SYNTHETIC));
		assertTrue(event.isPassword());

	}

	@Test
	public void anEventThatLeavesAFilledFieldCarriesNoTypedText() {
		FragmentActivity a = host();
		SecurePasswordEditText field = new SecurePasswordEditText(a);
		TextInputLayout til = new TextInputLayout(a);
		til.addView(field);
		Catcher root = new Catcher(a);
		root.addView(til);
		a.setContentView(root);
		field.setText(SYNTHETIC);
		field.setTransformationMethod(null);
		AccessibilityEvent sent = AccessibilityEvent.obtain(
				AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
		sent.setBeforeText(SYNTHETIC);
		sent.getText().add(SYNTHETIC);

		field.sendAccessibilityEventUnchecked(sent);

		assertEquals(1, root.events);
		assertTrue(root.seen.size() >= 2);
		for (String text : root.seen) {
			assertFalse(text.contains(SYNTHETIC));
		}
		assertTrue(root.password);
	}

	@Test
	public void anEmptyFieldStillReportsItsHintAndThatItIsAPassword() {
		FragmentActivity a = host();
		SecurePasswordEditText field =
				shown(a, new SecurePasswordEditText(a));
		field.setHint("Password");

		AccessibilityNodeInfo info = field.createAccessibilityNodeInfo();

		assertEquals("Password", String.valueOf(info.getText()));
		assertTrue(info.isPassword());
	}

	@Test
	public void theUnlockScreenAndTheChangeDialogGiveNoTypedText() {
		int[] layouts = {R.layout.fragment_vault_unlock,
				R.layout.dialog_change_password,
				R.layout.fragment_vault_setup,
				R.layout.fragment_vault_onboarding};
		int[] fields = {1, 3, 2, 2};
		for (int i = 0; i < layouts.length; i++) {
			FragmentActivity a = host();
			View root = LayoutInflater.from(a).inflate(layouts[i], null);
			a.setContentView(root);
			List<SecurePasswordEditText> found = new ArrayList<>();
			collect(root, found);
			assertEquals(fields[i], found.size());
			for (SecurePasswordEditText field : found) {
				assertNotNull(field.getParent());
				field.setText(SYNTHETIC);
				assertMasked(field);
				field.setTransformationMethod(null);
				assertMasked(field);
			}
		}
	}
}
