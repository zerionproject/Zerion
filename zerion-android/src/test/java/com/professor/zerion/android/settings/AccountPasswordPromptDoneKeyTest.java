package com.professor.zerion.android.settings;

import android.os.Looper;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import com.professor.zerion.R;
import com.professor.zerion.android.vault.ui.IncognitoInputHelper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AccountPasswordPromptDoneKeyTest {

	private static AlertDialog dialogWith(AppCompatActivity host,
			EditText field, AtomicInteger oks) {
		AlertDialog dialog = new AlertDialog.Builder(host)
				.setView(field)
				.setPositiveButton(android.R.string.ok,
						(d, w) -> oks.incrementAndGet())
				.setNegativeButton(android.R.string.cancel, null)
				.show();
		shadowOf(Looper.getMainLooper()).idle();
		return dialog;
	}

	private static AppCompatActivity host() {
		AppCompatActivity a = Robolectric.buildActivity(
				AppCompatActivity.class).setup().get();
		a.setTheme(R.style.ZerionTheme_NoActionBar);
		return a;
	}

	@Test
	public void theDoneKeyConfirmsThePasswordLikeOk() {
		AppCompatActivity host = host();
		EditText field = new EditText(host);
		IncognitoInputHelper.configurePasswordField(field);
		AtomicInteger oks = new AtomicInteger();
		AlertDialog dialog = dialogWith(host, field, oks);
		AccountPasswordGate.submitOnDone(field, dialog);

		field.setText("a long password");
		field.onEditorAction(EditorInfo.IME_ACTION_DONE);
		shadowOf(Looper.getMainLooper()).idle();

		assertEquals(1, oks.get());
	}

	@Test
	public void theEnterKeyConfirmsInsteadOfTypingANewline() {
		AppCompatActivity host = host();
		EditText field = new EditText(host);
		IncognitoInputHelper.configurePasswordField(field);
		AtomicInteger oks = new AtomicInteger();
		AlertDialog dialog = dialogWith(host, field, oks);
		AccountPasswordGate.submitOnDone(field, dialog);
		field.setText("secret");
		field.requestFocus();

		field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,
				KeyEvent.KEYCODE_ENTER));
		field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,
				KeyEvent.KEYCODE_ENTER));
		shadowOf(Looper.getMainLooper()).idle();

		assertEquals(1, oks.get());
		assertEquals(-1, field.getText().toString().indexOf('\n'));
	}

	@Test
	public void otherEditorActionsDoNothing() {
		AppCompatActivity host = host();
		EditText field = new EditText(host);
		IncognitoInputHelper.configurePasswordField(field);
		AtomicInteger oks = new AtomicInteger();
		AlertDialog dialog = dialogWith(host, field, oks);
		AccountPasswordGate.submitOnDone(field, dialog);

		field.onEditorAction(EditorInfo.IME_ACTION_NEXT);
		shadowOf(Looper.getMainLooper()).idle();

		assertEquals(0, oks.get());
	}
}
