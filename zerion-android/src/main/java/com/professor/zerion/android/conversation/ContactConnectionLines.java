package com.professor.zerion.android.conversation;

import android.content.res.Resources;
import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;

import com.professor.zerion.R;

import org.zerionproject.core.api.connection.ContactConnectionSnapshot;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Reach;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Tor;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import androidx.annotation.StringRes;

import static java.util.Collections.unmodifiableList;
import static org.zerionproject.core.api.connection.ContactConnectionSnapshot.UNKNOWN;

@Immutable
@NotNullByDefault
final class ContactConnectionLines {

	@Immutable
	@NotNullByDefault
	static final class Line {

		@StringRes
		final int text;
		final long durationMs;
		private final long[] numbers;

		private Line(@StringRes int text, long durationMs, long... numbers) {
			this.text = text;
			this.durationMs = durationMs;
			this.numbers = numbers.clone();
		}

		static Line of(@StringRes int text) {
			return new Line(text, UNKNOWN);
		}

		static Line after(@StringRes int text, long durationMs) {
			return new Line(text, Math.max(0, durationMs));
		}

		static Line counting(@StringRes int text, long... numbers) {
			return new Line(text, UNKNOWN, numbers);
		}

		long[] getNumbers() {
			return numbers.clone();
		}

		@Override
		public boolean equals(@Nullable Object o) {
			if (!(o instanceof Line)) return false;
			Line l = (Line) o;
			return text == l.text && durationMs == l.durationMs
					&& Arrays.equals(numbers, l.numbers);
		}

		@Override
		public int hashCode() {
			return 31 * text + Arrays.hashCode(numbers);
		}
	}

	private ContactConnectionLines() {
	}

	static List<Line> lines(ContactConnectionSnapshot s, boolean published) {
		List<Line> out = new ArrayList<>();
		out.add(Line.of(tor(s.getTor())));
		if (s.getTor().isUp()) {
			out.add(Line.of(published ? R.string.contact_connection_published
					: R.string.contact_connection_publishing));
		}
		out.add(reach(s));
		if (s.getReach() != Reach.ONLINE) {
			long ago = s.getLastConnectedAgoMs();
			out.add(ago == UNKNOWN ? Line.of(R.string.contact_connection_never)
					: Line.after(R.string.contact_connection_last, ago));
		}
		Line peer = peerAddress(s);
		if (peer != null) out.add(peer);
		Line own = ownRotation(s);
		if (own != null) out.add(own);
		out.add(authorization(s));
		if (s.getOwnGeneration() > 0 || s.getContactGeneration() > 0) {
			out.add(Line.counting(R.string.contact_connection_generation,
					s.getOwnGeneration(), s.getContactGeneration()));
		}
		return unmodifiableList(out);
	}

	@StringRes
	private static int tor(Tor tor) {
		switch (tor) {
			case CONNECTED:
				return R.string.contact_connection_tor_connected;
			case NO_INTERNET:
				return R.string.contact_connection_tor_no_internet;
			case CLOCK_WRONG:
				return R.string.contact_connection_tor_clock;
			case ENTRY_RELAY_FAILING:
				return R.string.contact_connection_tor_entry_relay_now;
			case OFF:
				return R.string.contact_connection_tor_off;
			case PAUSED:
				return R.string.contact_connection_tor_paused;
			case OFFLINE_MODE:
				return R.string.contact_connection_tor_offline_mode;
			default:
				return R.string.contact_connection_tor_connecting;
		}
	}

	private static Line reach(ContactConnectionSnapshot s) {
		switch (s.getReach()) {
			case ONLINE:
				return Line.of(R.string.contact_connection_online);
			case DIALING:
				return Line.of(R.string.contact_connection_dialing);
			case RETRY_LATER:
				return Line.after(R.string.contact_connection_retry,
						s.getNextAttemptInMs());
			case WAITING_FOR_CONTACT:
				return Line.of(R.string.contact_connection_incoming);
			case NO_ADDRESS:
				return Line.of(R.string.contact_connection_no_address);
			case NEEDS_TOR:
				return Line.of(R.string.contact_connection_needs_tor);
			default:
				return Line.of(R.string.contact_connection_retry_soon);
		}
	}

	@Nullable
	private static Line peerAddress(ContactConnectionSnapshot s) {
		switch (s.getPeerAddress()) {
			case NEW_ANNOUNCED:
				return Line.of(R.string.contact_connection_peer_new);
			case NEW_RETRY_LATER:
				return Line.after(R.string.contact_connection_peer_retry,
						s.getPeerRetryInMs());
			default:
				return null;
		}
	}

	@Nullable
	private static Line ownRotation(ContactConnectionSnapshot s) {
		switch (s.getOwnRotation()) {
			case PREPARING:
				return Line.of(R.string.contact_connection_rotation_preparing);
			case CONTACT_MOVED:
				return Line.of(R.string.contact_connection_rotation_moved);
			case CONTACT_NOT_MOVED:
				return Line.of(R.string.contact_connection_rotation_waiting);
			default:
				return null;
		}
	}

	private static Line authorization(ContactConnectionSnapshot s) {
		switch (s.getAuthorization()) {
			case ACTIVE:
				return Line.of(R.string.contact_connection_auth_active);
			case SETTING_UP:
				return Line.of(R.string.contact_connection_auth_setup);
			case AWAITING_CONFIRMATION:
				return Line.of(R.string.contact_connection_auth_confirm);
			case REPAIRING:
				return Line.counting(R.string.contact_connection_auth_repair,
						s.getRepairAttempts());
			case RENEWING:
				return Line.of(R.string.contact_connection_auth_renewing);
			case REACHING:
				return Line.counting(R.string.contact_connection_auth_reaching,
						s.getRepairAttempts());
			case REVOKED:
				return Line.of(R.string.contact_connection_auth_revoked);
			default:
				return Line.of(R.string.contact_connection_auth_unsupported);
		}
	}

	static String format(Resources r, Line line) {
		if (line.durationMs != UNKNOWN) {
			return r.getString(line.text, duration(locale(r), line.durationMs));
		}
		long[] numbers = line.getNumbers();
		Object[] args = new Object[numbers.length];
		for (int i = 0; i < numbers.length; i++) args[i] = numbers[i];
		return r.getString(line.text, args);
	}

	static String duration(Locale locale, long ms) {
		MeasureFormat f = MeasureFormat.getInstance(locale,
				MeasureFormat.FormatWidth.WIDE);
		long seconds = Math.max(0, ms) / 1000;
		long rounded = Math.max(5, (seconds + 2) / 5 * 5);
		if (rounded < 60) {
			return f.format(new Measure(rounded, MeasureUnit.SECOND));
		}
		long minutes = Math.max(1, (seconds + 30) / 60);
		if (minutes < 60) {
			return f.format(new Measure(minutes, MeasureUnit.MINUTE));
		}
		return f.format(new Measure((minutes + 30) / 60, MeasureUnit.HOUR));
	}

	private static Locale locale(Resources r) {
		return r.getConfiguration().getLocales().get(0);
	}
}
