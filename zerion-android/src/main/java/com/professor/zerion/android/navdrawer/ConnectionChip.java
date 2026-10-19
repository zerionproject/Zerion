package com.professor.zerion.android.navdrawer;

import android.content.Context;
import android.content.res.ColorStateList;

import com.google.android.material.button.MaterialButton;
import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.plugin.Plugin;

import javax.annotation.Nullable;

import androidx.annotation.AttrRes;
import androidx.annotation.StringRes;

import static com.professor.zerion.android.util.UiUtils.resolveColorAttribute;

@NotNullByDefault
final class ConnectionChip {

	enum State {CONNECTED, CONNECTING, OFFLINE, PAUSED, CLOCK}

	static final int ICON_PADDING_DP = 6;
	static final int TITLE_MIN_DP = 64;
	static final int CHIP_MIN_DP = 48;

	private ConnectionChip() {
	}

	static State decide(boolean offlineMode, boolean paused,
			@Nullable Plugin.State tor, boolean internet, boolean published,
			boolean clockSkewed, boolean entryRelayFailing,
			int bootstrapPercent) {
		if (offlineMode) return State.OFFLINE;
		if (paused) return State.PAUSED;
		if (!internet) return State.OFFLINE;
		if (tor == null) return State.CONNECTING;
		switch (TorStatusDecider.decide(false, tor, true, published,
				clockSkewed, entryRelayFailing, bootstrapPercent)) {
			case CONNECTED:
			case ENTRY_RELAY_FAILING:
				return State.CONNECTED;
			case DISABLED:
			case OFFLINE_MODE:
				return State.OFFLINE;
			case CLOCK_SKEW:
				return State.CLOCK;
			default:
				return State.CONNECTING;
		}
	}

	@StringRes
	static int label(State state) {
		switch (state) {
			case CONNECTED:
				return R.string.connected;
			case OFFLINE:
				return R.string.offline;
			case PAUSED:
				return R.string.connection_chip_paused;
			case CLOCK:
				return R.string.connection_chip_clock;
			default:
				return R.string.connecting;
		}
	}

	@AttrRes
	static int color(State state) {
		switch (state) {
			case CONNECTED:
				return R.attr.zerionColorSuccess;
			case OFFLINE:
			case PAUSED:
				return R.attr.zerionColorStatusMuted;
			default:
				return R.attr.zerionColorWarning;
		}
	}

	static void bind(MaterialButton chip, State state, boolean compact) {
		Context ctx = chip.getContext();
		String text = ctx.getString(label(state));
		int tint = resolveColorAttribute(ctx, color(state));
		chip.setText(compact ? "" : text);
		chip.setIconPadding(compact ? 0 : iconPadding(ctx));
		chip.setTextColor(tint);
		chip.setIconTint(ColorStateList.valueOf(tint));
		chip.setContentDescription(ctx.getString(
				R.string.connection_chip_description, text));
	}

	static int widthWithText(MaterialButton chip, State state) {
		Context ctx = chip.getContext();
		String text = ctx.getString(label(state));
		return chip.getPaddingStart() + chip.getPaddingEnd()
				+ chip.getIconSize() + iconPadding(ctx)
				+ (int) Math.ceil(chip.getPaint().measureText(text));
	}

	static boolean showsText(int barWidth, int othersWidth,
			int chipWithText, float titleWidth) {
		return barWidth - othersWidth - chipWithText >= titleWidth;
	}

	static boolean folds(State state, boolean wordsFit) {
		return state == State.CONNECTED && !wordsFit;
	}

	static int maxWidth(int barWidth, int othersWidth, float density) {
		int room = barWidth - othersWidth - Math.round(TITLE_MIN_DP * density);
		return Math.max(Math.round(CHIP_MIN_DP * density), room);
	}

	private static int iconPadding(Context ctx) {
		return Math.round(ICON_PADDING_DP
				* ctx.getResources().getDisplayMetrics().density);
	}
}
