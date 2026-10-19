package com.professor.zerion.android.accessibility;

import android.view.View;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Objects;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;

@UiThread
@NotNullByDefault
public final class Announcer {

	public static final long SETTLE_MS = 2_500L;

	private final long holdMs;
	private boolean primed = false;
	@Nullable
	private Object last = null;
	@Nullable
	private Object said = null;
	@Nullable
	private View waitingOn = null;
	@Nullable
	private Runnable waiting = null;

	public Announcer() {
		this(0L);
	}

	public Announcer(long holdMs) {
		this.holdMs = holdMs;
	}

	public boolean update(View v, @Nullable Object value, CharSequence text) {
		if (!primed) {
			primed = true;
			last = value;
			said = value;
			return false;
		}
		if (Objects.equals(last, value)) return false;
		last = value;
		stopWaiting();
		if (value == null || text.length() == 0) {
			said = value;
			return false;
		}
		if (holdMs <= 0L) {
			said = value;
			Speech.say(v, text);
			return true;
		}
		if (Objects.equals(said, value)) return false;
		String spoken = text.toString();
		Runnable later = () -> {
			waiting = null;
			waitingOn = null;
			if (!Objects.equals(last, value)) return;
			said = value;
			Speech.say(v, spoken);
		};
		waiting = later;
		waitingOn = v;
		v.postDelayed(later, holdMs);
		return true;
	}

	public void cancel() {
		stopWaiting();
		last = said;
	}

	private void stopWaiting() {
		View on = waitingOn;
		Runnable later = waiting;
		waitingOn = null;
		waiting = null;
		if (on != null && later != null) on.removeCallbacks(later);
	}
}
