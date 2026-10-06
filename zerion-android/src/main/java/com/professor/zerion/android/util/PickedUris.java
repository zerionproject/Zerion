package com.professor.zerion.android.util;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.widget.Toast;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

@NotNullByDefault
public final class PickedUris {

	private PickedUris() {
	}

	public static boolean isForeignContent(Context ctx, @Nullable Uri uri) {
		if (uri == null || !"content".equals(uri.getScheme())) return false;
		String authority = uri.getEncodedAuthority();
		if (authority == null || authority.isEmpty()) return false;
		int at = authority.lastIndexOf('@');
		if (at >= 0) authority = authority.substring(at + 1);
		String own = ctx.getPackageName();
		if (authority.equals(own) || authority.startsWith(own + ".")) {
			return false;
		}
		try {
			ProviderInfo p = ctx.getPackageManager()
					.resolveContentProvider(authority, 0);
			if (p != null && own.equals(p.packageName)) return false;
		} catch (RuntimeException e) {
			return false;
		}
		return true;
	}

	@Nullable
	public static Uri accept(Context ctx, @Nullable Uri uri) {
		if (uri == null) return null;
		if (isForeignContent(ctx, uri)) return uri;
		refused(ctx);
		return null;
	}

	public static List<Uri> accept(Context ctx, @Nullable List<Uri> uris) {
		if (uris == null || uris.isEmpty()) return Collections.emptyList();
		List<Uri> out = new ArrayList<>(uris.size());
		for (Uri u : uris) {
			if (isForeignContent(ctx, u)) out.add(u);
		}
		if (out.size() < uris.size()) refused(ctx);
		return out;
	}

	private static void refused(Context ctx) {
		try {
			Toast.makeText(ctx, R.string.picked_file_refused,
					Toast.LENGTH_SHORT).show();
		} catch (RuntimeException ignored) {
		}
	}
}
