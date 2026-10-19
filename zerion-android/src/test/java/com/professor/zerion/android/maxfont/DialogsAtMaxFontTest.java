package com.professor.zerion.android.maxfont;

import android.os.Looper;
import android.text.Layout;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
public class DialogsAtMaxFontTest extends MaxFontTest {

	private static final int SYSTEM_BARS_DP = 24 + 48;

	private static View laidOut(Largest a, AlertDialog dialog) {
		dialog.show();
		shadowOf(Looper.getMainLooper()).idle();
		View decor = dialog.getWindow().getDecorView();
		int height = a.getResources().getDisplayMetrics().heightPixels
				- Math.round(SYSTEM_BARS_DP * dp(a));
		decor.measure(View.MeasureSpec.makeMeasureSpec(screenWidth(a),
				View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(
				height, View.MeasureSpec.AT_MOST));
		decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
		assertTrue("the dialog is taller than the screen",
				decor.getHeight() <= height);
		return decor;
	}

	private static void assertWhollyVisible(View decor, View v, String name) {
		int[] b = boundsIn(v, decor);
		int bottom = decor.getHeight() - decor.getPaddingBottom();
		assertTrue(name + " starts above the dialog", b[1] >= 0);
		assertTrue(name + " ends at " + b[3] + " below the dialog at "
				+ bottom, b[3] <= bottom);
		for (View p = (View) v.getParent(); p != decor;
				p = (View) p.getParent()) {
			int[] pb = boundsIn(p, decor);
			assertTrue(name + " is hidden by " + p.getClass().getSimpleName()
							+ " " + pb[1] + ".." + pb[3] + " vs " + b[1] + ".."
							+ b[3],
					b[1] >= pb[1] && b[3] <= pb[3]);
		}
	}

	private static void assertLabelWhole(Button button) {
		String name = button.getText().toString();
		Layout layout = button.getLayout();
		assertFalse(name + " is cut short", ellipsized(button));
		assertNull(name + " breaks inside a word", brokenWord(button));
		int room = button.getWidth() - button.getCompoundPaddingLeft()
				- button.getCompoundPaddingRight();
		for (int i = 0; i < layout.getLineCount(); i++) {
			assertTrue(name + " is wider than its button",
					layout.getLineMax(i) <= room);
		}
		int needed = button.getCompoundPaddingTop() + layout.getHeight()
				+ button.getCompoundPaddingBottom();
		assertTrue(name + " needs " + needed + "px and gets "
				+ button.getHeight(), button.getHeight() >= needed);
	}

	private static List<Button> buttons(AlertDialog dialog) {
		List<Button> out = new ArrayList<>();
		for (int which : new int[] {AlertDialog.BUTTON_POSITIVE,
				AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE}) {
			Button b = dialog.getButton(which);
			if (b != null && b.getVisibility() == View.VISIBLE) out.add(b);
		}
		return out;
	}

	private static void assertEveryButtonReachable(View decor,
			AlertDialog dialog, int expected) {
		List<Button> buttons = buttons(dialog);
		assertTrue("buttons " + buttons.size(), buttons.size() == expected);
		for (Button b : buttons) {
			assertLabelWhole(b);
			assertWhollyVisible(decor, b, b.getText().toString());
		}
		View message = dialog.findViewById(android.R.id.message);
		if (message != null && message.getVisibility() == View.VISIBLE) {
			View panel = dialog.findViewById(androidx.appcompat.R.id.contentPanel);
			assertTrue("the message has no room left", panel.getHeight() > 0);
		}
	}

	@Test
	public void deletingForEveryoneKeepsAllThreeChoicesVisible() {
		Largest a = activity();
		String question = a.getResources().getQuantityString(
				R.plurals.conversation_delete_message, 1, 1);
		AlertDialog dialog = new SecureAlertDialogBuilder(a)
				.setTitle(R.string.conversation_delete_title)
				.setMessage(question + "\n\n"
						+ a.getString(R.string.delete_for_everyone_explained))
				.setPositiveButton(R.string.delete_for_everyone, null)
				.setNeutralButton(R.string.delete_for_me, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		View decor = laidOut(a, dialog);
		assertEveryButtonReachable(decor, dialog, 3);
	}

	@Test
	public void theDeleteWordStepKeepsItsFieldAndButtonsVisible() {
		Largest a = activity();
		View view = LayoutInflater.from(a).inflate(
				R.layout.dialog_channel_delete, null);
		AlertDialog dialog = new SecureAlertDialogBuilder(a)
				.setTitle(R.string.channels_delete_everyone_title)
				.setMessage(R.string.channels_delete_everyone_message)
				.setView(view)
				.setPositiveButton(R.string.continue_button, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		View decor = laidOut(a, dialog);
		assertEveryButtonReachable(decor, dialog, 2);
		EditText input = view.findViewById(R.id.channelDeleteWordInput);
		assertWhollyVisible(decor, input, "the word field");
	}

	@Test
	public void theLostPasswordStepKeepsItsFieldAndButtonsVisible() {
		Largest a = activity();
		TextInputLayout til = new TextInputLayout(a);
		TextInputEditText input = new TextInputEditText(til.getContext());
		input.setHint(a.getString(R.string.delete_confirm_hint,
				a.getString(R.string.delete_confirm_word)));
		til.addView(input);
		AlertDialog dialog = new SecureAlertDialogBuilder(a,
				R.style.ZerionDialogTheme)
				.setTitle(R.string.dialog_title_lost_password)
				.setMessage(a.getString(R.string.dialog_message_lost_password)
						+ "\n\n"
						+ a.getString(R.string.startup_erase_everything_confirm))
				.setView(til)
				.setPositiveButton(R.string.cancel, null)
				.setNegativeButton(R.string.delete, null)
				.create();
		View decor = laidOut(a, dialog);
		assertEveryButtonReachable(decor, dialog, 2);
		assertWhollyVisible(decor, input, "the word field");
	}

	@Test
	public void theHelperPromptKeepsBothChoicesVisible() {
		Largest a = activity();
		AlertDialog dialog = new SecureAlertDialogBuilder(a)
				.setTitle(R.string.tor_helper_prompt_title)
				.setMessage(R.string.tor_helper_prompt_message)
				.setPositiveButton(R.string.tor_helper_prompt_use, null)
				.setNegativeButton(R.string.tor_helper_prompt_not_now, null)
				.create();
		View decor = laidOut(a, dialog);
		assertEveryButtonReachable(decor, dialog, 2);
	}

	@Test
	public void dialogTitlesAndMessagesDoNotBreakWords() {
		Largest a = activity();
		AlertDialog dialog = new SecureAlertDialogBuilder(a)
				.setTitle(R.string.channels_delete_everyone_title)
				.setMessage(R.string.channels_delete_everyone_message)
				.setPositiveButton(R.string.continue_button, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		laidOut(a, dialog);
		TextView message = dialog.findViewById(android.R.id.message);
		assertNull(brokenWord(message));
	}

	private static final String[] CHOICES = {"Een", "Twee", "Drie"};

	private static void assertListAndFieldShow(Largest a, AlertDialog dialog,
			EditText field) {
		View decor = laidOut(a, dialog);
		ListView list = dialog.getListView();
		assertTrue("the list is missing", list != null && list.getHeight() > 0
				&& list.getChildCount() > 0);
		assertWhollyVisible(decor, field, "the field beside the list");
		assertEveryButtonReachable(decor, dialog, 2);
	}

	@Test
	public void aListWithAFieldOpensInTheMaterialDialog() {
		Largest a = activity();
		EditText field = new EditText(a);
		AlertDialog dialog = new SecureAlertDialogBuilder(a)
				.setTitle(R.string.conversation_delete_title)
				.setView(field)
				.setItems(CHOICES, null)
				.setPositiveButton(R.string.continue_button, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		assertListAndFieldShow(a, dialog, field);
	}

	@Test
	public void aChoiceListWithAFieldOpensInTheZerionDialog() {
		Largest a = activity();
		EditText field = new EditText(a);
		AlertDialog dialog = new SecureAlertDialogBuilder(a,
				R.style.ZerionDialogTheme)
				.setTitle(R.string.conversation_delete_title)
				.setView(field)
				.setSingleChoiceItems(CHOICES, 0, null)
				.setPositiveButton(R.string.continue_button, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		assertListAndFieldShow(a, dialog, field);
	}

	private static final int SHORT_PANEL_DP = 208;

	private static View inShortWindow(Largest a, AlertDialog dialog,
			EditText field) {
		dialog.show();
		shadowOf(Looper.getMainLooper()).idle();
		field.requestFocus();
		View panel = dialog.findViewById(androidx.appcompat.R.id.parentPanel);
		int width = Math.round(312 * dp(a));
		int height = Math.round(SHORT_PANEL_DP * dp(a));
		for (int pass = 0; pass < 2; pass++) {
			panel.measure(View.MeasureSpec.makeMeasureSpec(width,
					View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
					height, View.MeasureSpec.AT_MOST));
			panel.layout(0, 0, panel.getMeasuredWidth(),
					panel.getMeasuredHeight());
			field.requestRectangleOnScreen(new android.graphics.Rect(0, 0,
					field.getWidth(), field.getHeight()), true);
		}
		assertTrue("the panel is taller than the window",
				panel.getHeight() <= height);
		return panel;
	}

	private static void assertInside(View panel, View v, String name) {
		int[] b = boundsIn(v, panel);
		assertTrue(name + " ends at " + b[3] + " below the window at "
				+ panel.getHeight(), b[1] >= 0 && b[3] <= panel.getHeight());
		for (View p = (View) v.getParent(); p != panel;
				p = (View) p.getParent()) {
			int[] pb = boundsIn(p, panel);
			assertTrue(name + " is hidden by " + p.getClass().getSimpleName()
							+ " " + pb[1] + ".." + pb[3] + " vs " + b[1] + ".."
							+ b[3],
					b[1] >= pb[1] && b[3] <= pb[3]);
		}
	}

	private static AlertDialog deleteWordStep(Largest a, View view) {
		return new SecureAlertDialogBuilder(a)
				.setTitle(R.string.channels_delete_everyone_title)
				.setMessage(R.string.channels_delete_everyone_message)
				.setView(view)
				.setPositiveButton(R.string.continue_button, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
	}

	private static AlertDialog decoyCode(Largest a, View view) {
		return new SecureAlertDialogBuilder(a)
				.setTitle(R.string.decoy_set_code_title)
				.setView(view)
				.setPositiveButton(R.string.decoy_set_code_save, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
	}

	@Test
	public void aShortWindowKeepsTheFieldAndButtonsAtNormalText() {
		Normal a = normalActivity();
		View word = LayoutInflater.from(a).inflate(
				R.layout.dialog_channel_delete, null);
		EditText wordField = word.findViewById(R.id.channelDeleteWordInput);
		AlertDialog delete = deleteWordStep(a, word);
		View panel = inShortWindow(a, delete, wordField);
		assertInside(panel, wordField, "the DELETE field");
		for (Button b : buttons(delete)) {
			assertInside(panel, b, b.getText().toString());
		}
		View code = LayoutInflater.from(a).inflate(R.layout.dialog_password,
				null);
		EditText codeField = code.findViewById(R.id.password_input_1);
		AlertDialog decoy = decoyCode(a, code);
		panel = inShortWindow(a, decoy, codeField);
		assertInside(panel, codeField, "the code field");
		for (Button b : buttons(decoy)) {
			assertInside(panel, b, b.getText().toString());
		}
	}

	@Test
	public void aShortWindowKeepsTheFieldAndMainButtonAtTheLargestText() {
		Largest a = activity();
		View word = LayoutInflater.from(a).inflate(
				R.layout.dialog_channel_delete, null);
		EditText wordField = word.findViewById(R.id.channelDeleteWordInput);
		AlertDialog delete = deleteWordStep(a, word);
		View panel = inShortWindow(a, delete, wordField);
		assertInside(panel, wordField, "the DELETE field");
		assertInside(panel, delete.getButton(AlertDialog.BUTTON_POSITIVE),
				"the continue button");
		View code = LayoutInflater.from(a).inflate(R.layout.dialog_password,
				null);
		EditText codeField = code.findViewById(R.id.password_input_1);
		AlertDialog decoy = decoyCode(a, code);
		panel = inShortWindow(a, decoy, codeField);
		assertInside(panel, codeField, "the code field");
		assertInside(panel, decoy.getButton(AlertDialog.BUTTON_POSITIVE),
				"the save button");
	}

	@Test
	public void aTooNarrowDialogAsksForMoreWidth() {
		Largest a = activity();
		LinearLayout wide = new LinearLayout(a);
		wide.setOrientation(LinearLayout.HORIZONTAL);
		int cell = Math.round(200 * dp(a));
		for (int i = 0; i < 2; i++) {
			wide.addView(new View(a), new LinearLayout.LayoutParams(cell, cell));
		}
		AlertDialog dialog = new SecureAlertDialogBuilder(a)
				.setTitle(R.string.conversation_delete_title)
				.setView(wide)
				.setPositiveButton(R.string.continue_button, null)
				.create();
		dialog.show();
		shadowOf(Looper.getMainLooper()).idle();
		View panel = dialog.findViewById(androidx.appcompat.R.id.parentPanel);
		panel.measure(View.MeasureSpec.makeMeasureSpec(Math.round(320 * dp(a)),
				View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(
				Math.round(600 * dp(a)), View.MeasureSpec.AT_MOST));
		assertTrue("the panel hides that its content is too wide",
				(panel.getMeasuredWidthAndState() & View.MEASURED_STATE_MASK)
						== View.MEASURED_STATE_TOO_SMALL);
	}
}
