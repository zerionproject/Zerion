package com.professor.zerion.android.conversation;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.autodelete.AutoDeleteConstants;

import java.util.function.LongConsumer;

import javax.annotation.Nullable;

import static com.professor.zerion.android.util.UiUtils.formatDuration;

@NotNullByDefault
public final class DisappearingTimers {

	static final long MINUTE = 60_000L;
	static final long HOUR = 60L * MINUTE;
	static final long DAY = 24L * HOUR;
	static final long WEEK = 7L * DAY;
	public static final long MIN_MS =
			AutoDeleteConstants.MIN_AUTO_DELETE_TIMER_MS;
	public static final long MAX_MS =
			AutoDeleteConstants.MAX_AUTO_DELETE_TIMER_MS;

	enum Unit {
		MINUTES(MINUTE, 59, R.plurals.disappearing_unit_minutes),
		HOURS(HOUR, 23, R.plurals.disappearing_unit_hours),
		DAYS(DAY, 365, R.plurals.disappearing_unit_days),
		WEEKS(WEEK, 52, R.plurals.disappearing_unit_weeks);

		final long millis;
		final int max;
		final int label;

		Unit(long millis, int max, int label) {
			this.millis = millis;
			this.max = max;
			this.label = label;
		}
	}

	private static final int[] CHAT_RADIO_IDS = {
			R.id.timer_5_minutes, R.id.timer_30_minutes, R.id.timer_1_hour,
			R.id.timer_8_hours, R.id.timer_12_hours, R.id.timer_24_hours,
			R.id.timer_1_week, R.id.timer_4_weeks
	};
	private static final long[] CHAT_RADIO_VALUES = {
			5L * MINUTE, 30L * MINUTE, HOUR, 8L * HOUR, 12L * HOUR, DAY,
			WEEK, 4L * WEEK
	};

	private DisappearingTimers() {
	}

	public static boolean isValid(long ms) {
		return ms >= MIN_MS && ms <= MAX_MS;
	}

	static long toMillis(int amount, Unit unit) {
		return amount * unit.millis;
	}

	static Unit unitFor(long ms) {
		if (ms % WEEK == 0 && ms / WEEK <= Unit.WEEKS.max) return Unit.WEEKS;
		if (ms % DAY == 0 && ms / DAY <= Unit.DAYS.max) return Unit.DAYS;
		if (ms % HOUR == 0 && ms / HOUR <= Unit.HOURS.max) return Unit.HOURS;
		if (ms % MINUTE == 0 && ms / MINUTE <= Unit.MINUTES.max) {
			return Unit.MINUTES;
		}
		if (ms >= DAY) return Unit.DAYS;
		if (ms >= HOUR) return Unit.HOURS;
		return Unit.MINUTES;
	}

	static int amountFor(long ms, Unit unit) {
		long amount = Math.max(1L, ms / unit.millis);
		return (int) Math.min(amount, unit.max);
	}

	public static String label(Context ctx, long ms) {
		if (ms <= 0) return ctx.getString(R.string.off);
		if (ms % WEEK == 0) {
			int weeks = (int) Math.min(ms / WEEK, Integer.MAX_VALUE);
			return ctx.getResources().getQuantityString(
					R.plurals.duration_weeks, weeks, weeks);
		}
		return formatDuration(ctx, ms);
	}

	public static void showChatTimerDialog(Activity activity,
			@Nullable Long current, LongConsumer onChosen) {
		View view = activity.getLayoutInflater().inflate(
				R.layout.dialog_disappearing_messages, null);
		RadioGroup group = view.findViewById(
				R.id.disappearing_messages_radio_group);
		RadioButton custom = view.findViewById(R.id.timer_custom);
		long cur = current == null ? -1L : current;
		int checked = R.id.timer_off;
		if (cur > 0) {
			checked = R.id.timer_custom;
			for (int i = 0; i < CHAT_RADIO_VALUES.length; i++) {
				if (CHAT_RADIO_VALUES[i] == cur) checked = CHAT_RADIO_IDS[i];
			}
		}
		if (checked == R.id.timer_custom) {
			custom.setText(activity.getString(
					R.string.disappearing_custom_with_value,
					label(activity, cur)));
		}
		group.check(checked);
		new SecureAlertDialogBuilder(activity)
				.setView(view)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					int id = group.getCheckedRadioButtonId();
					if (id == R.id.timer_custom) {
						showCustomPicker(activity, cur, onChosen);
						return;
					}
					long value = -1L;
					for (int i = 0; i < CHAT_RADIO_IDS.length; i++) {
						if (CHAT_RADIO_IDS[i] == id) value = CHAT_RADIO_VALUES[i];
					}
					onChosen.accept(value);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	public static void showCustomPicker(Context ctx, long current,
			LongConsumer onChosen) {
		Unit startUnit = isValid(current) ? unitFor(current) : Unit.DAYS;
		int startAmount = isValid(current) ? amountFor(current, startUnit) : 1;
		Unit[] units = Unit.values();

		NumberPicker amount = new NumberPicker(ctx);
		amount.setMinValue(1);
		amount.setMaxValue(startUnit.max);
		amount.setValue(startAmount);
		amount.setWrapSelectorWheel(false);

		NumberPicker unit = new NumberPicker(ctx);
		unit.setMinValue(0);
		unit.setMaxValue(units.length - 1);
		unit.setDisplayedValues(unitLabels(ctx, startAmount));
		unit.setValue(startUnit.ordinal());
		unit.setWrapSelectorWheel(false);
		unit.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);

		TextView preview = new TextView(ctx);
		preview.setGravity(Gravity.CENTER);
		Runnable update = () -> preview.setText(ctx.getString(
				R.string.disappearing_custom_preview, label(ctx,
						toMillis(amount.getValue(), units[unit.getValue()]))));
		amount.setOnValueChangedListener((p, o, n) -> {
			unit.setDisplayedValues(unitLabels(ctx, n));
			update.run();
		});
		unit.setOnValueChangedListener((p, o, n) -> {
			amount.setMaxValue(units[n].max);
			unit.setDisplayedValues(unitLabels(ctx, amount.getValue()));
			update.run();
		});
		update.run();

		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER);
		row.addView(amount);
		row.addView(unit);
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);
		int pad = Math.round(16 * ctx.getResources().getDisplayMetrics()
				.density);
		box.setPadding(pad, pad, pad, 0);
		box.addView(row);
		box.addView(preview);

		new SecureAlertDialogBuilder(ctx)
				.setTitle(R.string.disappearing_custom_title)
				.setView(box)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					amount.clearFocus();
					long ms = toMillis(amount.getValue(),
							units[unit.getValue()]);
					if (isValid(ms)) onChosen.accept(ms);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private static String[] unitLabels(Context ctx, int amount) {
		Unit[] units = Unit.values();
		String[] out = new String[units.length];
		for (int i = 0; i < units.length; i++) {
			out[i] = ctx.getResources().getQuantityString(units[i].label,
					amount);
		}
		return out;
	}
}
