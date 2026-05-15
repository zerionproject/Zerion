package com.professor.zerion.android.sticker;

import android.icu.lang.UCharacter;
import android.icu.lang.UProperty;

import org.briarproject.nullsafety.NotNullByDefault;

import java.text.BreakIterator;

import androidx.annotation.Nullable;

@NotNullByDefault
public final class StickerUtils {

	public static final String STICKER_PNG_MIME =
			"image/png; profile=sticker";

	public static final String[] STANDARD_PACK = {
			"🎉", "🎊", "🥳",
			"🎂", "🎁", "🎈",
			"❤️", "💜", "💙",
			"💚", "💛", "🧡",
			"🔥", "✨", "⭐️",
			"🌟", "💫", "⚡️",
			"👍", "👏", "🙌",
			"👋", "🤝", "🙏",
			"😂", "😍", "😎",
			"🤩", "😴", "🤔",
			"💪", "🫡", "🤙",
			"👀", "💯", "🚀",
	};

	private StickerUtils() {
	}

	public static boolean isSingleEmojiSticker(@Nullable String s) {
		if (s == null) return false;
		String trimmed = s.trim();
		if (trimmed.isEmpty()) return false;
		BreakIterator it = BreakIterator.getCharacterInstance();
		it.setText(trimmed);
		int first = it.first();
		int end = it.next();
		if (end != trimmed.length()) return false;
		String cluster = trimmed.substring(first, end);
		boolean hasEmojiPresentation = false;
		boolean hasEmoji = false;
		boolean hasVS16 = false;
		int i = 0;
		while (i < cluster.length()) {
			int cp = cluster.codePointAt(i);
			i += Character.charCount(cp);
			if (UCharacter.hasBinaryProperty(cp, UProperty.EMOJI_PRESENTATION)) {
				hasEmojiPresentation = true;
			}
			if (UCharacter.hasBinaryProperty(cp, UProperty.EMOJI)) {
				hasEmoji = true;
			}
			if (cp == 0xFE0F) hasVS16 = true;
		}
		return hasEmojiPresentation || (hasEmoji && hasVS16);
	}

	public static boolean isStickerContentType(@Nullable String contentType) {
		if (contentType == null) return false;
		String lower = contentType.toLowerCase();
		return lower.contains("profile=sticker")
				|| lower.contains("zerion-sticker");
	}

	public static String baseMime(@Nullable String contentType) {
		if (contentType == null) return "";
		int semi = contentType.indexOf(';');
		String head = semi < 0 ? contentType : contentType.substring(0, semi);
		return head.trim().toLowerCase();
	}
}
