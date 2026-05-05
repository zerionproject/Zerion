package com.professor.zerion.android.sticker;

import android.icu.lang.UCharacter;
import android.icu.lang.UProperty;

import org.briarproject.nullsafety.NotNullByDefault;

import java.text.BreakIterator;

import androidx.annotation.Nullable;

/**
 * Sticker-related predicates and constants.
 *
 * Wire compatibility with iOS commit 3311da4:
 *   - Emoji stickers travel as plain text (one grapheme cluster containing
 *     an emoji). Receivers without the feature still render them as normal
 *     text — just smaller.
 *   - Image stickers travel as a regular inline-image attachment whose
 *     contentType carries the parameter ";profile=sticker" (e.g.
 *     "image/png; profile=sticker"). Receivers without the feature show
 *     them as a regular small photo.
 */
@NotNullByDefault
public final class StickerUtils {

	public static final String STICKER_PNG_MIME =
			"image/png; profile=sticker";

	/** Curated standard emoji pack (UI parity with iOS). */
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

	/**
	 * True iff the trimmed text is exactly one grapheme cluster that
	 * Unicode considers an emoji.
	 *
	 * Mirrors the iOS predicate at StickerPack.swift:isSingleEmojiSticker.
	 * Accepts default-emoji-presentation cluster (e.g. 🎉, single code
	 * point with EMOJI_PRESENTATION) AND the explicit-presentation
	 * sequence (text-default emoji + U+FE0F, e.g. ❤️ ☀️).
	 */
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

	/**
	 * True iff the contentType string carries an iOS-shipped sticker
	 * profile marker. Case-insensitive on the parameters portion.
	 */
	public static boolean isStickerContentType(@Nullable String contentType) {
		if (contentType == null) return false;
		String lower = contentType.toLowerCase();
		return lower.contains("profile=sticker")
				|| lower.contains("zerion-sticker");
	}

	/**
	 * Strip MIME parameters from a contentType string, returning the
	 * canonical "type/subtype" portion. Used everywhere we need to feed
	 * a contentType to a decoder/dispatcher that only understands
	 * canonical MIME types (e.g. AndroidUtils.getSupportedImageContentTypes).
	 *
	 * "image/png; profile=sticker" → "image/png"
	 * "image/jpeg"                 → "image/jpeg"
	 * null / empty                 → ""
	 */
	public static String baseMime(@Nullable String contentType) {
		if (contentType == null) return "";
		int semi = contentType.indexOf(';');
		String head = semi < 0 ? contentType : contentType.substring(0, semi);
		return head.trim().toLowerCase();
	}
}
