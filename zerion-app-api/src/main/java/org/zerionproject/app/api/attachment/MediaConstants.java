package org.zerionproject.app.api.attachment;

public interface MediaConstants {

	String MSG_KEY_CONTENT_TYPE = "contentType";
	String MSG_KEY_DESCRIPTOR_LENGTH = "descriptorLength";

	int MAX_CONTENT_TYPE_BYTES = 80;

	int CHUNK_SIZE = 512 * 1024;
	int MAX_CHUNK_COUNT = 100;
	int MAX_PARALLEL_CHUNKS = 2;

	int ATTACHMENT_DESCRIPTOR_OVERHEAD = 100;
	int MAX_ATTACHMENT_SIZE = 10 * 1024 * 1024;
	int MAX_IMAGE_SIZE = MAX_ATTACHMENT_SIZE - ATTACHMENT_DESCRIPTOR_OVERHEAD;
}
