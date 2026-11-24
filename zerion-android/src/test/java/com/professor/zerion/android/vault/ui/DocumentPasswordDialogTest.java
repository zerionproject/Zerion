package com.professor.zerion.android.vault.ui;

import android.content.DialogInterface;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.widget.EditText;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class DocumentPasswordDialogTest {

	private static final String TAG = "password_dialog";
	private static final String TYPED = "open sesame";

	static {
		TestAndroidKeyStore.register();
	}

	private static final class ReadsRecorded extends SpannableStringBuilder {

		private final List<char[]> copies;

		private ReadsRecorded(CharSequence source, List<char[]> copies) {
			super(source);
			this.copies = copies;
		}

		@Override
		public void getChars(int start, int end, char[] dest, int destoff) {
			super.getChars(start, end, dest, destoff);
			copies.add(dest);
		}
	}

	private TextEditorSaveTest.Host host;
	private final List<char[]> copies = new ArrayList<>();
	private final List<String> answers = new ArrayList<>();

	@Before
	public void setUp() {
		ShadowDialog.reset();
		host = Robolectric.buildActivity(TextEditorSaveTest.Host.class)
				.setup().get();
		idle();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private AlertDialog show(DocumentPasswordDialog dialog) {
		dialog.show(host.getSupportFragmentManager(), TAG);
		idle();
		AlertDialog shown = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(shown);
		assertTrue(shown.isShowing());
		return shown;
	}

	private EditText typeInto(AlertDialog shown, int field) {
		EditText input = shown.findViewById(field);
		assertNotNull(input);
		input.setEditableFactory(new Editable.Factory() {
			@Override
			public Editable newEditable(CharSequence source) {
				return new ReadsRecorded(source, copies);
			}
		});
		input.setText(TYPED);
		return input;
	}

	private void ok(AlertDialog shown) {
		copies.clear();
		shown.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		idle();
	}

	private void assertNoCopyOfTheTypedPasswordIsLeft() {
		assertFalse("the typed password was never read", copies.isEmpty());
		for (char[] copy : copies) {
			assertFalse("a copy of the typed password was left in memory",
					new String(copy).contains(TYPED));
		}
	}

	private boolean isOpen() {
		idle();
		return host.getSupportFragmentManager().findFragmentByTag(TAG)
				!= null;
	}

	private DocumentPasswordDialog.PasswordCallback recording() {
		return new DocumentPasswordDialog.PasswordCallback() {
			@Override
			public void onPasswordEntered(@Nullable char[] password) {
				answers.add(password == null ? "none"
						: new String(password));
			}

			@Override
			public void onPasswordCancelled() {
				answers.add("cancelled");
			}
		};
	}

	@Test
	public void anUnlockDialogNobodyAnswersWipesWhatWasTypedAndCloses() {
		AlertDialog shown = show(DocumentPasswordDialog.newUnlockDialog(
				"Password", "Enter it"));
		EditText input = typeInto(shown, R.id.password_input_1);

		ok(shown);

		assertNoCopyOfTheTypedPasswordIsLeft();
		assertEquals("", input.getText().toString());
		assertFalse(shown.isShowing());
		assertFalse(isOpen());
	}

	@Test
	public void aNewPasswordDialogNobodyAnswersWipesBothFieldsAndCloses() {
		AlertDialog shown = show(DocumentPasswordDialog.newPasswordDialog(
				"Protect", "Choose one"));
		EditText first = typeInto(shown, R.id.password_input_1);
		EditText second = typeInto(shown, R.id.password_input_2);

		ok(shown);

		assertNoCopyOfTheTypedPasswordIsLeft();
		assertEquals("", first.getText().toString());
		assertEquals("", second.getText().toString());
		assertFalse(shown.isShowing());
		assertFalse(isOpen());
		assertNull("a second dialog was opened",
				host.getSupportFragmentManager()
						.findFragmentByTag("password_retry"));
	}

	@Test
	public void aDialogWithSomeoneToAnswerStillHandsThePasswordOver() {
		DocumentPasswordDialog dialog = DocumentPasswordDialog
				.newUnlockDialog("Password", "Enter it");
		dialog.setCallback(recording());
		AlertDialog shown = show(dialog);
		typeInto(shown, R.id.password_input_1);

		ok(shown);

		assertEquals(java.util.Collections.singletonList(TYPED), answers);
		assertFalse(isOpen());
	}
}
