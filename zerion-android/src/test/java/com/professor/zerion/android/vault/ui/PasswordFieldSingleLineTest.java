package com.professor.zerion.android.vault.ui;

import android.content.Context;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.widget.EditText;

import com.google.android.material.textfield.TextInputEditText;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class PasswordFieldSingleLineTest {

	private static Context themed() {
		Context app = RuntimeEnvironment.getApplication();
		app.setTheme(com.professor.zerion.R.style.ZerionTheme_NoActionBar);
		return app;
	}

	@Test
	public void aPasswordFieldBuiltInCodeStaysOnOneLine() {
		TextInputEditText field = new TextInputEditText(themed());
		IncognitoInputHelper.configurePasswordField(field);

		assertEquals("no multi-line flag on a password field", 0,
				field.getInputType() & InputType.TYPE_TEXT_FLAG_MULTI_LINE);
		assertEquals(1, field.getMaxLines());
		assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD,
				field.getInputType() & InputType.TYPE_MASK_VARIATION);
		assertTrue("still masked",
				field.getTransformationMethod()
						instanceof PasswordTransformationMethod);
	}

	@Test
	public void aVeryLongPasswordDoesNotGrowTheField() {
		TextInputEditText field = new TextInputEditText(themed());
		IncognitoInputHelper.configurePasswordField(field);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 2000; i++) sb.append((char) ('a' + i % 26));
		field.setText(sb);

		assertEquals(1, field.getMaxLines());
		assertEquals(0,
				field.getInputType() & InputType.TYPE_TEXT_FLAG_MULTI_LINE);
		assertTrue(field.getTransformationMethod()
				instanceof PasswordTransformationMethod);
	}

	@Test
	public void aMultiLineRecoveryPhraseFieldKeepsItsLines() {
		EditText phrase = new EditText(themed());
		phrase.setMaxLines(8);
		IncognitoInputHelper.configureIncognitoInput(phrase, false);

		assertTrue((phrase.getInputType()
				& InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0);
	}
}
