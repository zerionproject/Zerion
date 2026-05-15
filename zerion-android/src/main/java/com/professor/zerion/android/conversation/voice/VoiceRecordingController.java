package com.professor.zerion.android.conversation.voice;

import android.animation.ValueAnimator;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.professor.zerion.R;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.concurrent.Executor;

@NotNullByDefault
public class VoiceRecordingController implements DefaultLifecycleObserver {

	public interface VoiceRecordingHost {
		void onRecordingComplete();
		void onRecordingCancelled();
		void onRecordingError(Exception e);

		GroupId getGroupIdForRecording();
		void onEncryptionInit(byte[] iv, byte[] sessionKey);
		void onEncryptedChunk(byte[] encrypted, int len, byte[] tagPart);
		void onEncryptionFinal(byte[] globalMAC, int totalDurationMs, int chunkCount);
		void cancelVoiceRecordingInViewModel();
		void runOnUiThread(Runnable action);
		void onAttachmentRecordingComplete(java.io.File audioFile, int durationMs, String mimeType);
		void storeVoiceAttachment(android.net.Uri audioUri);
	}

	private final VoiceRecordingHost host;
	private final Executor dbExecutor;

	public enum RecordingMode {
		SHORT_MESSAGE,
		ATTACHMENT
	}

	@Nullable
	private VoiceMessageRecorder voiceRecorder;
	@Nullable
	private VoiceAttachmentHandler attachmentHandler;
	private boolean isRecording = false;
	private RecordingMode currentMode = RecordingMode.SHORT_MESSAGE;
	private long recordingStartTime = 0;
	@Nullable
	private ValueAnimator pulseAnimator;

	@Nullable
	private View voiceRecordingOverlay;
	@Nullable
	private TextView recordingTimer;
	@Nullable
	private View recordingPulse;
	@Nullable
	private AppCompatImageButton cancelRecordingButton;
	@Nullable
	private AppCompatImageButton sendVoiceButton;
	@Nullable
	private View textInputView;

	public VoiceRecordingController(VoiceRecordingHost host, Executor dbExecutor) {
		this.host = host;
		this.dbExecutor = dbExecutor;
	}

	@UiThread
	public void initRecorder(android.content.Context context) {
		voiceRecorder = new VoiceMessageRecorder(context, dbExecutor);
		attachmentHandler = new VoiceAttachmentHandler(context, dbExecutor);
	}

	@UiThread
	public void bindViews(
			@Nullable View overlay,
			@Nullable TextView timer,
			@Nullable View pulse,
			@Nullable AppCompatImageButton cancelButton,
			@Nullable AppCompatImageButton sendButton,
			@Nullable View textInput) {
		this.voiceRecordingOverlay = overlay;
		this.recordingTimer = timer;
		this.recordingPulse = pulse;
		this.cancelRecordingButton = cancelButton;
		this.sendVoiceButton = sendButton;
		this.textInputView = textInput;

		if (cancelRecordingButton != null) {
			cancelRecordingButton.setOnClickListener(v -> stopRecording());
		}
		if (sendVoiceButton != null) {
			sendVoiceButton.setOnClickListener(v -> finishRecording());
		}
	}

	@UiThread
	public void startRecording() {
		startRecording(RecordingMode.SHORT_MESSAGE);
	}

	@UiThread
	public void startAttachmentRecording() {
		startRecording(RecordingMode.ATTACHMENT);
	}

	@UiThread
	public void startRecording(RecordingMode mode) {
		if (isRecording) return;

		currentMode = mode;

		if (mode == RecordingMode.SHORT_MESSAGE) {
			if (voiceRecorder == null) return;
			isRecording = true;
			showRecordingUI();
		} else {
			if (attachmentHandler == null) return;
			isRecording = true;
			showAttachmentRecordingUI();
		}
	}

	@UiThread
	public void stopRecording() {
		if (!isRecording) return;

		try {
			if (currentMode == RecordingMode.SHORT_MESSAGE) {
				if (voiceRecorder != null) {
					voiceRecorder.cancelStreamingRecording();
				}
				host.cancelVoiceRecordingInViewModel();
			} else {
				if (attachmentHandler != null) {
					attachmentHandler.cancelRecording();
				}
			}
		} catch (Exception e) {
			host.onRecordingError(e);
		}
		isRecording = false;
		hideRecordingUI();
		host.onRecordingCancelled();
	}

