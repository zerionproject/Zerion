package com.professor.zerion.android.conversation;

import com.professor.zerion.android.vault.utils.SecureMemory;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.app.api.attachment.Attachment;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.AttachmentNotYetAvailableException;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.attachment.AttachmentItem;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.annotation.Nullable;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
@UnstableApi
public class VideoPlayerActivity extends ZerionActivity {

	static final String ATTACHMENT = "attachment";
	static final String ITEM_ID = "itemId";

	private static final String TEMP_VIDEO_PREFIX = "zerion_video_";
	private static final String[] SUPPORTED_VIDEO_TYPES = {
			"video/mp4", "video/webm", "video/3gpp", "video/quicktime",
			"video/x-matroska", "video/mpeg", "video/avi"
	};

	static final int MAX_VIDEO_BYTES = 256 * 1024 * 1024;

	private static final int MAX_RETRY_ATTEMPTS = 10;
	private static final long RETRY_DELAY_MS = 500;

	@Inject
	AttachmentReader attachmentReader;
	@Inject
	@DatabaseExecutor
	Executor dbExecutor;

	private final android.os.Handler retryHandler =
			new android.os.Handler(android.os.Looper.getMainLooper());

	@Nullable
	private ExoPlayer player;
	@Nullable
	private PlayerView playerView;
	@Nullable
	private ProgressBar loadingIndicator;
	@Nullable
	private TextView errorText;
	@Nullable
	private volatile byte[] videoBytes;

	private boolean playWhenReady = true;
	private int currentWindow = 0;
	private long playbackPosition = 0;
	private boolean isLoadingVideo = false;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		cleanupOrphanedTempFiles();

		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		setContentView(R.layout.activity_video_player);
		hideSystemUi();

		playerView = findViewById(R.id.player_view);
		loadingIndicator = findViewById(R.id.loading_indicator);
		errorText = findViewById(R.id.error_text);

		ImageButton closeButton = findViewById(R.id.close_button);
		closeButton.setOnClickListener(v -> finish());

		if (savedInstanceState != null) {
			playWhenReady = savedInstanceState.getBoolean("playWhenReady", true);
			currentWindow = savedInstanceState.getInt("currentWindow", 0);
			playbackPosition = savedInstanceState.getLong("playbackPosition", 0);
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		initializePlayer();
	}

