package com.professor.zerion.android.vault.ui;

import android.content.Context;
import android.text.InputType;
import android.view.View;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SeedEntryFieldAccessibilityTest {

	private static final String SYNTHETIC =
			"zt synthetic words never used for a wallet";
	private static final String UI = "src/main/java/com/professor/zerion/"
			+ "android/vault/ui/";

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

	private static TextInputEditText phraseField(FragmentActivity a,
			LinearLayout root) {
		TextInputLayout til = new TextInputLayout(a);
		TextInputEditText field = new TextInputEditText(til.getContext());
		field.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
				| InputType.TYPE_TEXT_FLAG_MULTI_LINE);
		field.setMinLines(2);
		til.addView(field);
		root.addView(til);
		a.setContentView(root);
		return field;
	}

	private static String nodeText(EditText field) {
		CharSequence t = field.createAccessibilityNodeInfo().getText();
		return t == null ? "" : t.toString();
	}

	@Test
	public void aPhraseFieldSetUpTheOldWayHandsTheTypedWordsOn() {
		FragmentActivity a = host();
		TextInputEditText field = phraseField(a, new LinearLayout(a));
		IncognitoInputHelper.configureIncognitoInput(field, false);
		IncognitoInputHelper.enforceSecureInputsOnViewTree(field);
		field.setText(SYNTHETIC);

		assertEquals(SYNTHETIC, nodeText(field));
	}

	@Test
	public void aPhraseFieldGivesAccessibilityNoTypedWords() {
		FragmentActivity a = host();
		TextInputEditText field = phraseField(a, new LinearLayout(a));

		IncognitoInputHelper.configureSecretPhraseField(field);
		field.setText(SYNTHETIC);

		AccessibilityNodeInfo info = field.createAccessibilityNodeInfo();
		String reported = String.valueOf(info.getText());
		assertFalse("the words reached accessibility services",
				reported.contains("synthetic"));
		assertEquals(SYNTHETIC.length(), reported.length());
		assertTrue(info.isPassword());
		IncognitoInputHelper.enforceSecureInputsOnViewTree(field);
		assertFalse(nodeText(field).contains("synthetic"));
	}

	@Test
	public void theEventsOfAPhraseFieldCarryNoTypedWords() {
		FragmentActivity a = host();
		Catcher root = new Catcher(a);
		TextInputEditText field = phraseField(a, root);
		IncognitoInputHelper.configureSecretPhraseField(field);
		field.setText(SYNTHETIC);
		AccessibilityEvent sent = AccessibilityEvent.obtain(
				AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
		sent.setBeforeText(SYNTHETIC);
		sent.getText().add(SYNTHETIC);

		field.sendAccessibilityEventUnchecked(sent);

		assertEquals(1, root.events);
		assertTrue(root.seen.size() >= 2);
		for (String text : root.seen) {
			assertFalse(text.contains("synthetic"));
		}
		assertTrue(root.password);
	}

	@Test
	public void aPhraseFieldStillTakesTypedAndPastedWordsOnSeveralLines() {
		FragmentActivity a = host();
		TextInputEditText field = phraseField(a, new LinearLayout(a));

		IncognitoInputHelper.configureSecretPhraseField(field);
		field.setText(SYNTHETIC);
		field.getText().append(" more");

		assertEquals(SYNTHETIC + " more", field.getText().toString());
		int type = field.getInputType();
		assertEquals(InputType.TYPE_CLASS_TEXT,
				type & InputType.TYPE_MASK_CLASS);
		assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
				type & InputType.TYPE_MASK_VARIATION);
		assertNotEquals(0, type & InputType.TYPE_TEXT_FLAG_MULTI_LINE);
		assertNotEquals(0, type & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		assertTrue(field.isLongClickable());
		assertTrue(field.isEnabled());
		assertFalse(field.isSaveEnabled());
		assertTrue(field.onTextContextMenuItem(android.R.id.selectAll));
	}

	private static String importDialog(String file) throws Exception {
		String s = new String(Files.readAllBytes(Paths.get(UI + file)),
				StandardCharsets.UTF_8);
		int start = s.indexOf("private void showImportDialog(");
		assertTrue(file, start >= 0);
		int end = s.indexOf("\n\tprivate ", start + 1);
		assertTrue(file, end > start);
		return s.substring(start, end);
	}

	@Test
	public void bothImportDialogsProtectTheirPhraseField() throws Exception {
		String btc = importDialog("VaultWalletFragment.java");
		assertTrue(btc.contains(
				"IncognitoInputHelper.configureSecretPhraseField(phrase)"));
		assertFalse(btc.contains("configureIncognitoInput(phrase"));
		String xmr = importDialog("XmrWalletFragment.java");
		assertTrue(xmr.contains(
				"IncognitoInputHelper.configureSecretPhraseField(seed)"));
		assertFalse(xmr.contains("configureIncognitoInput(seed"));
	}
}
