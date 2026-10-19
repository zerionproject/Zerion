package com.professor.zerion.android.maxfont;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class TrailingLabelRowsAtMaxFontTest extends MaxFontTest {

	static void collectProblems(View v, List<String> problems) {
		if (v.getVisibility() != View.VISIBLE) return;
		if (v instanceof TextView) {
			TextView t = (TextView) v;
			if (t.length() == 0 || t.getLayout() == null) return;
			String broken = brokenWord(t);
			if (broken != null) problems.add("breaks a word: " + broken);
			if (ellipsized(t)) problems.add("cut short: " + t.getText());
			int room = t.getWidth() - t.getCompoundPaddingLeft()
					- t.getCompoundPaddingRight();
			for (int i = 0; i < t.getLayout().getLineCount(); i++) {
				if (t.getLayout().getLineMax(i) > room + 1) {
					problems.add("too wide: " + t.getText());
				}
			}
			return;
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				collectProblems(g.getChildAt(i), problems);
			}
		}
	}

	private static void assertNoBrokenWords(View root, int... rows) {
		List<String> problems = new ArrayList<>();
		for (int id : rows) collectProblems(root.findViewById(id), problems);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void theSafetyCodeRowKeepsItsWordsWhole() {
		Largest a = activity();
		View sheet = LayoutInflater.from(a).inflate(
				R.layout.sheet_channel_member, null, false);
		((TextView) sheet.findViewById(R.id.channelMemberName)).setText(
				"Persoon zonder naam");
		((TextView) sheet.findViewById(R.id.channelMemberRole)).setText(
				R.string.channels_member_role_subscriber);
		layoutAtWidth(host(a, sheet), screenWidth(a));
		assertNoBrokenWords(sheet, R.id.channelMemberSafetyCode,
				R.id.channelMemberBlock, R.id.channelMemberUnblock,
				R.id.channelMemberTrust, R.id.channelMemberEditor,
				R.id.channelMemberRemove);
		View row = sheet.findViewById(R.id.channelMemberSafetyCode);
		assertTrue("the row grows with its words",
				row.getHeight() >= row.getMinimumHeight());
	}

	@Test
	public void theConnectionRowKeepsItsWordsWhole() {
		Largest a = activity();
		View screen = LayoutInflater.from(a).inflate(
				R.layout.activity_channel_settings, null, false);
		((TextView) screen.findViewById(
				R.id.channelSettingsConnectionSummary)).setText(
				R.string.channels_settings_connection_owner);
		layoutAtWidth(host(a, screen), screenWidth(a));
		assertNoBrokenWords(screen, R.id.channelSettingsConnection);
	}
}
