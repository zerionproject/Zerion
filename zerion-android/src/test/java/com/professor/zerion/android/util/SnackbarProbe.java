package com.professor.zerion.android.util;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

public final class SnackbarProbe {

	private SnackbarProbe() {
	}

	@Nullable
	public static String text(Activity a) {
		TextView label = a.getWindow().getDecorView().findViewById(
				com.google.android.material.R.id.snackbar_text);
		return label == null ? null : label.getText().toString();
	}

	@Nullable
	public static TextView action(Activity a) {
		TextView action = a.getWindow().getDecorView().findViewById(
				com.google.android.material.R.id.snackbar_action);
		return action == null || action.getVisibility() != View.VISIBLE
				? null : action;
	}

	public static List<String> texts(Activity a) {
		List<String> out = new ArrayList<>();
		collect(a.getWindow().getDecorView(), out);
		return out;
	}

	private static void collect(View v, List<String> out) {
		if (v.getId() == com.google.android.material.R.id.snackbar_text
				&& v instanceof TextView) {
			out.add(((TextView) v).getText().toString());
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				collect(g.getChildAt(i), out);
			}
		}
	}
}
