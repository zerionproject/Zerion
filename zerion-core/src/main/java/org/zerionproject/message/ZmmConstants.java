package org.zerionproject.message;

/**
 * The Zerion Message Model (ZMM) flat message-type registry.
 *
 * <p>Dispatch uses a single 16-bit type carried inside each frame. The type
 * lives inside the AEAD, so a network observer cannot see it; cover, text,
 * receipts and group mutations are all indistinguishable on the wire (the
 * enclosing ZWF frame is fixed-size).
 * Ranges: 0x00 padding/cover, 0x01-0x1F conversation, 0x20-0x2F receipts,
 * 0x30-0x3F group, 0x40-0x4F channel, 0xF0-0xFF control/reserved.
 */
public interface ZmmConstants {

	int TYPE_COVER = 0x00;

	int TYPE_TEXT = 0x01;
	int TYPE_MEDIA_MANIFEST = 0x02;
	int TYPE_MEDIA_CHUNK = 0x03;
	int TYPE_VOICE_SIGNAL = 0x04;
	int TYPE_REACTION = 0x10;
	int TYPE_EDIT = 0x11;
	int TYPE_DELETE = 0x12;
	int TYPE_REPLY = 0x13;

	int TYPE_RECEIPT_DELIVERED = 0x20;
	int TYPE_RECEIPT_READ = 0x21;

	int TYPE_GROUP_CREATE = 0x30;
	int TYPE_GROUP_INVITE_OFFER = 0x31;
	int TYPE_GROUP_INVITE_ACCEPT = 0x32;
	int TYPE_GROUP_INVITE_DECLINE = 0x33;
	int TYPE_GROUP_POST = 0x34;
	int TYPE_GROUP_MEMBERSHIP = 0x35;

	int TYPE_CHANNEL_POST = 0x40;
	int TYPE_CHANNEL_SUBSCRIBE = 0x41;

	int TYPE_ACK = 0xF0;
	int TYPE_REQUEST = 0xF1;
	// a fragment of a record too large for one frame (see ZmmFragmenter)
	int TYPE_FRAGMENT = 0xF2;
	// a delivery-DAG sync record (Message/Ack/Offer/Request/...) carried opaque;
	// its own kind is read from the sync-record framing, not the ZMM type.
	int TYPE_SYNC = 0xF3;

	int MAX_TYPE = 0xFFFF;
}
