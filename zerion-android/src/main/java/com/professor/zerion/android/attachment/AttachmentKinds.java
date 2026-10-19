package com.professor.zerion.android.attachment;

import android.content.Context;
import android.text.format.Formatter;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

@NotNullByDefault
public final class AttachmentKinds {

	private AttachmentKinds() {
	}

	@StringRes
	public static int name(@Nullable String mimeType) {
		String type = SharedMediaSanitizer.base(mimeType);
		if (type.startsWith("image/")) return R.string.image;
		if (type.startsWith("video/")) return R.string.video;
		if (type.startsWith("audio/")) return R.string.attachment_kind_audio;
		if (type.equals(AttachmentDocuments.PDF)) {
			return R.string.attachment_kind_pdf;
		}
		if (type.contains("spreadsheet") || type.contains("excel")
				|| type.equals("text/csv")) {
			return R.string.attachment_kind_spreadsheet;
		}
		if (type.startsWith("text/")) return R.string.attachment_kind_text;
		if (type.contains("presentation") || type.contains("powerpoint")) {
			return R.string.attachment_kind_presentation;
		}
		if (type.contains("wordprocessing") || type.equals("application/msword")
				|| type.equals("application/rtf")
				|| type.equals("application/vnd.oasis.opendocument.text")) {
			return R.string.document_attachment_description;
		}
		if (type.equals("application/zip") || type.contains("compressed")
				|| type.equals("application/x-7z-compressed")
				|| type.equals("application/x-tar")
				|| type.equals("application/gzip")) {
			return R.string.attachment_kind_archive;
		}
		return R.string.attachment_kind_file;
	}

	public static String describe(Context ctx, @Nullable String mimeType) {
		return ctx.getString(name(mimeType));
	}

	public static String describe(Context ctx, @Nullable String mimeType,
			long sizeBytes) {
		if (sizeBytes <= 0) return describe(ctx, mimeType);
		return ctx.getString(R.string.attachment_row_description,
				describe(ctx, mimeType),
				Formatter.formatShortFileSize(ctx, sizeBytes));
	}

	@DrawableRes
	public static int icon(@Nullable String mimeType) {
		String type = SharedMediaSanitizer.base(mimeType);
		if (type.startsWith("image/")) return R.drawable.ic_photo;
		if (type.startsWith("video/")) return R.drawable.ic_video;
		if (type.equals(AttachmentDocuments.PDF)) return R.drawable.ic_pdf;
		return R.drawable.ic_file;
	}
}
