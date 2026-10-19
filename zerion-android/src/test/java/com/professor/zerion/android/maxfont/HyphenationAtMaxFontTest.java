package com.professor.zerion.android.maxfont;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Looper;
import android.text.Layout;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
public class HyphenationAtMaxFontTest extends MaxFontTest {

	private static int hyphenation(Context c) {
		int value = c.getResources().getInteger(R.integer.text_hyphenation);
		assertNotEquals("hyphenation is switched off",
				Layout.HYPHENATION_FREQUENCY_NONE, value);
		return value;
	}

	@Test
	public void theAppThemeLeavesOtherTextAlone() {
		Largest a = activity();
		for (Context themed : new Context[] {a, new ContextThemeWrapper(a,
				R.style.ZerionDialogTheme)}) {
			TypedArray t = themed.obtainStyledAttributes(null,
					new int[] {android.R.attr.hyphenationFrequency},
					android.R.attr.textViewStyle, 0);
			try {
				assertTrue("every text view hyphenates", !t.hasValue(0)
						|| t.getInt(0, -1) == Layout.HYPHENATION_FREQUENCY_NONE);
			} finally {
				t.recycle();
			}
		}
	}

	@Test
	public void settingsRowsLabelsAndDialogButtonsHyphenate() {
		Largest a = activity();
		int expected = hyphenation(a);
		Context dialog = new ContextThemeWrapper(a,
				R.style.ThemeOverlay_Zerion_MaterialAlertDialog);
		int[] styles = {R.style.Zerion_Settings_Row_Title,
				R.style.Zerion_Settings_Row_Summary,
				R.style.GroupTrAdminActionText,
				R.style.GroupTrAdminActionTextDanger,
				R.style.Widget_Zerion_Button_TextButton_Dialog,
				R.style.Widget_Zerion_Button_TextButton_Dialog_Flush,
				R.style.ZerionButtonFlat,
				R.style.MaterialAlertDialog_Zerion_Title_Text};
		for (int style : styles) {
			TypedArray t = dialog.obtainStyledAttributes(style, new int[] {
					android.R.attr.hyphenationFrequency});
			try {
				assertEquals(a.getResources().getResourceEntryName(style),
						expected, t.getInt(0, -1));
			} finally {
				t.recycle();
			}
		}
	}

	@Test
	public void dialogButtonsAreNotHeldToOneLine() {
		Largest a = activity();
		Context dialog = new ContextThemeWrapper(a,
				R.style.ThemeOverlay_Zerion_MaterialAlertDialog);
		for (int style : new int[] {
				R.style.Widget_Zerion_Button_TextButton_Dialog,
				R.style.Widget_Zerion_Button_TextButton_Dialog_Flush}) {
			TypedArray t = dialog.obtainStyledAttributes(style, new int[] {
					android.R.attr.singleLine, android.R.attr.lines,
					android.R.attr.ellipsize});
			try {
				assertTrue(!t.getBoolean(0, true));
				assertTrue("a dialog button is held to one line",
						!t.hasValue(1));
				assertEquals(0, t.getInt(2, -1));
			} finally {
				t.recycle();
			}
		}
	}

	@Test
	public void titlesOfZerionDialogsHyphenate() {
		Largest a = activity();
		int expected = hyphenation(a);
		AlertDialog dialog = new SecureAlertDialogBuilder(a,
				R.style.ZerionDialogTheme)
				.setTitle(R.string.dialog_title_lost_password)
				.setMessage(R.string.dialog_message_lost_password)
				.setPositiveButton(R.string.cancel, null)
				.create();
		dialog.show();
		shadowOf(Looper.getMainLooper()).idle();
		TextView title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
		assertNotNull(title);
		assertEquals(expected, title.getHyphenationFrequency());
		assertEquals(Layout.BREAK_STRATEGY_HIGH_QUALITY,
				title.getBreakStrategy());
		dialog.dismiss();
	}

	@Test
	public void dialogMessagesHyphenate() {
		Largest a = activity();
		int expected = hyphenation(a);
		for (int theme : new int[] {0, R.style.ZerionDialogTheme}) {
			AlertDialog dialog = new SecureAlertDialogBuilder(a, theme)
					.setTitle(R.string.channels_delete_everyone_title)
					.setMessage(R.string.channels_delete_everyone_message)
					.setPositiveButton(R.string.continue_button, null)
					.create();
			dialog.show();
			shadowOf(Looper.getMainLooper()).idle();
			TextView message = dialog.findViewById(android.R.id.message);
			assertNotNull(message);
			assertEquals(expected, message.getHyphenationFrequency());
			dialog.dismiss();
		}
	}

	private static TextView withText(View v, CharSequence text) {
		if (v instanceof TextView
				&& text.toString().equals(((TextView) v).getText().toString())) {
			return (TextView) v;
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				TextView found = withText(g.getChildAt(i), text);
				if (found != null) return found;
			}
		}
		return null;
	}

	@Test
	public void longSettingsCompoundsHyphenate() {
		Largest a = activity();
		int expected = hyphenation(a);
		int[][] cases = {
				{R.layout.fragment_settings_security,
						R.string.pref_screenshot_protection_title},
				{R.layout.fragment_settings_main,
						R.string.network_settings_summary}};
		for (int[] c : cases) {
			View screen = LayoutInflater.from(a).inflate(c[0], null, false);
			TextView t = withText(screen, a.getString(c[1]));
			assertNotNull(a.getString(c[1]), t);
			assertEquals(t.getText().toString(), expected,
					t.getHyphenationFrequency());
			assertEquals(t.getText().toString(),
					Layout.BREAK_STRATEGY_HIGH_QUALITY, t.getBreakStrategy());
		}
	}

	@Test
	public void messageTextIsNeverHyphenated() {
		Largest a = activity();
		int[][] bubbles = {
				{R.layout.list_item_conversation_msg_in, R.id.text},
				{R.layout.list_item_conversation_msg_out, R.id.text},
				{R.layout.list_item_grouptr_post_in, R.id.postText},
				{R.layout.list_item_grouptr_post_out, R.id.postText},
				{R.layout.list_item_channel_comment, R.id.commentBody},
				{R.layout.list_item_channel_post, R.id.channelPostBodyView}};
		for (int[] b : bubbles) {
			View row = LayoutInflater.from(a).inflate(b[0], null, false);
			TextView text = row.findViewById(b[1]);
			assertEquals(a.getResources().getResourceEntryName(b[0]),
					Layout.HYPHENATION_FREQUENCY_NONE,
					text.getHyphenationFrequency());
		}
	}
}
