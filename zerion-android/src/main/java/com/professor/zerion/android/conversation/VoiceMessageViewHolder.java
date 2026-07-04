package com.professor.zerion.android.conversation;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.professor.zerion.R;
import com.professor.zerion.android.attachment.AttachmentItem;

import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.briar.api.attachment.Attachment;
import org.briarproject.briar.api.attachment.AttachmentReader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executor;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

@UiThread
@NotNullByDefault
public class VoiceMessageViewHolder {
	private static final int STATE_LOADING = 0;
	private static final int STATE_READY = 1;
	private static final int STATE_ERROR = 2;

	private final MaterialButton playPauseButton;
	private final SeekBar progressBar;
	private final TextView durationText;
	private final AttachmentReader attachmentReader;
	private final Executor dbExecutor;
	private final Handler uiHandler;

	@Nullable
	private MediaPlayer mediaPlayer;
	@Nullable
	private volatile java.io.File currentTempFile;
	private boolean isPlaying = false;
	private int duration = 0;
	private int loadingState = STATE_LOADING;
	private volatile boolean released = false;

	public VoiceMessageViewHolder(View view, AttachmentReader attachmentReader,
			@DatabaseExecutor Executor dbExecutor) {
		this.playPauseButton = view.findViewById(R.id.playPauseButton);
		this.progressBar = view.findViewById(R.id.voiceProgress);
		this.durationText = view.findViewById(R.id.voiceDuration);
		this.attachmentReader = attachmentReader;
		this.dbExecutor = dbExecutor;
		this.uiHandler = new Handler(Looper.getMainLooper());

		setupListeners();
	}

