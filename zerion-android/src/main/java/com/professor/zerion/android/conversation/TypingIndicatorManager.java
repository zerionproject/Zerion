package com.professor.zerion.android.conversation;

import android.os.Handler;
import android.os.Looper;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;

@NotNullByDefault
class TypingIndicatorManager {

	private static final long SEND_INTERVAL_MS = 4000;
	private static final long IDLE_TIMEOUT_MS = 3000;
	private static final long RECEIVE_TIMEOUT_MS = 6000;

	private final Handler handler = new Handler(Looper.getMainLooper());
	@Nullable
	private Runnable sendIdleCallback;
	@Nullable
	private Runnable receiveTimeoutCallback;

	private boolean isSending = false;
	private long lastSendTime = 0;

	@Nullable
	private TypingListener listener;

	interface TypingListener {
		void onSendTypingIndicator(boolean isTyping);
		void onContactTypingChanged(boolean isTyping);
	}

	void setListener(@Nullable TypingListener listener) {
		this.listener = listener;
	}

	@UiThread
	void onTextChanged() {
		long now = System.currentTimeMillis();
		if (!isSending || now - lastSendTime >= SEND_INTERVAL_MS) {
			isSending = true;
			lastSendTime = now;
			if (listener != null) {
				listener.onSendTypingIndicator(true);
			}
		}
		resetIdleTimer();
	}

	@UiThread
	void onMessageSent() {
		cancelIdleTimer();
		if (isSending) {
			isSending = false;
			if (listener != null) {
				listener.onSendTypingIndicator(false);
			}
		}
	}

	@UiThread
	void onTypingIndicatorReceived(boolean isTyping) {
		cancelReceiveTimeout();
		if (isTyping) {
			if (listener != null) {
				listener.onContactTypingChanged(true);
			}
			receiveTimeoutCallback = () -> {
				if (listener != null) {
					listener.onContactTypingChanged(false);
				}
			};
			handler.postDelayed(receiveTimeoutCallback, RECEIVE_TIMEOUT_MS);
		} else {
			if (listener != null) {
				listener.onContactTypingChanged(false);
			}
		}
	}

	@UiThread
	void destroy() {
		cancelIdleTimer();
		cancelReceiveTimeout();
		listener = null;
	}

	private void resetIdleTimer() {
		cancelIdleTimer();
		sendIdleCallback = () -> {
			if (isSending) {
				isSending = false;
				if (listener != null) {
					listener.onSendTypingIndicator(false);
				}
			}
		};
		handler.postDelayed(sendIdleCallback, IDLE_TIMEOUT_MS);
	}

	private void cancelIdleTimer() {
		if (sendIdleCallback != null) {
			handler.removeCallbacks(sendIdleCallback);
			sendIdleCallback = null;
		}
	}

	private void cancelReceiveTimeout() {
		if (receiveTimeoutCallback != null) {
			handler.removeCallbacks(receiveTimeoutCallback);
			receiveTimeoutCallback = null;
		}
	}
}
