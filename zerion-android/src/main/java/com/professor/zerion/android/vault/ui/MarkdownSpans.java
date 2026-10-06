package com.professor.zerion.android.vault.ui;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@NotNullByDefault
final class MarkdownSpans {

	private static final int CODE_BACKGROUND = 0xFF1D242B;
	private static final Pattern INLINE = Pattern.compile(
			"\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`(.+?)`");

	private MarkdownSpans() {
	}

	static Spanned render(String markdown) {
		SpannableStringBuilder out = new SpannableStringBuilder();
		String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
		boolean inCode = false;
		int codeStart = 0;
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (line.trim().startsWith("```")) {
				if (inCode) {
					code(out, codeStart, out.length());
				} else {
					codeStart = out.length();
				}
				inCode = !inCode;
				continue;
			}
			if (inCode) {
				out.append(line).append('\n');
				continue;
			}
			int level = headingLevel(line);
			int start = out.length();
			if (level > 0) {
				appendInline(out, line.substring(level + 1));
				out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
						Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
				out.setSpan(new RelativeSizeSpan(1.6f - 0.2f * level), start,
						out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			} else {
				appendInline(out, line);
			}
			if (i < lines.length - 1) out.append('\n');
		}
		if (inCode) code(out, codeStart, out.length());
		return out;
	}

	private static int headingLevel(String line) {
		for (int level = 3; level >= 1; level--) {
			StringBuilder prefix = new StringBuilder();
			for (int j = 0; j < level; j++) prefix.append('#');
			prefix.append(' ');
			if (line.startsWith(prefix.toString())) return level;
		}
		return 0;
	}

	private static void appendInline(SpannableStringBuilder out, String text) {
		Matcher m = INLINE.matcher(text);
		int last = 0;
		while (m.find()) {
			out.append(text, last, m.start());
			int start = out.length();
			if (m.group(1) != null) {
				out.append(m.group(1));
				out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
						Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			} else if (m.group(2) != null) {
				out.append(m.group(2));
				out.setSpan(new StyleSpan(Typeface.ITALIC), start,
						out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			} else {
				out.append(m.group(3));
				code(out, start, out.length());
			}
			last = m.end();
		}
		out.append(text, last, text.length());
	}

	private static void code(SpannableStringBuilder out, int start, int end) {
		if (end <= start) return;
		out.setSpan(new TypefaceSpan("monospace"), start, end,
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		out.setSpan(new BackgroundColorSpan(CODE_BACKGROUND), start, end,
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
	}
}