	@Override
	public void onResume() {
		super.onResume();
		hideSystemUi();
		if (player == null) {
			initializePlayer();
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		releasePlayer();
	}

	@Override
	public void onStop() {
		super.onStop();
		releasePlayer();
		wipeVideo();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		retryHandler.removeCallbacksAndMessages(null);
		wipeVideo();
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		if (player != null) {
			outState.putBoolean("playWhenReady", player.getPlayWhenReady());
			outState.putInt("currentWindow", player.getCurrentMediaItemIndex());
			outState.putLong("playbackPosition", player.getCurrentPosition());
		}
	}

	private void cleanupOrphanedTempFiles() {
		try {
			File cacheDir = getCacheDir();
			if (cacheDir == null || !cacheDir.exists()) return;

			File[] files = cacheDir.listFiles();
			if (files == null) return;

			for (File file : files) {
				if (file.getName().startsWith(TEMP_VIDEO_PREFIX)) {
					SecureMemory.secureDeleteFile(file, 200L * 1024 * 1024,
							false);
				}
			}
		} catch (SecurityException ignored) {
		}
	}

	private void initializePlayer() {
		if (isLoadingVideo || player != null) {
			return;
		}

		byte[] prepared = videoBytes;
		if (prepared != null) {
			startPlayback(prepared);
			return;
		}

		Intent intent = getIntent();
		AttachmentItem attachment = intent.getParcelableExtra(ATTACHMENT);
		byte[] messageIdBytes = intent.getByteArrayExtra(ITEM_ID);

		if (attachment == null || messageIdBytes == null) {
			showError(getString(R.string.video_playback_error));
			return;
		}

		String mimeType = attachment.getMimeType();

		if (!isSupportedVideoType(mimeType)) {
			showError(getString(R.string.video_playback_error));
			return;
		}

		String ext = getExtensionForMimeType(mimeType);
		if (ext == null) {
			showError(getString(R.string.video_playback_error));
			return;
		}

		isLoadingVideo = true;
		showLoading(true);

		loadVideoWithRetry(attachment.getHeader(), ext, 0);
	}

	private void loadVideoWithRetry(AttachmentHeader header, String ext,
			int attemptNumber) {
		if (isFinishing() || isDestroyed()) {
			isLoadingVideo = false;
			return;
		}

		dbExecutor.execute(() -> {
			try {
				Attachment att = attachmentReader.getAttachment(header);
				byte[] bytes;
				try (InputStream is = att.getStream()) {
					bytes = readVideo(is);
				}
				if (bytes.length == 0) throw new IOException("Empty video");
				videoBytes = bytes;

				runOnUiThread(() -> {
					isLoadingVideo = false;
					if (isFinishing() || isDestroyed()) {
						wipeVideo();
						return;
					}
					startPlayback(bytes);
				});

			} catch (AttachmentNotYetAvailableException e) {
				if (attemptNumber < MAX_RETRY_ATTEMPTS) {
					retryHandler.postDelayed(() -> loadVideoWithRetry(
							header, ext, attemptNumber + 1), RETRY_DELAY_MS);
				} else {
					wipeVideo();
					runOnUiThread(() -> {
						isLoadingVideo = false;
						showLoading(false);
						showError(getString(R.string.video_still_downloading));
					});
				}
			} catch (Exception e) {
				wipeVideo();
				runOnUiThread(() -> {
					isLoadingVideo = false;
					showLoading(false);
					showError(getString(R.string.video_playback_error));
				});
			}
		});
	}

	static byte[] readVideo(InputStream in) throws IOException {
		WipingBuffer out = new WipingBuffer();
		try {
			byte[] buffer = new byte[64 * 1024];
			int n;
			while ((n = in.read(buffer)) != -1) {
				if (out.size() + n > MAX_VIDEO_BYTES) {
					throw new IOException("Video too large");
				}
				out.write(buffer, 0, n);
			}
			Arrays.fill(buffer, (byte) 0);
			return out.toByteArray();
		} finally {
			out.wipe();
		}
	}

	private static final class WipingBuffer extends ByteArrayOutputStream {

		@Override
		public synchronized void write(byte[] b, int off, int len) {
			if (count + len > buf.length) {
				byte[] old = buf;
				super.write(b, off, len);
				if (old != buf) Arrays.fill(old, (byte) 0);
			} else {
				super.write(b, off, len);
			}
		}

		void wipe() {
			Arrays.fill(buf, (byte) 0);
			reset();
		}
	}

	private boolean isSupportedVideoType(@Nullable String mimeType) {
		if (mimeType == null || mimeType.isEmpty()) {
			return false;
		}
		for (String supported : SUPPORTED_VIDEO_TYPES) {
			if (supported.equalsIgnoreCase(mimeType)) {
				return true;
			}
		}
		return false;
	}

	@Nullable
	private String getExtensionForMimeType(@Nullable String mimeType) {
		if (mimeType == null) return null;
		switch (mimeType.toLowerCase()) {
			case "video/mp4":
				return "mp4";
			case "video/webm":
				return "webm";
			case "video/3gpp":
				return "3gp";
			case "video/quicktime":
				return "mov";
			case "video/x-matroska":
				return "mkv";
			case "video/mpeg":
				return "mpg";
			case "video/avi":
				return "avi";
			default:
				return null;
		}
	}

	private void startPlayback(byte[] video) {
		if (isFinishing() || isDestroyed()) return;

		player = new ExoPlayer.Builder(this).build();

		if (playerView != null) {
			playerView.setPlayer(player);
		}

		player.addListener(new Player.Listener() {
			@Override
			public void onPlaybackStateChanged(int playbackState) {
				if (playbackState == Player.STATE_READY) {
					showLoading(false);
				} else if (playbackState == Player.STATE_BUFFERING) {
					showLoading(true);
				}
			}

			@Override
			public void onPlayerError(PlaybackException error) {
				showLoading(false);
				showError(getString(R.string.video_playback_error));
				wipeVideo();
			}
		});

		player.setMediaSource(new ProgressiveMediaSource.Factory(
				() -> new ByteArrayDataSource(video))
				.createMediaSource(MediaItem.fromUri(Uri.EMPTY)));
		player.setPlayWhenReady(playWhenReady);
		player.seekTo(currentWindow, playbackPosition);
		player.prepare();
	}

	private void releasePlayer() {
		if (player != null) {
			playWhenReady = player.getPlayWhenReady();
			currentWindow = player.getCurrentMediaItemIndex();
			playbackPosition = player.getCurrentPosition();
			player.release();
			player = null;
		}
	}

	private void wipeVideo() {
		byte[] bytes = videoBytes;
		videoBytes = null;
		if (bytes != null) Arrays.fill(bytes, (byte) 0);
	}

	private void showLoading(boolean show) {
		if (loadingIndicator != null) {
			loadingIndicator.setVisibility(show ? View.VISIBLE : View.GONE);
		}
	}

	private void showError(String message) {
		if (errorText != null) {
			errorText.setText(message);
			errorText.setVisibility(View.VISIBLE);
		}
		if (playerView != null) {
			playerView.setVisibility(View.GONE);
		}
	}

	private void hideSystemUi() {
		androidx.core.view.WindowInsetsControllerCompat controller =
				androidx.core.view.WindowCompat.getInsetsController(
						getWindow(), getWindow().getDecorView());
		controller.setSystemBarsBehavior(
				androidx.core.view.WindowInsetsControllerCompat
						.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
		controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
	}
}
