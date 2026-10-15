package com.professor.zerion.android.grouptr;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberInviteAvailability;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.SignaturePrivateKey;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupMemberInviteAdminTest {

	static {
		TestAndroidKeyStore.register();
	}

	public static class ProbeAdmin extends GroupTrAdminActivity {

		static GroupTrManager manager;
		static IdentityManager identity;

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			groupTrManager = manager;
			identityManager = identity;
			contactManager = mock(ContactManager.class);
			ioExecutor = Runnable::run;
		}
	}

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] memberKey = key((byte) 2);

	@Test
	public void theCreatorTurnsMemberInvitesOnWithALabelledSwitch()
			throws Exception {
		GroupTrManager m = manager(MemberInviteAvailability.UNAVAILABLE, 0L);
		ProbeAdmin a = open(m, creatorKey);

		View row = a.findViewById(R.id.rowMembersMayInvite);
		MaterialSwitch sw = a.findViewById(R.id.membersMayInviteSwitch);
		assertEquals(View.VISIBLE, row.getVisibility());
		assertFalse("off by default", sw.isChecked());
		assertEquals(a.getString(R.string.grouptr_members_may_invite),
				sw.getText().toString());
		assertEquals(View.GONE,
				a.findViewById(R.id.actionInviteContacts).getVisibility());

		sw.performClick();
		shadowOf(Looper.getMainLooper()).idle();
		verify(m).setMembersMayInvite(groupId, true);
	}

	@Test
	public void theSwitchShowsTheSettingInForce() throws Exception {
		ProbeAdmin a = open(manager(MemberInviteAvailability.UNAVAILABLE, 1L),
				creatorKey);
		MaterialSwitch sw = a.findViewById(R.id.membersMayInviteSwitch);
		assertTrue(sw.isChecked());
	}

	@Test
	public void aMemberWhoMayInviteOpensThePickerInMemberMode()
			throws Exception {
		ProbeAdmin a = open(manager(MemberInviteAvailability.ALLOWED, 1L),
				memberKey);

		assertEquals(View.GONE,
				a.findViewById(R.id.rowMembersMayInvite).getVisibility());
		View action = a.findViewById(R.id.actionInviteContacts);
		assertEquals(View.VISIBLE, action.getVisibility());
		assertTrue(action.isEnabled());
		assertEquals(View.GONE,
				a.findViewById(R.id.actionInviteContactsHint).getVisibility());

		drainStarted(a);
		action.performClick();
		Intent next = shadowOf(a).getNextStartedActivity();
		assertNotNull(next);
		assertEquals(GroupTrInviteMembersActivity.class.getName(),
				next.getComponent().getClassName());
		assertTrue(next.getBooleanExtra("memberMode", false));
	}

	@Test
	public void aMemberWithoutTheCreatorAsContactSeesWhyItCannotInvite()
			throws Exception {
		ProbeAdmin a = open(manager(
				MemberInviteAvailability.CREATOR_NOT_A_CONTACT, 1L), memberKey);

		View action = a.findViewById(R.id.actionInviteContacts);
		assertEquals(View.VISIBLE, action.getVisibility());
		assertFalse(action.isEnabled());
		TextView hint = a.findViewById(R.id.actionInviteContactsHint);
		assertEquals(View.VISIBLE, hint.getVisibility());
		assertEquals(a.getString(
				R.string.grouptr_member_invite_needs_creator_contact),
				hint.getText().toString());
		drainStarted(a);
		action.performClick();
		assertEquals(null, shadowOf(a).getNextStartedActivity());
	}

	private static void drainStarted(ProbeAdmin a) {
		while (shadowOf(a).getNextStartedActivity() != null) {
			shadowOf(Looper.getMainLooper()).idle();
		}
	}

	@Test
	public void aCreatorOnAnOlderAppIsExplained() throws Exception {
		ProbeAdmin a = open(manager(
				MemberInviteAvailability.CREATOR_ON_OLDER_VERSION, 1L),
				memberKey);

		TextView hint = a.findViewById(R.id.actionInviteContactsHint);
		assertEquals(a.getString(
				R.string.grouptr_member_invite_creator_outdated),
				hint.getText().toString());
		assertFalse(a.findViewById(R.id.actionInviteContacts).isEnabled());
	}

	@Test
	public void aMemberWithoutTheRightSeesThatOnlyTheCreatorAddsMembers()
			throws Exception {
		ProbeAdmin a = open(manager(MemberInviteAvailability.NOT_ENABLED, 0L),
				memberKey);

		View action = a.findViewById(R.id.actionInviteContacts);
		assertEquals(View.VISIBLE, action.getVisibility());
		assertFalse(action.isEnabled());
		TextView hint = a.findViewById(R.id.actionInviteContactsHint);
		assertEquals(View.VISIBLE, hint.getVisibility());
		assertEquals(a.getString(
				R.string.grouptr_member_invite_creator_only),
				hint.getText().toString());
		assertEquals(View.GONE,
				a.findViewById(R.id.rowMembersMayInvite).getVisibility());
		assertEquals(null, visibleGrantSwitch(a));
	}

	@Test
	public void theCreatorGivesOneMemberTheAddRightWithALabelledSwitch()
			throws Exception {
		GroupTrManager m = manager(MemberInviteAvailability.UNAVAILABLE, 0L);
		when(m.canReceiveInviterGrant(any(), any())).thenReturn(true);
		ProbeAdmin a = open(m, creatorKey);

		MaterialSwitch sw = visibleGrantSwitch(a);
		assertNotNull(sw);
		assertEquals(a.getString(R.string.grouptr_member_may_add),
				sw.getText().toString());
		assertTrue(sw.isEnabled());
		assertFalse(sw.isChecked());

		sw.performClick();
		shadowOf(Looper.getMainLooper()).idle();
		verify(m).setInviterGrant(groupId, memberKey, true);
	}

	@Test
	public void theCreatorSeesAndRevokesAGrantedRight() throws Exception {
		GroupTrManager m = manager(MemberInviteAvailability.UNAVAILABLE, 0L);
		when(m.hasInviterGrant(groupId, memberKey)).thenReturn(true);
		ProbeAdmin a = open(m, creatorKey);

		MaterialSwitch sw = visibleGrantSwitch(a);
		assertNotNull(sw);
		assertTrue(sw.isChecked());
		assertTrue("a right can be revoked whatever the member runs",
				sw.isEnabled());
		sw.performClick();
		shadowOf(Looper.getMainLooper()).idle();
		verify(m).setInviterGrant(groupId, memberKey, false);
	}

	@Test
	public void aMemberOnAnOlderAppCannotBeGivenTheRight() throws Exception {
		ProbeAdmin a = open(manager(MemberInviteAvailability.UNAVAILABLE, 0L),
				creatorKey);

		MaterialSwitch sw = visibleGrantSwitch(a);
		assertNotNull(sw);
		assertFalse(sw.isEnabled());
		View row = (View) sw.getParent();
		TextView role = row.findViewById(R.id.memberRole);
		assertTrue(role.getText().toString().contains(a.getString(
				R.string.grouptr_member_invite_contact_outdated)));
	}

	@Test
	public void theMemberMenuOffersNoAdminRole() throws Exception {
		GroupTrManager m = manager(MemberInviteAvailability.UNAVAILABLE, 0L);
		ProbeAdmin a = open(m, creatorKey);
		MaterialSwitch sw = visibleGrantSwitch(a);
		assertNotNull(sw);
		View row = (View) sw.getParent().getParent();
		row.findViewById(R.id.memberMenuButton).performClick();
		shadowOf(Looper.getMainLooper()).idle();

		AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(d);
		ListView items = d.getListView();
		assertEquals(1, items.getAdapter().getCount());
		assertEquals(a.getString(R.string.grouptr_member_action_remove),
				items.getAdapter().getItem(0).toString());
		verify(m, never()).promoteToAdmin(any(), any());
	}

	@Test
	public void anIncognitoGroupShowsTheNameTheUserAppearsUnder()
			throws Exception {
		GroupTrManager m = manager(MemberInviteAvailability.NOT_ENABLED, 0L);
		when(m.getIncognitoName(any())).thenReturn("Amber Falcon 27");
		ProbeAdmin a = open(m, memberKey);

		assertEquals(View.VISIBLE,
				a.findViewById(R.id.rowIncognito).getVisibility());
		TextView name = a.findViewById(R.id.incognitoNameText);
		assertEquals(a.getString(R.string.grouptr_incognito_name,
				"Amber Falcon 27"), name.getText().toString());
	}

	@Test
	public void aGroupWithoutIncognitoHidesTheRow() throws Exception {
		ProbeAdmin a = open(manager(MemberInviteAvailability.NOT_ENABLED, 0L),
				memberKey);

		assertEquals(View.GONE,
				a.findViewById(R.id.rowIncognito).getVisibility());
	}

	@javax.annotation.Nullable
	private static MaterialSwitch visibleGrantSwitch(ProbeAdmin a) {
		ViewGroup members = a.findViewById(R.id.membersContainer);
		for (int i = 0; i < members.getChildCount(); i++) {
			View sw = members.getChildAt(i)
					.findViewById(R.id.memberMayAddSwitch);
			if (sw != null && sw.getVisibility() == View.VISIBLE) {
				return (MaterialSwitch) sw;
			}
		}
		return null;
	}

	private GroupTrManager manager(MemberInviteAvailability availability,
			long permissions) throws Exception {
		GroupTrManager m = mock(GroupTrManager.class);
		when(m.getGroup(any())).thenReturn(state());
		when(m.getGroupPermissions(any())).thenReturn(permissions);
		when(m.getMemberInviteAvailability(any())).thenReturn(availability);
		when(m.getMembersOutOfReach(any())).thenReturn(new ArrayList<>());
		when(m.getSentInvites(any())).thenReturn(new ArrayList<>());
		return m;
	}

	private ProbeAdmin open(GroupTrManager m, byte[] localKey)
			throws Exception {
		IdentityManager identity = mock(IdentityManager.class);
		when(identity.getLocalAuthor()).thenReturn(new LocalAuthor(
				new AuthorId(getRandomId()), 1, "Me",
				new SignaturePublicKey(localKey),
				new SignaturePrivateKey(new byte[32])));
		ProbeAdmin.manager = m;
		ProbeAdmin.identity = identity;
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeAdmin.class);
		i.putExtra(GroupTrAdminActivity.EXTRA_GROUP_ID,
				StringUtils.toHexString(groupId));
		ProbeAdmin a = Robolectric.buildActivity(ProbeAdmin.class, i)
				.setup().get();
		shadowOf(Looper.getMainLooper()).idle();
		return a;
	}

	private GroupTrState state() {
		List<GroupTrMember> members = new ArrayList<>();
		members.add(new GroupTrMember(creatorKey, "Carol", 0L, 0L,
				MemberRole.CREATOR));
		members.add(new GroupTrMember(memberKey, "Mia", 0L, 1L));
		return new GroupTrState(groupId, "Club", new byte[32], creatorKey,
				"Carol", 0L, 1L, false, members);
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
