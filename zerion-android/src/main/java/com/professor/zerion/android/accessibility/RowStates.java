package com.professor.zerion.android.accessibility;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.view.Window;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.message.MessageState;
import com.professor.zerion.android.message.MessageStates;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

@UiThread
@NotNullByDefault
public final class RowStates {

	static final int MAX_ROWS = 256;
	static final long TOGETHER_MS = 1000L;
	private static final MessageState[] SAID_IN_ORDER = {
			MessageState.NOT_SENT, MessageState.WAITING, MessageState.SENT,
			MessageState.DELIVERED};

	private static final class Seen {

		@Nullable
		MessageState state;
		boolean failed;

		Seen(@Nullable MessageState state) {
			this.state = state;
			this.failed = state == MessageState.NOT_SENT;
		}
	}

	private final Map<String, Seen> rows =
			new LinkedHashMap<String, Seen>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(
						Map.Entry<String, Seen> eldest) {
					return size() > MAX_ROWS;
				}
			};

	private static final class Told {

		final MessageState state;
		final Supplier<? extends CharSequence> subject;

		Told(MessageState state, Supplier<? extends CharSequence> subject) {
			this.state = state;
			this.subject = subject;
		}
	}

	private final Map<String, Told> told = new LinkedHashMap<>();
	@Nullable
	private View speaker;
	@Nullable
	private View waitingOn;
	private final Runnable sayTogether = this::sayTogether;

	public static RowStates of(View v) {
		Activity activity = activity(v.getContext());
		Window window = activity == null ? null : activity.getWindow();
		if (window == null) return new RowStates();
		View decor = window.getDecorView();
		Object kept = decor.getTag(R.id.row_states);
		if (kept instanceof RowStates) return (RowStates) kept;
		RowStates states = new RowStates();
		states.speaker = decor;
		decor.setTag(R.id.row_states, states);
		if (activity instanceof LifecycleOwner) {
			((LifecycleOwner) activity).getLifecycle().addObserver(
					states.new Leaving());
		}
		return states;
	}

	@Nullable
	private static Activity activity(Context ctx) {
		Context c = ctx;
		while (c instanceof ContextWrapper) {
			if (c instanceof Activity) return (Activity) c;
			c = ((ContextWrapper) c).getBaseContext();
		}
		return null;
	}

	public void saw(TextView meta, String key, @Nullable MessageState state,
			Supplier<? extends CharSequence> subject) {
		MessageState now = change(key, state);
		if (now == null) {
			Told waiting = told.get(key);
			if (waiting != null && waiting.state != state) told.remove(key);
			return;
		}
		told.remove(key);
		told.put(key, new Told(now, subject));
		if (waitingOn != null) return;
		View on = speaker == null ? meta : speaker;
		waitingOn = on;
		on.postDelayed(sayTogether, TOGETHER_MS);
	}

	private final class Leaving implements LifecycleEventObserver {

		@Override
		public void onStateChanged(LifecycleOwner source,
				Lifecycle.Event event) {
			View on = waitingOn;
			if (event == Lifecycle.Event.ON_PAUSE) {
				if (on == null) return;
				on.removeCallbacks(sayTogether);
				sayTogether();
			} else if (event == Lifecycle.Event.ON_DESTROY) {
				if (on != null) on.removeCallbacks(sayTogether);
				waitingOn = null;
				told.clear();
				source.getLifecycle().removeObserver(this);
			}
		}
	}

	private void sayTogether() {
		View on = waitingOn;
		waitingOn = null;
		List<Told> all = new ArrayList<>(told.values());
		told.clear();
		if (on == null || all.isEmpty()) return;
		Context ctx = on.getContext();
		if (all.size() == 1) {
			Told one = all.get(0);
			String label = ctx.getString(one.state.getLabel());
			String about = MessageStates.subject(one.subject.get());
			Speech.say(on, about.isEmpty() ? label : ctx.getString(
					R.string.message_state_action_description, label, about));
			return;
		}
		for (MessageState state : SAID_IN_ORDER) {
			int n = 0;
			for (Told t : all) {
				if (t.state == state) n++;
			}
			if (n == 0) continue;
			Speech.say(on, state == MessageState.NOT_SENT
					? ctx.getResources().getQuantityString(
					R.plurals.message_state_could_not_send, n, n)
					: ctx.getString(state.getLabel()));
		}
	}

	@VisibleForTesting
	@Nullable
	MessageState change(String key, @Nullable MessageState state) {
		Seen seen = rows.get(key);
		if (seen == null) {
			rows.put(key, new Seen(state));
			return null;
		}
		MessageState was = seen.state;
		seen.state = state;
		if (state == was) return null;
		if (state == MessageState.NOT_SENT) {
			seen.failed = true;
			return was == MessageState.SENDING ? null : state;
		}
		if (seen.failed && (state == MessageState.SENT
				|| state == MessageState.WAITING
				|| state == MessageState.DELIVERED)) {
			seen.failed = false;
			return state;
		}
		return null;
	}
}