	@UiThread
	public void finishRecording() {
		if (!isRecording) return;

		try {
			showProcessingState();

			if (currentMode == RecordingMode.SHORT_MESSAGE) {
				if (voiceRecorder != null) {
					voiceRecorder.stopStreamingRecording();
				}
			} else {
				if (attachmentHandler != null) {
					attachmentHandler.stopRecording();
				}
			}
		} catch (Exception e) {
			host.onRecordingError(e);
		}
	}

	@UiThread
	public void forceCancel() {
		if (isRecording) {
			if (currentMode == RecordingMode.SHORT_MESSAGE && voiceRecorder != null) {
				voiceRecorder.cancelStreamingRecording();
				host.cancelVoiceRecordingInViewModel();
			} else if (currentMode == RecordingMode.ATTACHMENT && attachmentHandler != null) {
				attachmentHandler.cancelRecording();
			}
			isRecording = false;
			hideRecordingUI();
		}
	}

	public RecordingMode getCurrentMode() {
		return currentMode;
	}

	public int getMaxDurationSeconds() {
		return currentMode == RecordingMode.SHORT_MESSAGE ? 30 : VoiceAttachmentHandler.getMaxDurationSeconds();
	}

	public boolean isRecording() {
		return isRecording;
	}

	@Override
	public void onStop(@NonNull LifecycleOwner owner) {
		forceCancel();
	}

	private void showRecordingUI() {
		if (textInputView != null) {
			textInputView.setVisibility(View.GONE);
		}
		if (voiceRecordingOverlay != null) {
			voiceRecordingOverlay.setVisibility(View.VISIBLE);
		}
		startPulseAnimation();
		recordingStartTime = System.currentTimeMillis();

		try {
			GroupId groupId = host.getGroupIdForRecording();
			byte[] groupIdBytes = groupId.getBytes();

			voiceRecorder.startStreamingRecording(groupIdBytes, new EncryptedChunkCallback() {
				@Override
				public void onRecordingStarted() {
				}

				@Override
				public void onEncryptionInit(byte[] iv, byte[] sessionKey) {
					byte[] ivCopy = Arrays.copyOf(iv, iv.length);
					byte[] sessionKeyCopy = Arrays.copyOf(sessionKey, sessionKey.length);
					host.onEncryptionInit(ivCopy, sessionKeyCopy);
					Arrays.fill(iv, (byte) 0);
					Arrays.fill(sessionKey, (byte) 0);
				}

				@Override
				public void onEncryptedChunk(byte[] encrypted, int len, byte[] tagPart) {
					byte[] encryptedCopy = Arrays.copyOf(encrypted, encrypted.length);
					byte[] tagCopy = Arrays.copyOf(tagPart, tagPart.length);
					host.onEncryptedChunk(encryptedCopy, len, tagCopy);
					Arrays.fill(encrypted, (byte) 0);
					Arrays.fill(tagPart, (byte) 0);
				}

				@Override
				public void onEncryptionFinal(byte[] globalMAC, int totalDurationMs, int chunkCount) {
					byte[] globalMACCopy = Arrays.copyOf(globalMAC, globalMAC.length);
					host.onEncryptionFinal(globalMACCopy, totalDurationMs, chunkCount);
					Arrays.fill(globalMAC, (byte) 0);
					host.runOnUiThread(() -> {
						isRecording = false;
						hideRecordingUI();
						host.onRecordingComplete();
					});
				}

				@Override
				public void onRecordingProgress(int durationMs, int amplitudeDb) {
					host.runOnUiThread(() -> updateTimerDisplay(durationMs));
				}

				@Override
				public void onError(Exception e) {
					host.runOnUiThread(() -> {
						isRecording = false;
						hideRecordingUI();
						host.onRecordingError(e);
					});
				}

				@Override
				public void onCancelled() {
					host.runOnUiThread(() -> {
						isRecording = false;
						hideRecordingUI();
					});
				}
			});
		} catch (Exception e) {
			host.onRecordingError(e);
			hideRecordingUI();
			isRecording = false;
		}
	}

