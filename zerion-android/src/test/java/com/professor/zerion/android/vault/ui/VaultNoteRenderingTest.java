package com.professor.zerion.android.vault.ui;

import android.graphics.Typeface;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultNoteRenderingTest {

	@Test
	public void markdownIsShownAsStyledText() {
		Spanned s = MarkdownSpans.render(
				"# Title\nsome **bold** and *slanted* and `code`\n"
						+ "<script>alert(1)</script>");
		String text = s.toString();
		assertEquals("Title\nsome bold and slanted and code\n"
				+ "<script>alert(1)</script>", text);
		boolean bold = false;
		for (StyleSpan span : s.getSpans(0, s.length(), StyleSpan.class)) {
			if (span.getStyle() == Typeface.BOLD
					&& text.substring(s.getSpanStart(span),
					s.getSpanEnd(span)).equals("bold")) {
				bold = true;
			}
		}
		assertTrue(bold);
		assertEquals(1, s.getSpans(0, s.length(), TypefaceSpan.class).length);
	}

	@Test
	public void aCodeBlockKeepsItsLines() {
		Spanned s = MarkdownSpans.render("before\n```\nline one\nline two\n"
				+ "```\nafter");
		assertEquals("before\nline one\nline two\nafter", s.toString());
	}
}
