package com.professor.zerion.android.conversation;

import android.content.Context;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.grouptr.GroupTrInvitationHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.util.Collections;

import androidx.lifecycle.MutableLiveData;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupMemberInviteCardTest {

	private final Context ctx = ApplicationProvider.getApplicationContext();
	private final ConversationVisitor visitor = new ConversationVisitor(ctx,
			m -> null, h -> Collections.emptyList(),
			new MutableLiveData<>("Mia"), null);
	private final byte[] groupId = getRandomId();

	@Test
	public void theCardSaysWhoInvitesToWhichGroupAndWhoCreatedIt() {
		ConversationItem item = header("Club", false, getRandomBytes(32))
				.accept(visitor);

		assertTrue(item instanceof ConversationRequestItem);
		ConversationRequestItem request = (ConversationRequestItem) item;
		assertEquals(ConversationRequestItem.RequestType.GROUPTR_MEMBER,
				request.getRequestType());
		assertFalse(request.wasAnswered());
		assertEquals(ctx.getString(R.string.grouptr_member_invitation_received,
				"Mia", "Club", "Carol"), request.getText());
		assertEquals(groupId.length, request.getGrouptrGid().length);
	}

	@Test
	public void aCardForAnUnnamedGroupStillNamesItsCreator() {
		ConversationItem item = header("", false, getRandomBytes(32))
				.accept(visitor);

		assertEquals(ctx.getString(
				R.string.grouptr_member_invitation_received_no_name, "Mia",
				"Carol"), item.getText());
	}

	@Test
	public void anAnsweredCardKeepsItsTextWithoutButtons() {
		ConversationItem item = header("Club", true, getRandomBytes(32))
				.accept(visitor);

		assertFalse(item instanceof ConversationRequestItem);
		assertEquals(ctx.getString(R.string.grouptr_member_invitation_received,
				"Mia", "Club", "Carol"), item.getText());
	}

	@Test
	public void theCreatorsOwnInvitationIsUnchanged() {
		ConversationItem item = header("Club", false, null).accept(visitor);

		assertTrue(item instanceof ConversationRequestItem);
		assertEquals(ConversationRequestItem.RequestType.GROUPTR,
				((ConversationRequestItem) item).getRequestType());
		assertEquals(ctx.getString(R.string.grouptr_invitation_received,
				"Mia", "Club"), item.getText());
	}

	private GroupTrInvitationHeader header(String groupName, boolean read,
			@javax.annotation.Nullable byte[] inviter) {
		return new GroupTrInvitationHeader(new MessageId(getRandomId()),
				new GroupId(getRandomId()), 1_000L, false, read, false, false,
				new GroupId(groupId), groupName, getRandomBytes(32), "Carol",
				getRandomBytes(32), 1_000L, inviter);
	}
}
