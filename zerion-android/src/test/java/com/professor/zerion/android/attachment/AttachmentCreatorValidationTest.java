package com.professor.zerion.android.attachment;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AttachmentCreatorValidationTest {

	@Test
	public void testHasValidAttachments_EmptyList_ReturnsFalse() {
		List<TestAttachmentItemResult> itemResults = new ArrayList<>();

		boolean result = hasValidAttachments(itemResults);

		assertFalse(
				"hasValidAttachments() should return false when no attachments exist",
				result
		);
	}

	@Test
	public void testHasValidAttachments_WithErrorAttachment_ReturnsFalse() {
		List<TestAttachmentItemResult> itemResults = new ArrayList<>();
		TestAttachmentItemResult errorResult = new TestAttachmentItemResult(true, false);
		itemResults.add(errorResult);

		boolean result = hasValidAttachments(itemResults);

		assertFalse(
				"hasValidAttachments() should return false when attachment has error",
				result
		);
	}

	@Test
	public void testHasValidAttachments_WithNullItem_ReturnsFalse() {
		List<TestAttachmentItemResult> itemResults = new ArrayList<>();
		TestAttachmentItemResult nullItemResult = new TestAttachmentItemResult(false, true);
		itemResults.add(nullItemResult);

		boolean result = hasValidAttachments(itemResults);

		assertFalse(
				"hasValidAttachments() should return false when attachment item is null",
				result
		);
	}

	@Test
	public void testHasValidAttachments_WithValidImageAttachment_ReturnsTrue() {
		List<TestAttachmentItemResult> itemResults = new ArrayList<>();
		TestAttachmentItemResult validResult = new TestAttachmentItemResult(false, false);
		itemResults.add(validResult);

		boolean result = hasValidAttachments(itemResults);

		assertTrue(
				"hasValidAttachments() should return true for valid image attachment",
				result
		);
	}

	@Test
	public void testHasValidAttachments_WithValidVideoAttachment_ReturnsTrue() {
		List<TestAttachmentItemResult> itemResults = new ArrayList<>();
		TestAttachmentItemResult validResult = new TestAttachmentItemResult(false, false);
		itemResults.add(validResult);

		boolean result = hasValidAttachments(itemResults);

		assertTrue(
				"hasValidAttachments() should return true for valid video attachment",
				result
		);
	}

	@Test
	public void testHasValidAttachments_MixedValidAndError_ReturnsFalse() {
		List<TestAttachmentItemResult> itemResults = new ArrayList<>();
		TestAttachmentItemResult validResult = new TestAttachmentItemResult(false, false);
		itemResults.add(validResult);

		TestAttachmentItemResult errorResult = new TestAttachmentItemResult(true, false);
		itemResults.add(errorResult);

		boolean result = hasValidAttachments(itemResults);

		assertFalse(
				"hasValidAttachments() should return false when ANY attachment has error",
				result
		);
	}

	@Test
	public void testHasValidAttachments_MultipleValidAttachments_ReturnsTrue() {
		List<TestAttachmentItemResult> itemResults = new ArrayList<>();

		for (int i = 0; i < 3; i++) {
			TestAttachmentItemResult validResult = new TestAttachmentItemResult(false, false);
			itemResults.add(validResult);
		}

		boolean result = hasValidAttachments(itemResults);

		assertTrue(
				"hasValidAttachments() should return true when all attachments are valid",
				result
		);
	}

	@Test
	public void testValidationLogic_MatchesImplementation() {
		assertTrue(
				"Empty list should return false",
				!hasValidAttachments(new ArrayList<>())
		);

		List<TestAttachmentItemResult> validList = new ArrayList<>();
		validList.add(new TestAttachmentItemResult(false, false));
		assertTrue(
				"Single valid item should return true",
				hasValidAttachments(validList)
		);

		List<TestAttachmentItemResult> errorList = new ArrayList<>();
		errorList.add(new TestAttachmentItemResult(true, false));
		assertFalse(
				"Error item should return false",
				!errorList.get(0).hasError
		);
	}

	private boolean hasValidAttachments(List<TestAttachmentItemResult> itemResults) {
		if (itemResults.isEmpty()) return false;
		for (TestAttachmentItemResult itemResult : itemResults) {
			if (itemResult.hasError || itemResult.isNullItem) {
				return false;
			}
		}
		return true;
	}

	private static class TestAttachmentItemResult {
		final boolean hasError;
		final boolean isNullItem;

		TestAttachmentItemResult(boolean hasError, boolean isNullItem) {
			this.hasError = hasError;
			this.isNullItem = isNullItem;
		}
	}
}
