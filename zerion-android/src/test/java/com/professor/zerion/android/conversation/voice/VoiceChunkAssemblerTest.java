package com.professor.zerion.android.conversation.voice;

import org.zerionproject.core.api.contact.ContactId;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * PROTO-14: voice memo assemblies are scoped by contact, so one contact's
 * parts can neither evict nor complete another contact's memo, and eviction
 * under a flood of fresh memo ids is confined to the flooding contact.
 */
public class VoiceChunkAssemblerTest {

	private static final ContactId A = new ContactId(1);
	private static final ContactId B = new ContactId(2);

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
		assembler.addPartText(A, part(memo, 0, 2));
		for (int i = 0; i <= VoiceChunkAssembler.MAX_ASSEMBLIES; i++) {
			assembler.addPartText(B, part(memoId(0x200 + i), 0, 2));
		}
		assembler.addPartText(A, part(memo, 1, 2));
		assertNotNull("A's memo must survive B's flood",
				assembler.getReassembled(A, memo));
	}

	@Test
	public void memoIdsAreScopedPerContact() {
		VoiceChunkAssembler assembler = new VoiceChunkAssembler();
		String memo = memoId(0x300);
		assembler.addPartText(A, part(memo, 0, 2));
		assembler.addPartText(B, part(memo, 1, 2));
		assertNull("B's part must not complete A's memo",
				assembler.getReassembled(A, memo));
		assertNull(assembler.getReassembled(B, memo));
		assembler.addPartText(A, part(memo, 1, 2));
		assertNotNull(assembler.getReassembled(A, memo));
		assertNull(assembler.getReassembled(B, memo));
		assertFalse(assembler.isFailed(B, memo));
	}

	@Test
	public void aContactCanOnlyEvictItsOwnAssemblies() {
		VoiceChunkAssembler assembler = new VoiceChunkAssembler();
		String first = memoId(0x400);
		assembler.addPartText(A, part(first, 0, 2));
		for (int i = 1; i <= VoiceChunkAssembler.MAX_ASSEMBLIES; i++) {
			assembler.addPartText(A, part(memoId(0x400 + i), 0, 2));
		}
		assembler.addPartText(A, part(first, 1, 2));
		assertNull("the contact's own oldest assembly was evicted",
				assembler.getReassembled(A, first));
		String last = memoId(0x400 + VoiceChunkAssembler.MAX_ASSEMBLIES);
		assembler.addPartText(A, part(last, 1, 2));
		assertNotNull(assembler.getReassembled(A, last));
	}

	@Test
	public void scopesAreBoundedLeastRecentlyUsedFirst() {
		VoiceChunkAssembler assembler = new VoiceChunkAssembler();
		String memo = memoId(0x500);
		for (int c = 0; c <= VoiceChunkAssembler.MAX_SCOPES; c++) {
			assembler.addPartText(new ContactId(c), part(memo, 0, 2));
		}
		assembler.addPartText(new ContactId(0), part(memo, 1, 2));
		assertNull(assembler.getReassembled(new ContactId(0), memo));
		ContactId newest = new ContactId(VoiceChunkAssembler.MAX_SCOPES);
		assembler.addPartText(newest, part(memo, 1, 2));
		assertNotNull(assembler.getReassembled(newest, memo));
	}

	@Test
	public void ownCompleteMemoIsScopedToTheConversation() {
		VoiceChunkAssembler assembler = new VoiceChunkAssembler();
		String memo = memoId(0x600);
		assembler.putComplete(A, memo, "[VOICE:1000:abc]");
		assertNotNull(assembler.getReassembled(A, memo));
		assertNull(assembler.getReassembled(B, memo));
	}
}