	private void setupListeners() {
		playPauseButton.setOnClickListener(v -> togglePlayPause());

		progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (fromUser && mediaPlayer != null) {
					int newPosition = (int) ((progress / 100.0) * duration);
					mediaPlayer.seekTo(newPosition);
				}
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
			}
		});
	}

	public void bind(AttachmentItem item) {
		released = false;
		stop();
		isPlaying = false;
		showLoadingState();

		dbExecutor.execute(() -> {
			try {
				Attachment attachment =
						attachmentReader.getAttachment(item.getHeader());
				InputStream audioStream = attachment.getStream();
				prepareMediaPlayer(audioStream, ".3gp");
			} catch (DbException e) {
				uiHandler.post(() -> showErrorState("Failed to load"));
			}
		});
	}

	public void bindEncryptedVoice(String messageText, org.briarproject.bramble.api.sync.GroupId groupId,
	                                 org.briarproject.bramble.api.sync.MessageId messageId) {
		released = false;
		stop();
		isPlaying = false;
		showLoadingState();

		dbExecutor.execute(() -> {
			com.professor.zerion.android.conversation.voice.VoiceMessageFormat.ParsedVoiceMessage parsed =
				com.professor.zerion.android.conversation.voice.VoiceMessageFormat.parse(messageText);

			if (parsed == null) {
				uiHandler.post(() -> showErrorState("Verification failed"));
				return;
			}

			try {
				com.professor.zerion.android.conversation.voice.VoiceMessagePayloadParser.ParsedPayload payload =
					com.professor.zerion.android.conversation.voice.VoiceMessagePayloadParser.parse(parsed.getPayload());
				byte[] formatVersion = new byte[]{1};
				byte[] groupIdBytes = groupId.getBytes();
				byte[] emptyMessageId = new byte[0];
				byte[] decryptedMuLaw = com.professor.zerion.android.conversation.voice.StreamingAudioDecryptor.decryptAll(
					payload.wrappedKey, payload.iv, payload.chunks, payload.tags,
					payload.chunks.size(), payload.durationMs, payload.globalMAC,
					formatVersion, groupIdBytes, emptyMessageId);

				payload.zeroize();
				byte[] decryptedPcm = com.professor.zerion.android.conversation.voice.AudioCodec.muLawToPcm(decryptedMuLaw);
				java.util.Arrays.fill(decryptedMuLaw, (byte) 0);

				byte[] wavData = com.professor.zerion.android.conversation.voice.WavHeaderGenerator.addWavHeader(decryptedPcm);

				java.util.Arrays.fill(decryptedPcm, (byte) 0);

				java.io.ByteArrayInputStream audioStream = new java.io.ByteArrayInputStream(wavData);
				prepareMediaPlayer(audioStream, ".wav");

				java.util.Arrays.fill(wavData, (byte) 0);

			} catch (Exception e) {
				uiHandler.post(() -> showErrorState("Verification failed"));
			}
		});
	}

	public void bindReceiving() {
		released = false;
		stop();
		isPlaying = false;
		loadingState = STATE_LOADING;
		playPauseButton.setEnabled(false);
		playPauseButton.setIconResource(R.drawable.ic_play_arrow_24dp);
		progressBar.setProgress(0);
		progressBar.setEnabled(false);
		durationText.setText("Receiving...");
	}

	public void bindFailed() {
		released = false;
		stop();
		isPlaying = false;
		showErrorState("Verification failed");
	}

	private void prepareMediaPlayer(InputStream audioStream, String extension) {
		if (released) {
			try {
				audioStream.close();
			} catch (IOException ignored) {
			}
			return;
		}
		try {
			java.io.File tempFile = java.io.File.createTempFile("voice",
					extension, playPauseButton.getContext().getCacheDir());
			currentTempFile = tempFile;

			java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile);
			byte[] buffer = new byte[8192];
			int bytesRead;
			while ((bytesRead = audioStream.read(buffer)) != -1) {
				fos.write(buffer, 0, bytesRead);
			}
			fos.close();
			audioStream.close();

			uiHandler.post(() -> {
				if (released) {
					cleanupTempFile();
					return;
				}
				try {
					mediaPlayer = new MediaPlayer();
					mediaPlayer.setDataSource(tempFile.getAbsolutePath());
					mediaPlayer.prepare();

					duration = mediaPlayer.getDuration();
					updateDurationText(duration);
					showReadyState();

					mediaPlayer.setOnCompletionListener(mp -> {
						isPlaying = false;
						updatePlayPauseButton();
						progressBar.setProgress(0);
						updateDurationText(duration);
						cleanupTempFile();
					});

				} catch (IOException e) {
					showErrorState("Failed to load");
					cleanupTempFile();
				}
			});

		} catch (IOException e) {
			uiHandler.post(() -> showErrorState("Failed to load"));
		}
	}

	private void togglePlayPause() {
		if (mediaPlayer == null) {
			return;
		}

		if (isPlaying) {
			pause();
		} else {
			play();
		}
	}

	private void play() {
		if (mediaPlayer == null) return;

		mediaPlayer.start();
		isPlaying = true;
		updatePlayPauseButton();
		startProgressUpdate();
	}

	private void pause() {
		if (mediaPlayer == null) return;

		mediaPlayer.pause();
		isPlaying = false;
		updatePlayPauseButton();
	}

	private void stop() {
		if (mediaPlayer != null) {
			if (mediaPlayer.isPlaying()) {
				mediaPlayer.stop();
			}
			mediaPlayer.release();
			mediaPlayer = null;
		}
		isPlaying = false;
		cleanupTempFile();
	}

	private void cleanupTempFile() {
		if (currentTempFile != null && currentTempFile.exists()) {
			try {
				long len = currentTempFile.length();
				if (len > 0 && len < 50L * 1024 * 1024) {
					try (java.io.RandomAccessFile raf =
							new java.io.RandomAccessFile(
									currentTempFile, "rw")) {
						byte[] zeroes = new byte[8192];
						long written = 0;
						while (written < len) {
							int chunk = (int) Math.min(zeroes.length,
									len - written);
							raf.write(zeroes, 0, chunk);
							written += chunk;
						}
						raf.getFD().sync();
					}
				}
			} catch (java.io.IOException ignored) {
			}
			currentTempFile.delete();
			currentTempFile = null;
		}
	}

	private void updatePlayPauseButton() {
		if (playPauseButton != null) {
			if (isPlaying) {
				playPauseButton.setIconResource(R.drawable.ic_pause_24dp);
			} else {
				playPauseButton.setIconResource(R.drawable.ic_play_arrow_24dp);
			}
		}
	}

	private void startProgressUpdate() {
		uiHandler.post(new Runnable() {
			@Override
			public void run() {
				if (mediaPlayer != null && isPlaying) {
					int currentPosition = mediaPlayer.getCurrentPosition();
					int progress = (int) ((currentPosition / (float) duration) * 100);
					progressBar.setProgress(progress);
					updateDurationText(currentPosition);
					uiHandler.postDelayed(this, 100);
				}
			}
		});
	}

	private void updateDurationText(int milliseconds) {
		int seconds = milliseconds / 1000;
		int minutes = seconds / 60;
		int secs = seconds % 60;
		durationText.setText(String.format("%d:%02d", minutes, secs));
	}

	private void showLoadingState() {
		loadingState = STATE_LOADING;
		playPauseButton.setEnabled(false);
		playPauseButton.setIconResource(R.drawable.ic_play_arrow_24dp);
		progressBar.setProgress(0);
		progressBar.setEnabled(false);
		durationText.setText("Decrypting...");
	}

	private void showReadyState() {
		loadingState = STATE_READY;
		playPauseButton.setEnabled(true);
		progressBar.setEnabled(true);
		updatePlayPauseButton();
	}

	private void showErrorState(String message) {
		loadingState = STATE_ERROR;
		playPauseButton.setEnabled(false);
		progressBar.setEnabled(false);
		durationText.setText(message);
	}

	public void onRecycled() {
		released = true;
		uiHandler.removeCallbacksAndMessages(null);
		stop();
	}
}
