package org.zerionproject.app.grouptr;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class GroupMemberInviteSignedInputTest {

	private final byte[] groupId = filled(32, 0x11);
	private final byte[] inviter = filled(32, 0x22);
	private final byte[] invitee = filled(32, 0x33);
	private final byte[] creator = filled(32, 0x44);
	private final byte[] mlDsa = filled(1952, 0x55);
	private final byte[] sig = filled(3373, 0x66);

	@Test
	public void permissionsInputIsGroupFlagsTimestampAndDomainByte() {
		byte[] in = GroupTrMemberInvites.permissionsSignedInput(groupId,
				0x0102030405060708L, 0x1112131415161718L);
		ByteBuffer expected = ByteBuffer.allocate(49);
		expected.put(groupId);
		expected.put(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
		expected.put(new byte[] {0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
				0x18});
		expected.put((byte) 0x09);
		assertArrayEquals(expected.array(), in);
	}

	@Test
	public void responseInputBindsBothKeysTheCreatorAndTheInviteesMlDsaKey() {
		byte[] in = GroupTrMemberInvites.responseSignedInput(groupId, invitee,
				inviter, creator, 0x0A0B0C0D0E0F1011L, mlDsa);
		assertEquals(32 * 4 + 8 + 4 + 1952, in.length);
		assertArrayEquals(groupId, Arrays.copyOfRange(in, 0, 32));
		assertArrayEquals(invitee, Arrays.copyOfRange(in, 32, 64));
		assertArrayEquals(inviter, Arrays.copyOfRange(in, 64, 96));
		assertArrayEquals(creator, Arrays.copyOfRange(in, 96, 128));
		assertEquals(0x0A0B0C0D0E0F1011L, ByteBuffer.wrap(in, 128, 8)
				.getLong());
		assertEquals(1952, ByteBuffer.wrap(in, 136, 4).getInt());
		assertArrayEquals(mlDsa, Arrays.copyOfRange(in, 140, in.length));
	}

	@Test
	public void offerInputCoversEveryFieldIncludingTheInvitee() {
		byte[] in = GroupTrMemberInvites.offerSignedInput(groupId, inviter,
				invitee, creator, 7L, "Club", filled(32, 0x77), "Carol",
				mlDsa, 1L, 9L, sig);
		ByteBuffer b = ByteBuffer.wrap(in);
		assertArrayEquals(groupId, take(b, 32));
		assertArrayEquals(inviter, take(b, 32));
		assertArrayEquals(invitee, take(b, 32));
		assertArrayEquals(creator, take(b, 32));
		assertEquals(7L, b.getLong());
		assertArrayEquals(utf8("Club"), take(b, b.getInt()));
		assertArrayEquals(filled(32, 0x77), take(b, b.getInt()));
		assertArrayEquals(utf8("Carol"), take(b, b.getInt()));
		assertArrayEquals(mlDsa, take(b, b.getInt()));
		assertEquals(1L, b.getLong());
		assertEquals(9L, b.getLong());
		assertArrayEquals(sig, take(b, b.getInt()));
		assertEquals(0, b.remaining());

		byte[] otherInvitee = GroupTrMemberInvites.offerSignedInput(groupId,
				inviter, creator, creator, 7L, "Club", filled(32, 0x77),
				"Carol", mlDsa, 1L, 9L, sig);
		assertNotEquals(Arrays.toString(in), Arrays.toString(otherInvitee));
	}

	@Test
	public void joinRequestInputCoversTheAcceptanceAndTheInviteesKey() {
		byte[] in = GroupTrMemberInvites.joinRequestSignedInput(groupId,
				inviter, invitee, 5L, 6L, "Xavi", mlDsa, sig);
		ByteBuffer b = ByteBuffer.wrap(in);
		assertArrayEquals(groupId, take(b, 32));
		assertArrayEquals(inviter, take(b, 32));
		assertArrayEquals(invitee, take(b, 32));
		assertEquals(5L, b.getLong());
		assertEquals(6L, b.getLong());
		assertArrayEquals(utf8("Xavi"), take(b, b.getInt()));
		assertArrayEquals(mlDsa, take(b, b.getInt()));
		assertArrayEquals(sig, take(b, b.getInt()));
		assertEquals(0, b.remaining());
	}

	@Test
	public void everyRecordTypeHasItsOwnLabel() {
		String[] labels = {GroupTrMemberInvites.LABEL_PERMISSIONS,
				GroupTrMemberInvites.LABEL_OFFER,
				GroupTrMemberInvites.LABEL_ACCEPT,
				GroupTrMemberInvites.LABEL_DECLINE,
				GroupTrMemberInvites.LABEL_JOIN_REQUEST,
				GroupTrConstants.SIGNING_LABEL_GROUPTR_INVITE_OFFER,
				GroupTrConstants.SIGNING_LABEL_GROUPTR_INVITE_ACCEPT,
				GroupTrConstants.SIGNING_LABEL_GROUPTR_INVITE_DECLINE,
				GroupTrConstants.SIGNING_LABEL_GROUP_SETTINGS,
				GroupTrConstants.SIGNING_LABEL_GROUP_MEMBERSHIP};
		assertEquals(labels.length,
				new java.util.HashSet<>(Arrays.asList(labels)).size());
	}

	private static byte[] take(ByteBuffer b, int n) {
		byte[] out = new byte[n];
		b.get(out);
		return out;
	}

	private static byte[] utf8(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] filled(int n, int v) {
		byte[] b = new byte[n];
		Arrays.fill(b, (byte) v);
		return b;
	}
}
