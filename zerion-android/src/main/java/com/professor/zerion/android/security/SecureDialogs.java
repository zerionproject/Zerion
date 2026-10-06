package com.professor.zerion.android.security;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.professor.zerion.android.vault.ui.IncognitoInputHelper;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class SecureDialogs {

	private static final int FLAG = WindowManager.LayoutParams.FLAG_SECURE;

	private SecureDialogs() {
	}

	public static void applyHostPolicy(Dialog dialog) {
		Window w = dialog.getWindow();
		if (w == null) return;
		if (hostIsSecure(dialog.getContext())) protect(w);
		View decor = w.peekDecorView();
		if (decor != null && android.os.Build.VERSION.SDK_INT >= 30) {
			decor.setFilterTouchesWhenObscured(true);
		}
		if (decor != null && containsSecretInput(decor)) protect(w);
		if (decor != null) IncognitoInputHelper.install(decor);
	}

	public static <T extends Dialog> T protectSecret(T dialog) {
		Window w = dialog.getWindow();
		if (w != null) protect(w);
		return dialog;
	}

	public static boolean isProtected(@Nullable Window w) {
		return w != null && (w.getAttributes().flags & FLAG) != 0;
	}

	public static void install(FragmentActivity activity) {
		activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(
				new FragmentManager.FragmentLifecycleCallbacks() {
					@Override
					public void onFragmentStarted(FragmentManager fm,
							Fragment f) {
						if (!(f instanceof DialogFragment)) return;
						Dialog d = ((DialogFragment) f).getDialog();
						if (d != null) applyHostPolicy(d);
					}
				}, true);
	}

	static boolean hostIsSecure(Context context) {
		Activity a = activityOf(context);
		return a != null && isProtected(a.getWindow());
	}

	@Nullable
	private static Activity activityOf(Context context) {
		Context c = context;
		while (c instanceof ContextWrapper) {
			if (c instanceof Activity) return (Activity) c;
			c = ((ContextWrapper) c).getBaseContext();
		}
		return null;
	}

	static boolean containsSecretInput(View root) {
		if (root instanceof EditText) {
			return isPasswordInput(((EditText) root).getInputType());
		}
		if (root instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) root;
			for (int i = 0; i < g.getChildCount(); i++) {
				if (containsSecretInput(g.getChildAt(i))) return true;
			}
		}
		return false;
	}

	static boolean isPasswordInput(int inputType) {
		int cls = inputType & InputType.TYPE_MASK_CLASS;
		int variation = inputType & InputType.TYPE_MASK_VARIATION;
		if (cls == InputType.TYPE_CLASS_TEXT) {
			return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
					|| variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
					|| variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
		}
		if (cls == InputType.TYPE_CLASS_NUMBER) {
			return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
		}
		return false;
	}

	private static void protect(Window w) {
		w.addFlags(FLAG);
	}
}
