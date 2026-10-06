package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
final class AudioFrameReplayGuard {

	private long highest = -1;

	synchronized boolean admit(long sequence) {
		if (sequence <= highest) return false;
		highest = sequence;
		return true;
	}
}
