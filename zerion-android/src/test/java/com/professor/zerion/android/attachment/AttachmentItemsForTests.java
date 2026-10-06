package com.professor.zerion.android.attachment;

import org.zerionproject.app.api.attachment.AttachmentHeader;

public final class AttachmentItemsForTests {

	private AttachmentItemsForTests() {
	}

	public static AttachmentItem available(AttachmentHeader header,
			String extension) {
		return new AttachmentItem(header, 640, 360, extension, 160, 90,
				AttachmentItem.State.AVAILABLE);
	}
}