	private void showAttachmentRecordingUI() {
		if (textInputView != null) {
			textInputView.setVisibility(View.GONE);
		}
		if (voiceRecordingOverlay != null) {
			voiceRecordingOverlay.setVisibility(View.VISIBLE);
		}
		startPulseAnimation();
		recordingStartTime = System.currentTimeMillis();

		try {
			attachmentHandler.startRecording(new VoiceAttachmentHandler.AttachmentRecordingCallback() {
				@Override
				public void onRecordingStarted() {
				}

				@Override
				public void onRecordingProgress(int durationMs, int amplitudeDb) {
					host.runOnUiThread(() -> updateTimerDisplay(durationMs));
				}

				@Override
				public void onRecordingCompleted(java.io.File audioFile, int durationMs, String mimeType) {
					host.runOnUiThread(() -> {
						isRecording = false;
						hideRecordingUI();
						host.onAttachmentRecordingComplete(audioFile, durationMs, mimeType);
						android.net.Uri audioUri = android.net.Uri.fromFile(audioFile);
						host.storeVoiceAttachment(audioUri);
					});
				}

				@Override
				public void onRecordingError(String error) {
					host.runOnUiThread(() -> {
						isRecording = false;
						hideRecordingUI();
						host.onRecordingError(new Exception(error));
					});
				}

				@Override
				public void onRecordingCancelled() {
					host.runOnUiThread(() -> {
						isRecording = false;
						hideRecordingUI();
					});
				}
			});
		} catch (Exception e) {
			host.onRecordingError(e);
			hideRecordingUI();
			isRecording = false;
		}
	}

	private void hideRecordingUI() {
		if (voiceRecordingOverlay != null) {
			voiceRecordingOverlay.setVisibility(View.GONE);
		}
		if (textInputView != null) {
			textInputView.setVisibility(View.VISIBLE);
		}
		stopPulseAnimation();
		resetProcessingState();
		if (recordingTimer != null) {
			recordingTimer.setText("0:00");
		}
	}

	private void showProcessingState() {
		stopPulseAnimation();
		if (cancelRecordingButton != null) {
			cancelRecordingButton.setEnabled(false);
			cancelRecordingButton.setAlpha(0.5f);
		}
		if (sendVoiceButton != null) {
			sendVoiceButton.setEnabled(false);
			sendVoiceButton.setAlpha(0.5f);
		}
		if (recordingTimer != null) {
			recordingTimer.setText(recordingTimer.getContext().getString(R.string.sending_voice_message));
		}
	}

	private void resetProcessingState() {
		if (cancelRecordingButton != null) {
			cancelRecordingButton.setEnabled(true);
			cancelRecordingButton.setAlpha(1.0f);
		}
		if (sendVoiceButton != null) {
			sendVoiceButton.setEnabled(true);
			sendVoiceButton.setAlpha(1.0f);
		}
	}

	private void updateTimerDisplay(int durationMs) {
		int seconds = durationMs / 1000;
		int minutes = seconds / 60;
		int secs = seconds % 60;
		if (recordingTimer != null) {
			recordingTimer.setText(String.format("%d:%02d", minutes, secs));
		}
	}

	private void startPulseAnimation() {
		if (recordingPulse != null) {
			pulseAnimator = ValueAnimator.ofFloat(1.0f, 0.3f);
			pulseAnimator.setDuration(800);
			pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
			pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
			pulseAnimator.addUpdateListener(animation -> {
				float alpha = (float) animation.getAnimatedValue();
				if (recordingPulse != null) {
					recordingPulse.setAlpha(alpha);
				}
			});
			pulseAnimator.start();
		}
	}

	private void stopPulseAnimation() {
		if (pulseAnimator != null) {
			pulseAnimator.cancel();
			pulseAnimator = null;
		}
		if (recordingPulse != null) {
			recordingPulse.setAlpha(1.0f);
		}
	}
}
