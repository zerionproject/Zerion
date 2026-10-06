package com.professor.zerion.android.conversation.voice;

import org.zerionproject.core.api.contact.ContactId;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VoiceChunkAssemblerTest {

	private static final ContactId A = new ContactId(1);
	private static final ContactId B = new ContactId(2);
	private static final boolean RECEIVED = false;
	private static final boolean SENT = true;

	private static String memoId(int n) {
		return String.format("%016x", n);
	}

	private static String part(String memoId, int seq, int total) {
		return "[VMP:1:" + memoId + ":" + seq + ":" + total + ":1000:c"
				+ seq + "]";
	}

	@Test
	public void anotherContactsFloodDoesNotEvictAnInProgressMemo() {
		VoiceChunkAssembler assembler = new VoiceChunkAssembler();
		String memo = memoId(0x100);
		assembler.addPartText(A, RECEIVED, part(memo, 0, 2));
		for (int i = 0; i <= VoiceChunkAssembler.MAX_ASSEMBLIES; i++) {
			assembler.addPartText(B, RECEIVED, part(memoId(0x200 + i), 0, 2));
		}
		assembler.addPartText(A, RECEIVED, part(memo, 1, 2));
		assertNotNull("A's memo must survive B's flood",
				assembler.getReassembled(A, RECEIVED, memo));
	}

	@Test
	public void memoIdsAreScopedPerContact() {
		VoiceChunkAssembler assembler = new VoiceChunkAssembler();
		String memo = memoId(0x300);
		assembler.addPartText(A, RECEIVED, part(memo, 0, 2));
		assembler.addPartText(B, RECEIVED, part(memo, 1, 2));
		assertNull("B's part must not complete A's memo",
				assembler.getReassembled(A, RECEIVED, memo));
		assertNull(assembler.getReassembled(B, RECEIVED, memo));
		assembler.addPartText(A, RECEIVED, part(memo, 1, 2));
		assertNotNull(assembler.getReassembled(A, RECEIVED, memo));
		assertNull(assembler.getReassembled(B, RECEIVED, memo));
		assertFalse(assembler.isFailed(B, RECEIVED, memo));
	}

	@Test
	public void aContactCannotCompleteOrBreakAMemoThisDeviceSent() {
		VoiceChunkAssembler assembler = new VoiceChunkAssembler();
		String memo = memoId(0x700);
		assembler.addPartText(A, SENT, part(memo, 0, 2));
		assembler.addPartText(A, RECEIVED, part(memo, 1, 2));
		assertNull("the contact's part completed this device's memo",
				assembler.getReassembled(A, SENT, memo));
		assertFalse(assembler.isFailed(A, SENT, memo));
		assembler.addPartText(A, SENT, part(memo, 1, 2));
		String mine = assembler.getReassembled(A, SENT, memo);
		assertNotNull(mine);
		assertEquals(VoiceMessageChunkFormat.reassemble(1000,
				java.util.Arrays.asList("c0", "c1")), mine);
	}

	@Test
	public void aContactCanOnlyEvictItsOwnAssemblies() {
		VoiceChunkAssembler assembler = new VoiceChunkAssembler();
		String first = memoId(0x400);
		assembler.addPartText(A, RECEIVED, part(first, 0, 2));
		for (int i = 1; i <= VoiceChunkAssembler.MAX_ASSEMBLIES; i++) {
			assembler.addPartText(A, RECEIVED, part(memoId(0x400 + i), 0, 2));
		}
		assembler.addPartText(A, RECEIVED, part(first, 1, 2));
		assertNull("the contact's own oldest assembly was evicted",
				assembler.getReassembled(A, RECEIVED, first));
		String last = memoId(0x400 + VoiceChunkAssembler.MAX_ASSEMBLIES);
		assembler.addPartText(A, RECEIVED, part(last, 1, 2));
		assertNotNull(assembler.getReassembled(A, RECEIVED, last));
	}

	@Test
	public void scopesCoverAtLeastSixtyFourConversationsInBothDirections() {
		assertTrue(VoiceChunkAssembler.MAX_SCOPES >= 2 * 64);
	}

	@Test
	public void scopesAreBoundedLeastRecentlyUsedFirst() {
		VoiceChunkAssembler assembler = new VoiceChunkAssembler();
		String memo = memoId(0x500);
		for (int c = 0; c <= VoiceChunkAssembler.MAX_SCOPES; c++) {
			assembler.addPartText(new ContactId(c), RECEIVED,
					part(memo, 0, 2));
		}
		assembler.addPartText(new ContactId(0), RECEIVED, part(memo, 1, 2));
		assertNull(assembler.getReassembled(new ContactId(0), RECEIVED,
				memo));
		ContactId newest = new ContactId(VoiceChunkAssembler.MAX_SCOPES);
		assembler.addPartText(newest, RECEIVED, part(memo, 1, 2));
		assertNotNull(assembler.getReassembled(newest, RECEIVED, memo));
	}

	@Test
	public void ownCompleteMemoIsScopedToTheConversation() {
		VoiceChunkAssembler assembler = new VoiceChunkAssembler();
		String memo = memoId(0x600);
		assembler.putComplete(A, memo, "[VOICE:1000:abc]");
		assertNotNull(assembler.getReassembled(A, SENT, memo));
		assertNull(assembler.getReassembled(A, RECEIVED, memo));
		assertNull(assembler.getReassembled(B, SENT, memo));
	}
}
