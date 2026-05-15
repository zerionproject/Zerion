package com.professor.zerion.android.util;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.MemoryInfo;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Debug;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.URLSpan;
import android.transition.Transition;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.bramble.util.StringUtils;
import com.professor.zerion.R;
import com.professor.zerion.android.view.ArticleMovementMethod;
import com.professor.zerion.android.api.MemoryStats;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Locale;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.AnyThread;
import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.core.content.ContextCompat;
import androidx.core.hardware.fingerprint.FingerprintManagerCompat;
import androidx.core.text.HtmlCompat;
import androidx.core.util.Consumer;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat;

import static android.content.Context.KEYGUARD_SERVICE;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.os.Build.MANUFACTURER;
import static android.os.Build.VERSION.SDK_INT;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.FORMAT_ABBREV_ALL;
import static android.text.format.DateUtils.FORMAT_ABBREV_MONTH;
import static android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE;
import static android.text.format.DateUtils.FORMAT_ABBREV_TIME;
import static android.text.format.DateUtils.FORMAT_SHOW_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_TIME;
import static android.text.format.DateUtils.FORMAT_SHOW_YEAR;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.WEEK_IN_MILLIS;
import static android.text.format.DateUtils.YEAR_IN_MILLIS;
import static android.view.KeyEvent.ACTION_DOWN;
import static android.view.KeyEvent.KEYCODE_ENTER;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;
import static android.view.inputmethod.EditorInfo.IME_NULL;
import static android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT;
import static android.widget.Toast.LENGTH_LONG;
import static androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
import static androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO;
import static androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;
import static androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode;
import static androidx.core.content.ContextCompat.getColor;
import static androidx.core.content.ContextCompat.getSystemService;
import static androidx.core.graphics.drawable.DrawableCompat.setTint;
import static androidx.core.view.ViewCompat.LAYOUT_DIRECTION_RTL;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static com.professor.zerion.android.TestingConstants.EXPIRY_DATE;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class UiUtils {

	public static final long MIN_DATE_RESOLUTION = MINUTE_IN_MILLIS;
	public static final int TEASER_LENGTH = 320;
	public static final float GREY_OUT = 0.5f;

	public static void showSoftKeyboard(View view) {
		if (view.requestFocus()) {
			InputMethodManager imm = requireNonNull(getSystemService(
					view.getContext(), InputMethodManager.class));
			imm.showSoftInput(view, SHOW_IMPLICIT);
		}
	}

	public static void hideSoftKeyboard(View view) {
		InputMethodManager imm = requireNonNull(
				getSystemService(view.getContext(), InputMethodManager.class));
		imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
	}

	public static void showFragment(FragmentManager fm, Fragment f,
			@Nullable String tag) {
		showFragment(fm, f, tag, true);
	}

	public static void showFragment(FragmentManager fm, Fragment f,
			@Nullable String tag, boolean addToBackStack) {
		Fragment fragment = fm.findFragmentByTag(tag);
		if (fragment != null && fragment.isAdded()) return;

		FragmentTransaction ta = fm.beginTransaction()
				.setCustomAnimations(R.anim.step_next_in,
						R.anim.step_previous_out, R.anim.step_previous_in,
						R.anim.step_next_out)
				.replace(R.id.fragmentContainer, f, tag);
		if (addToBackStack) ta.addToBackStack(tag);
		ta.commit();
	}

	public static void tryToStartActivity(Context ctx, Intent intent) {
		try {
			ctx.startActivity(intent);
		} catch (ActivityNotFoundException e) {
			Toast.makeText(ctx, R.string.error_start_activity, LENGTH_LONG)
					.show();
		}
	}

	public static String getContactDisplayName(Author author,
			@Nullable String alias) {
		String name = author.getName();
		if (alias == null) return name;
		else return String.format("%s (%s)", alias, name);
	}

	public static String getContactDisplayName(Contact c) {
		return getContactDisplayName(c.getAuthor(), c.getAlias());
	}

	public static void setError(TextInputLayout til, @Nullable String error,
			boolean set) {
		if (set) {
			if (til.getError() == null) til.setError(error);
		} else {
			til.setError(null);
		}
	}

	public static String formatDate(Context ctx, long time) {
		int flags = FORMAT_ABBREV_RELATIVE |
				FORMAT_SHOW_DATE | FORMAT_ABBREV_TIME | FORMAT_ABBREV_MONTH;

		long diff = System.currentTimeMillis() - time;
		if (diff < MIN_DATE_RESOLUTION) return ctx.getString(R.string.now);
		if (diff >= DAY_IN_MILLIS && diff < WEEK_IN_MILLIS) {
			return DateUtils.getRelativeDateTimeString(ctx, time,
					MIN_DATE_RESOLUTION, WEEK_IN_MILLIS, flags).toString();
		}
		return DateUtils.getRelativeTimeSpanString(time,
				System.currentTimeMillis(),
				MIN_DATE_RESOLUTION, flags).toString();
	}

	public static String formatDateAbsolute(Context ctx, long time) {
		int flags = FORMAT_SHOW_TIME | FORMAT_SHOW_DATE | FORMAT_ABBREV_ALL;
		long diff = System.currentTimeMillis() - time;
		if (diff >= YEAR_IN_MILLIS) flags |= FORMAT_SHOW_YEAR;
		return DateUtils.formatDateTime(ctx, time, flags);
	}

	public static String formatDateFull(Context ctx, long time) {
		return DateUtils.formatDateTime(ctx, time,
				FORMAT_SHOW_DATE | FORMAT_SHOW_YEAR | FORMAT_ABBREV_ALL);
	}

	public static String formatDuration(Context ctx, long millis) {
		Resources r = ctx.getResources();
		if (millis >= DAY_IN_MILLIS) {
			int days = (int) (millis / DAY_IN_MILLIS);
			int rest = (int) (millis % DAY_IN_MILLIS);
			String dayStr =
					r.getQuantityString(R.plurals.duration_days, days, days);
			if (rest < HOUR_IN_MILLIS / 2) return dayStr;
			else return dayStr + " " + formatDuration(ctx, rest);
		} else if (millis >= HOUR_IN_MILLIS) {
			int hours = (int) (millis / HOUR_IN_MILLIS);
			int rest = (int) (millis % HOUR_IN_MILLIS);
			String hourStr =
					r.getQuantityString(R.plurals.duration_hours, hours, hours);
			if (rest < MINUTE_IN_MILLIS / 2) return hourStr;
			else return hourStr + " " + formatDuration(ctx, rest);
		} else {
			int minutes =
					(int) ((millis + MINUTE_IN_MILLIS / 2) / MINUTE_IN_MILLIS);
			if (minutes < 1) minutes = 1;
			return r.getQuantityString(R.plurals.duration_minutes, minutes,
					minutes);
		}
	}

	public static long getDaysUntilExpiry() {
		long now = System.currentTimeMillis();
		return (EXPIRY_DATE - now) / DAYS.toMillis(1);
	}

	public static SpannableStringBuilder getTeaser(Context ctx, Spanned text) {
		if (text.length() < TEASER_LENGTH)
			throw new IllegalArgumentException(
					"String is shorter than TEASER_LENGTH");

		SpannableStringBuilder builder =
				new SpannableStringBuilder(text.subSequence(0, TEASER_LENGTH));
		String ellipsis = ctx.getString(R.string.ellipsis);
		builder.append(ellipsis).append(" ");

		Spannable readMore = new SpannableString(
				ctx.getString(R.string.read_more) + ellipsis);
		ForegroundColorSpan fg = new ForegroundColorSpan(
				ContextCompat.getColor(ctx, R.color.zerion_text_link));
		readMore.setSpan(fg, 0, readMore.length(),
				Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		builder.append(readMore);

		return builder;
	}

	public static Spanned getSpanned(@Nullable String s) {
		return HtmlCompat.fromHtml(s, HtmlCompat.FROM_HTML_MODE_LEGACY);
	}

	public static void makeLinksClickable(TextView v,
			Consumer<String> onLinkClicked) {
		SpannableStringBuilder ssb = new SpannableStringBuilder(v.getText());
		URLSpan[] spans = ssb.getSpans(0, ssb.length(), URLSpan.class);
		for (URLSpan span : spans) {
			int start = ssb.getSpanStart(span);
			int end = ssb.getSpanEnd(span);
			String url = span.getURL();
			ssb.removeSpan(span);
			ClickableSpan cSpan = new ClickableSpan() {
				@Override
				public void onClick(View v2) {
					onLinkClicked.accept(url);
				}
			};
			ssb.setSpan(cSpan, start, end, 0);
		}
		v.setText(ssb);
		v.setMovementMethod(ArticleMovementMethod.getInstance());
	}

	public static void onSingleLinkClick(TextView textView, Runnable runnable) {
		SpannableStringBuilder ssb =
				new SpannableStringBuilder(textView.getText());
		ClickableSpan[] spans =
				ssb.getSpans(0, ssb.length(), ClickableSpan.class);
		if (spans.length != 1) throw new AssertionError();
		ClickableSpan span = spans[0];
		int start = ssb.getSpanStart(span);
		int end = ssb.getSpanEnd(span);
		ssb.removeSpan(span);
		ClickableSpan cSpan = new ClickableSpan() {
			@Override
			public void onClick(View v) {
				runnable.run();
			}
		};
		ssb.setSpan(cSpan, start, end, 0);
		textView.setText(ssb);
		textView.setMovementMethod(new LinkMovementMethod());
	}

	public static void showOnboardingDialog(Context ctx, String text) {
		new MaterialAlertDialogBuilder(ctx, R.style.OnboardingDialogTheme)
				.setMessage(text)
				.setNeutralButton(R.string.got_it,
						(dialog, which) -> dialog.cancel())
				.show();
	}

	public static boolean isSamsung7() {
		return (SDK_INT == 24 || SDK_INT == 25) &&
				MANUFACTURER.equalsIgnoreCase("Samsung");
	}

	public static void setFilterTouchesWhenObscured(View v, boolean filter) {
		v.setFilterTouchesWhenObscured(filter);
		if (v.getFilterTouchesWhenObscured() != filter)
			v.setFilterTouchesWhenObscured(!filter);
	}

	public static void setTheme(Context ctx, String theme) {
		if (theme.equals(ctx.getString(R.string.pref_theme_light_value))) {
			setDefaultNightMode(MODE_NIGHT_NO);
		} else if (theme.equals(ctx.getString(R.string.pref_theme_dark_value))
				|| theme.equals(ctx.getString(
						R.string.pref_theme_amoled_value))) {
			setDefaultNightMode(MODE_NIGHT_YES);
		} else {
			setDefaultNightMode(MODE_NIGHT_FOLLOW_SYSTEM);
		}
	}

	public static boolean isAmoledTheme(String theme, Context ctx) {
		return theme.equals(ctx.getString(R.string.pref_theme_amoled_value));
	}

	public static int resolveAttribute(Context ctx, @AttrRes int attr) {
		TypedValue outValue = new TypedValue();
		ctx.getTheme().resolveAttribute(attr, outValue, true);
		return outValue.resourceId;
	}

	@ColorInt
	public static int resolveColorAttribute(Context ctx, @AttrRes int res) {
		@ColorRes
		int color = resolveAttribute(ctx, res);
		return ContextCompat.getColor(ctx, color);
	}

	public static boolean hasScreenLock(Context ctx) {
		return hasKeyguardLock(ctx) || hasUsableFingerprint(ctx);
	}

	public static boolean hasKeyguardLock(Context ctx) {
		KeyguardManager keyguardManager =
				(KeyguardManager) ctx.getSystemService(KEYGUARD_SERVICE);
		if (keyguardManager == null) return false;
		return (SDK_INT < 23 && keyguardManager.isKeyguardSecure()) ||
				(SDK_INT >= 23 && keyguardManager.isDeviceSecure());
	}

	public static boolean hasUsableFingerprint(Context ctx) {
		if (SDK_INT < 28) return false;
		FingerprintManagerCompat fm = FingerprintManagerCompat.from(ctx);
		return fm.hasEnrolledFingerprints() && fm.isHardwareDetected();
	}

	public static boolean enterPressed(int actionId,
			@Nullable KeyEvent keyEvent) {
		return actionId == IME_NULL &&
				keyEvent != null &&
				keyEvent.getAction() == ACTION_DOWN &&
				keyEvent.getKeyCode() == KEYCODE_ENTER;
	}

	public static void excludeSystemUi(Transition transition) {
		transition.excludeTarget(android.R.id.statusBarBackground, true);
		transition.excludeTarget(android.R.id.navigationBarBackground, true);
	}

	@UiThread
	public static <T> void observeOnce(LiveData<T> liveData,
			LifecycleOwner owner, Observer<T> observer) {
		liveData.observe(owner, new Observer<T>() {
			@Override
			public void onChanged(@Nullable T t) {
				observer.onChanged(t);
				liveData.removeObserver(this);
			}
		});
	}

	@UiThread
	public static <T> void observeForeverOnce(LiveData<T> liveData,
			Observer<T> observer) {
		liveData.observeForever(new Observer<T>() {
			@Override
			public void onChanged(@Nullable T t) {
				observer.onChanged(t);
				liveData.removeObserver(this);
			}
		});
	}

	public static boolean isRtl(Context ctx) {
		return ctx.getResources().getConfiguration().getLayoutDirection() ==
				LAYOUT_DIRECTION_RTL;
	}

	public static String getCountryDisplayName(String isoCode) {
		for (Locale locale : Locale.getAvailableLocales()) {
			if (locale.getCountry().equalsIgnoreCase(isoCode)) {
				return locale.getDisplayCountry();
			}
		}
		return isoCode;
	}

	public static Drawable getDialogIcon(Context ctx, @DrawableRes int resId) {
		Drawable icon =
				VectorDrawableCompat.create(ctx.getResources(), resId, null);
		setTint(requireNonNull(icon), getColor(ctx, R.color.color_primary));
		return icon;
	}

	public static void hideViewOnSmallScreen(View view) {
		boolean small = isSmallScreenRelativeToFontSize(view.getContext());
		view.setVisibility(small ? GONE : VISIBLE);
	}

	private static boolean isSmallScreenRelativeToFontSize(Context ctx) {
		Configuration config = ctx.getResources().getConfiguration();
		if (config.fontScale == 0f) return true;
		return config.screenHeightDp / config.fontScale < 600;
	}

	@AnyThread
	public static void handleException(Context context,
			AndroidExecutor androidExecutor, Exception e) {
		androidExecutor.runOnUiThread(() -> {
			String msg = "Error: " + e.getClass().getSimpleName();
			if (!StringUtils.isNullOrEmpty(e.getMessage())) {
				msg += " " + e.getMessage();
			}
			if (e.getCause() != null) {
				msg += " caused by " + e.getCause().getClass().getSimpleName();
			}
			Toast.makeText(context, msg, LENGTH_LONG).show();
		});
	}

	public static void setInputStateAlwaysVisible(Activity activity) {
		activity.getWindow().setSoftInputMode(SOFT_INPUT_ADJUST_RESIZE |
				SOFT_INPUT_STATE_ALWAYS_VISIBLE);
	}

	public static void setInputStateHidden(Activity activity) {
		activity.getWindow().setSoftInputMode(SOFT_INPUT_ADJUST_RESIZE |
				SOFT_INPUT_STATE_HIDDEN);
	}

	public static void launchActivityToOpenFile(Context ctx,
			ActivityResultLauncher<String[]> docLauncher,
			ActivityResultLauncher<String> contentLauncher,
			String contentType) {
		try {
			contentLauncher.launch(contentType);
			return;
		} catch (ActivityNotFoundException e) {

		}
		try {
			docLauncher.launch(new String[] {contentType});
			return;
		} catch (ActivityNotFoundException e) {

		}
		Toast.makeText(ctx, R.string.error_start_activity, LENGTH_LONG).show();
	}

}
