package org.zerionproject.app.api.grouptr;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupTrPostSummary {

	public static final int MAX_TEXT_CHARS = 120;

	public enum Kind {TEXT, VOICE, IMAGE, VIDEO, MEDIA}

	private final byte[] senderPubKey;
	private final String senderName;
	private final Kind kind;
	private final String text;
	private final long timestamp;
	private final long expiryTime;

	public GroupTrPostSummary(byte[] senderPubKey, String senderName,
			Kind kind, String text, long timestamp, long expiryTime) {
		this.senderPubKey = senderPubKey.clone();
		this.senderName = senderName;
		this.kind = kind;
		this.text = shorten(text);
		this.timestamp = timestamp;
		this.expiryTime = expiryTime;
	}

	static String shorten(String text) {
		if (text.length() <= MAX_TEXT_CHARS) return text;
		int end = MAX_TEXT_CHARS;
		if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
		return text.substring(0, end);
	}

	public byte[] getSenderPubKey() {
		return senderPubKey.clone();
	}

	public String getSenderName() {
		return senderName;
	}

	public Kind getKind() {
		return kind;
	}

	public String getText() {
		return text;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public long getExpiryTime() {
		return expiryTime;
	}

	public boolean isExpiredAt(long now) {
		return now >= expiryTime;
	}
}
