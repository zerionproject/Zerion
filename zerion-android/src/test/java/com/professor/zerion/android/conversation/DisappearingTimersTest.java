package com.professor.zerion.android.conversation;

import android.app.Dialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.List;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.conversation.DisappearingTimers.DAY;
import static com.professor.zerion.android.conversation.DisappearingTimers.HOUR;
import static com.professor.zerion.android.conversation.DisappearingTimers.MAX_MS;
import static com.professor.zerion.android.conversation.DisappearingTimers.MINUTE;
import static com.professor.zerion.android.conversation.DisappearingTimers.MIN_MS;
import static com.professor.zerion.android.conversation.DisappearingTimers.WEEK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class DisappearingTimersTest {

	private final List<Long> chosen = new ArrayList<>();

	private static AppCompatActivity host() {
		AppCompatActivity a = Robolectric.buildActivity(
				AppCompatActivity.class).setup().get();
		a.setTheme(R.style.ZerionTheme_NoActionBar);
		return a;
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static AlertDialog latest() {
		Dialog d = ShadowDialog.getLatestDialog();
		assertTrue(d.isShowing());
		return (AlertDialog) d;
	}

	private static void collect(View v, List<NumberPicker> out) {
		if (v instanceof NumberPicker) out.add((NumberPicker) v);
		else if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				collect(g.getChildAt(i), out);
			}
		}
	}

	private static List<NumberPicker> pickers(AlertDialog d) {
		List<NumberPicker> out = new ArrayList<>();
		collect(d.getWindow().getDecorView(), out);
		assertEquals(2, out.size());
		return out;
	}

	@Test
	public void anyTimerFromOneMinuteToAYearIsAccepted() {
		assertTrue(DisappearingTimers.isValid(MIN_MS));
		assertTrue(DisappearingTimers.isValid(3 * DAY));
		assertTrue(DisappearingTimers.isValid(MAX_MS));
		assertFalse(DisappearingTimers.isValid(MIN_MS - 1));
		assertFalse(DisappearingTimers.isValid(MAX_MS + 1));
		assertFalse(DisappearingTimers.isValid(-1));
	}

	@Test
	public void aCustomTimerKeepsItsOwnLabel() {
		AppCompatActivity a = host();
		assertEquals("3 days", DisappearingTimers.label(a, 3 * DAY));
		assertEquals("5 minutes", DisappearingTimers.label(a, 5 * MINUTE));
		assertEquals("2 hours", DisappearingTimers.label(a, 2 * HOUR));
		assertEquals("1 week", DisappearingTimers.label(a, WEEK));
		assertEquals("2 weeks", DisappearingTimers.label(a, 2 * WEEK));
		assertEquals(a.getString(R.string.off), DisappearingTimers.label(a, -1));
	}

	@Test
	public void thePickerStartsFromTheCurrentTimer() {
		assertEquals(DisappearingTimers.Unit.DAYS,
				DisappearingTimers.unitFor(3 * DAY));
		assertEquals(3, DisappearingTimers.amountFor(3 * DAY,
				DisappearingTimers.Unit.DAYS));
		assertEquals(DisappearingTimers.Unit.WEEKS,
				DisappearingTimers.unitFor(2 * WEEK));
		assertEquals(DisappearingTimers.Unit.HOURS,
				DisappearingTimers.unitFor(6 * HOUR));
		assertEquals(DisappearingTimers.Unit.MINUTES,
				DisappearingTimers.unitFor(45 * MINUTE));
		assertEquals(DisappearingTimers.Unit.DAYS,
				DisappearingTimers.unitFor(365 * DAY));
		assertEquals(DisappearingTimers.Unit.HOURS,
				DisappearingTimers.unitFor(90 * MINUTE));
		assertEquals(1, DisappearingTimers.amountFor(90 * MINUTE,
				DisappearingTimers.Unit.HOURS));
		assertEquals(2 * WEEK, DisappearingTimers.toMillis(2,
				DisappearingTimers.Unit.WEEKS));
	}

	@Test
	public void aCustomTimerIsShownAsCustomAndKeptOnOk() {
		AppCompatActivity a = host();
		DisappearingTimers.showChatTimerDialog(a, 3 * DAY, chosen::add);
		idle();
		AlertDialog list = latest();
		RadioGroup g = list.findViewById(R.id.disappearing_messages_radio_group);
		assertEquals(R.id.timer_custom, g.getCheckedRadioButtonId());
		RadioButton custom = list.findViewById(R.id.timer_custom);
		assertEquals("Custom: 3 days", custom.getText().toString());

		list.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();
		AlertDialog picker = latest();
		assertNotSame(list, picker);
		picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();
		assertEquals(1, chosen.size());
		assertEquals(3 * DAY, (long) chosen.get(0));
	}

	@Test
	public void aPresetStaysAPresetAndOffStaysOff() {
		AppCompatActivity a = host();
		DisappearingTimers.showChatTimerDialog(a, WEEK, chosen::add);
		idle();
		AlertDialog d = latest();
		RadioGroup g = d.findViewById(R.id.disappearing_messages_radio_group);
		assertEquals(R.id.timer_1_week, g.getCheckedRadioButtonId());
		d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();

		DisappearingTimers.showChatTimerDialog(a, -1L, chosen::add);
		idle();
		AlertDialog off = latest();
		RadioGroup g2 = off.findViewById(R.id.disappearing_messages_radio_group);
		assertEquals(R.id.timer_off, g2.getCheckedRadioButtonId());
		off.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();
		assertEquals(2, chosen.size());
		assertEquals(WEEK, (long) chosen.get(0));
		assertEquals(-1L, (long) chosen.get(1));
	}

	@Test
	public void thePickerReturnsTheChosenAmountAndUnit() {
		AppCompatActivity a = host();
		DisappearingTimers.showCustomPicker(a, -1L, chosen::add);
		idle();
		AlertDialog d = latest();
		List<NumberPicker> p = pickers(d);
		assertEquals(1, p.get(0).getValue());
		assertEquals(DisappearingTimers.Unit.DAYS.ordinal(), p.get(1).getValue());
		p.get(0).setValue(3);
		d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();

		DisappearingTimers.showCustomPicker(a, -1L, chosen::add);
		idle();
		AlertDialog w = latest();
		List<NumberPicker> q = pickers(w);
		q.get(1).setValue(DisappearingTimers.Unit.WEEKS.ordinal());
		q.get(0).setValue(2);
		w.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();

		assertEquals(3 * DAY, (long) chosen.get(0));
		assertEquals(2 * WEEK, (long) chosen.get(1));
	}

	@Test
	public void cancellingThePickerChangesNothing() {
		AppCompatActivity a = host();
		DisappearingTimers.showCustomPicker(a, 3 * DAY, chosen::add);
		idle();
		latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
		idle();
		assertTrue(chosen.isEmpty());
		assertTrue(RuntimeEnvironment.getApplication() != null);
	}
}
