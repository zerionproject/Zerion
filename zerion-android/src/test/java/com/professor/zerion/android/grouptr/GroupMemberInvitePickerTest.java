package com.professor.zerion.android.grouptr;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupMemberInvitePickerTest {

	static {
		TestAndroidKeyStore.register();
	}

	public static class ProbePicker extends GroupTrInviteMembersActivity {

		static GroupTrManager manager;
		static ContactManager contacts;

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			groupTrManager = manager;
			contactManager = contacts;
			ioExecutor = Runnable::run;
		}
	}

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] memberKey = key((byte) 2);
	private final Contact current = contact(10, "Xavi", (byte) 3);
	private final Contact older = contact(11, "Olga", (byte) 4);
	private final Contact creator = contact(12, "Carol", (byte) 1);

	@Test
	public void contactsOnAnOlderAppCannotBeChosenAndTheOthersAreInvited()
			throws Exception {
		GroupTrManager m = mock(GroupTrManager.class);
		when(m.getGroup(any())).thenReturn(state());
		when(m.canReceiveMemberInvite(current.getId())).thenReturn(true);
		when(m.canReceiveMemberInvite(older.getId())).thenReturn(true);
		when(m.canReceiveMemberInvite(groupId, current.getId()))
				.thenReturn(true);
		when(m.canReceiveMemberInvite(groupId, older.getId()))
				.thenReturn(false);
		ContactManager cm = mock(ContactManager.class);
		List<Contact> all = new ArrayList<>();
		all.add(current);
		all.add(older);
		all.add(creator);
		when(cm.getContacts()).thenReturn(all);
		ProbePicker.manager = m;
		ProbePicker.contacts = cm;
		Intent i = GroupTrInviteMembersActivity.memberIntent(
				ApplicationProvider.getApplicationContext(), groupId);
		i.setClass(ApplicationProvider.getApplicationContext(),
				ProbePicker.class);
		ProbePicker a = Robolectric.buildActivity(ProbePicker.class, i)
				.setup().get();
		shadowOf(Looper.getMainLooper()).idle();

		View root = a.getWindow().getDecorView();
		assertNull("the creator is not offered", findText(root, "Carol"));
		assertNotNull(findText(root, a.getString(
				R.string.grouptr_member_invite_contact_outdated)));
		CheckBox olderBox = checkBoxOf(findText(root, "Olga"));
		assertFalse(olderBox.isEnabled());
		CheckBox currentBox = checkBoxOf(findText(root, "Xavi"));
		assertTrue(currentBox.isEnabled());

		currentBox.setChecked(true);
		FloatingActionButton fab = findFab(root);
		assertNotNull(fab);
		fab.performClick();
		shadowOf(Looper.getMainLooper()).idle();

		verify(m).inviteContactAsMember(eq(groupId), eq(current.getId()));
		verify(m, never()).inviteContactAsMember(any(), eq(older.getId()));
		verify(m, never()).inviteContactToGroup(any(), any(), any(), any());
	}

	private static CheckBox checkBoxOf(@Nullable View label) {
		assertNotNull(label);
		View row = (View) label.getParent().getParent();
		CheckBox box = findCheckBox(row);
		assertNotNull(box);
		return box;
	}

	@Nullable
	private static CheckBox findCheckBox(View v) {
		if (v instanceof CheckBox) return (CheckBox) v;
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int k = 0; k < g.getChildCount(); k++) {
				CheckBox c = findCheckBox(g.getChildAt(k));
				if (c != null) return c;
			}
		}
		return null;
	}

	@Nullable
	private static FloatingActionButton findFab(View v) {
		if (v instanceof FloatingActionButton) {
			return (FloatingActionButton) v;
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int k = 0; k < g.getChildCount(); k++) {
				FloatingActionButton f = findFab(g.getChildAt(k));
				if (f != null) return f;
			}
		}
		return null;
	}

	@Nullable
	private static View findText(View v, String text) {
		if (v instanceof TextView && !(v instanceof CheckBox)
				&& text.equals(((TextView) v).getText().toString())) {
			return v;
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int k = 0; k < g.getChildCount(); k++) {
				View found = findText(g.getChildAt(k), text);
				if (found != null) return found;
			}
		}
		return null;
	}

	private GroupTrState state() {
		List<GroupTrMember> members = new ArrayList<>();
		members.add(new GroupTrMember(creatorKey, "Carol", 0L, 0L,
				MemberRole.CREATOR));
		members.add(new GroupTrMember(memberKey, "Mia", 0L, 1L));
		return new GroupTrState(groupId, "Club", new byte[32], creatorKey,
				"Carol", 0L, 1L, false, members);
	}

	private static Contact contact(int id, String name, byte b) {
		Author a = new Author(new AuthorId(getRandomId()), 1, name,
				new SignaturePublicKey(key(b)));
		return new Contact(new ContactId(id), a, new AuthorId(getRandomId()),
				null, null, true);
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
