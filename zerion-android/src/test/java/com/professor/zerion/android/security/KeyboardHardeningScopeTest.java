package com.professor.zerion.android.security;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Looper;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.BaseActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import javax.annotation.Nullable;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class KeyboardHardeningScopeTest {

	static {
		TestAndroidKeyStore.register();
	}

	public static class ProbeScreen extends BaseActivity {

		FrameLayout content;

		@Override
		public void injectActivity(ActivityComponent component) {
		}

		@Override
		public void onCreate(@Nullable Bundle state) {
			super.onCreate(state);
			content = new FrameLayout(this);
			setContentView(content);
		}
	}

	public static class FieldDialog extends DialogFragment {

		EditText field;

		@Override
		public Dialog onCreateDialog(@Nullable Bundle state) {
			field = new EditText(requireContext());
			Dialog d = new Dialog(requireContext());
			d.setContentView(field);
			return d;
		}
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static void assertHardened(EditText field) {
		assertTrue("the keyboard may learn from this field",
				(field.getImeOptions()
						& EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0);
		assertTrue("the keyboard may suggest from this field",
				(field.getInputType()
						& InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0);
		String priv = field.getPrivateImeOptions();
		assertNotNull(priv);
		assertTrue(priv.contains("nm=1"));
	}

	private static FragmentActivity host() {
		FragmentActivity a = Robolectric.buildActivity(FragmentActivity.class)
				.setup().get();
		a.setTheme(com.google.android.material.R.style
				.Theme_MaterialComponents_DayNight_NoActionBar);
		SecureDialogs.install(a);
		return a;
	}

	private static EditText textField(android.content.Context c) {
		EditText e = new EditText(c);
		e.setInputType(InputType.TYPE_CLASS_TEXT);
		return e;
	}

	@Test
	public void aFieldInAnAlertDialogIsHardened() {
		FragmentActivity a = host();
		LinearLayout layout = new LinearLayout(a);
		EditText field = textField(a);
		layout.addView(field);
		AlertDialog d = new SecureAlertDialogBuilder(a).setTitle("t")
				.setView(layout).create();
		d.show();
		idle();
		assertHardened(field);
	}

	@Test
	public void aFieldAddedToADialogAfterItOpenedIsHardenedOnFocus() {
		FragmentActivity a = host();
		LinearLayout layout = new LinearLayout(a);
		AlertDialog d = new SecureAlertDialogBuilder(a).setTitle("t")
				.setView(layout).create();
		d.show();
		idle();
		EditText late = textField(a);
		layout.addView(late);
		late.requestFocus();
		idle();
		assertHardened(late);
	}

	@Test
	public void aFieldInADialogFragmentIsHardened() {
		FragmentActivity a = host();
		FieldDialog f = new FieldDialog();
		f.show(a.getSupportFragmentManager(), "field");
		idle();
		assertNotNull(f.field);
		assertHardened(f.field);
	}

	@Test
	public void aFieldAScreenAddsAfterItResumedIsHardenedOnFocus() {
		ProbeScreen a = Robolectric.buildActivity(ProbeScreen.class).setup()
				.get();
		idle();
		EditText late = textField(a);
		a.content.addView(late);
		late.requestFocus();
		idle();
		assertHardened(late);
	}
}
