package com.professor.zerion.android.grouptr;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.grouptr.GroupTrIncognitoNames;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrState;

import java.util.ArrayList;
import java.util.List;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupIncognitoChoiceTest {

	static {
		TestAndroidKeyStore.register();
	}

	public static class ProbeCreate extends GroupTrCreateActivity {

		static GroupTrManager manager;

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			groupTrManager = manager;
			ioExecutor = Runnable::run;
		}
	}

	private final List<String> answers = new ArrayList<>();

	@Test
	public void aJoinerPicksARandomNameAndCanDrawAnother() {
		AppCompatActivity a = host();
		GroupTrIncognitoPicker.askToJoin(a, "Club", true, answers::add);
		idle();
		AlertDialog d = latest();
		MaterialSwitch sw = d.findViewById(R.id.incognitoSwitch);
		assertNotNull(sw);
		assertEquals(a.getString(R.string.grouptr_incognito),
				sw.getText().toString());
		assertTrue(sw.isEnabled());
		assertFalse("off unless chosen", sw.isChecked());
		View row = d.findViewById(R.id.incognitoNameRow);
		assertEquals(View.GONE, row.getVisibility());

		sw.performClick();
		idle();
		assertEquals(View.VISIBLE, row.getVisibility());
		TextView shown = d.findViewById(R.id.incognitoName);
		String first = shown.getText().toString();
		for (int i = 0; i < 5 && shown.getText().toString().equals(first);
				i++) {
			d.findViewById(R.id.incognitoNewName).performClick();
		}
		String second = shown.getText().toString();
		assertFalse("a new name was drawn", second.equals(first));

		d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		idle();
		assertEquals(1, answers.size());
		String chosen = answers.get(0);
		assertTrue(chosen, GroupTrIncognitoNames.isIncognitoName(chosen));
		assertEquals(a.getString(R.string.grouptr_incognito_name, chosen),
				second);
	}

	@Test
	public void joiningWithoutIncognitoPassesNoName() {
		AppCompatActivity a = host();
		GroupTrIncognitoPicker.askToJoin(a, null, true, answers::add);
		idle();
		latest().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		idle();
		assertEquals(1, answers.size());
		assertNull(answers.get(0));
	}

	@Test
	public void anOlderInviterLeavesIncognitoOff() {
		AppCompatActivity a = host();
		GroupTrIncognitoPicker.askToJoin(a, null, false, answers::add);
		idle();
		AlertDialog d = latest();
		MaterialSwitch sw = d.findViewById(R.id.incognitoSwitch);
		assertFalse(sw.isEnabled());
		TextView summary = d.findViewById(R.id.incognitoSummary);
		assertEquals(a.getString(
				R.string.grouptr_member_invite_contact_outdated),
				summary.getText().toString());
		sw.performClick();
		d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		idle();
		assertEquals(1, answers.size());
		assertNull(answers.get(0));
	}

	@Test
	public void cancellingJoinsNothing() {
		AppCompatActivity a = host();
		GroupTrIncognitoPicker.askToJoin(a, null, true, answers::add);
		idle();
		latest().getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
		idle();
		assertTrue(answers.isEmpty());
	}

	@Test
	public void aGroupCreatedIncognitoUsesTheRandomName() throws Exception {
		GroupTrManager m = manager();
		ProbeCreate a = openCreate(m);
		TextInputEditText name = a.findViewById(R.id.groupNameInput);
		name.setText("Club");
		TextInputEditText nickname = a.findViewById(R.id.nicknameInput);
		nickname.setText("Carol");
		MaterialSwitch sw = a.findViewById(R.id.incognitoSwitch);
		sw.performClick();
		idle();
		assertFalse("the chosen name gives way to the random one",
				nickname.isEnabled());

		a.findViewById(R.id.createButton).performClick();
		idle();

		verify(m).createGroup(eq("Club"), org.mockito.ArgumentMatchers
				.argThat(GroupTrIncognitoNames::isIncognitoName));
		verify(m, never()).setStealthName(any(), anyString());
	}

	@Test
	public void aGroupCreatedNormallyKeepsTheChosenName() throws Exception {
		GroupTrManager m = manager();
		ProbeCreate a = openCreate(m);
		TextInputEditText name = a.findViewById(R.id.groupNameInput);
		name.setText("Club");
		TextInputEditText nickname = a.findViewById(R.id.nicknameInput);
		nickname.setText("Captain");

		a.findViewById(R.id.createButton).performClick();
		idle();

		verify(m).createGroup(eq("Club"), isNull());
		verify(m).setStealthName(any(), eq("Captain"));
	}

	private GroupTrManager manager() throws Exception {
		GroupTrManager m = mock(GroupTrManager.class);
		GroupTrState s = new GroupTrState(getRandomId(), "Club",
				new byte[32], new byte[32], "Carol", 0L, 0L, false,
				new ArrayList<>());
		when(m.createGroup(anyString(), any())).thenReturn(s);
		return m;
	}

	private ProbeCreate openCreate(GroupTrManager m) {
		ProbeCreate.manager = m;
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeCreate.class);
		ProbeCreate a = Robolectric.buildActivity(ProbeCreate.class, i)
				.setup().get();
		idle();
		return a;
	}

	private static AppCompatActivity host() {
		AppCompatActivity a = Robolectric.buildActivity(
				AppCompatActivity.class).setup().get();
		a.setTheme(R.style.ZerionTheme_NoActionBar);
		return a;
	}

	private static AlertDialog latest() {
		Dialog d = ShadowDialog.getLatestDialog();
		assertTrue(d.isShowing());
		return (AlertDialog) d;
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}
}
