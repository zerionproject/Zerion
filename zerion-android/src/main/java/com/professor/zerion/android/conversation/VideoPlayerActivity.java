package com.professor.zerion.android.conversation;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerView;

import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.briar.api.attachment.Attachment;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.AttachmentReader;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.attachment.AttachmentItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.annotation.Nullable;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class VideoPlayerActivity extends ZerionActivity {

	public static final String ATTACHMENT = "attachment";
	public static final String ITEM_ID = "itemId";

	private static final String TEMP_VIDEO_PREFIX = "zerion_video_";
	private static final String[] SUPPORTED_VIDEO_TYPES = {
			"video/mp4", "video/webm", "video/3gpp", "video/quicktime",
			"video/x-matroska", "video/mpeg", "video/avi"
	};

	@Inject
	AttachmentReader attachmentReader;
	@Inject
	@DatabaseExecutor
	Executor dbExecutor;

	@Nullable
	private ExoPlayer player;
	@Nullable
	private PlayerView playerView;
	@Nullable
	private ProgressBar loadingIndicator;
	@Nullable
	private TextView errorText;
	@Nullable
	private File tempVideoFile;
	@Nullable
	private Uri preparedVideoUri;

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
		getWindow().getDecorView().setSystemUiVisibility(
				View.SYSTEM_UI_FLAG_FULLSCREEN |
				View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
				View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

		setContentView(R.layout.activity_video_player);

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
		cleanupTempFile();
		preparedVideoUri = null;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		cleanupTempFile();
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
					file.delete();
				}
			}
		} catch (SecurityException e) {
		}
	}

	private void initializePlayer() {
		if (isLoadingVideo || player != null) {
			return;
		}

		if (preparedVideoUri != null && tempVideoFile != null && tempVideoFile.exists()) {
			startPlayback(preparedVideoUri);
			return;
		}

		preparedVideoUri = null;

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

		final String finalExt = ext;
		dbExecutor.execute(() -> {
			try {
				AttachmentHeader header = attachment.getHeader();
				Attachment att = attachmentReader.getAttachment(header);
				InputStream is = att.getStream();

				tempVideoFile = File.createTempFile(TEMP_VIDEO_PREFIX, "." + finalExt, getCacheDir());

				FileOutputStream fos = new FileOutputStream(tempVideoFile);
				byte[] buffer = new byte[8192];
				int bytesRead;
				while ((bytesRead = is.read(buffer)) != -1) {
					fos.write(buffer, 0, bytesRead);
				}
				fos.close();
				is.close();

				if (!tempVideoFile.exists() || tempVideoFile.length() == 0) {
					cleanupTempFile();
					throw new Exception();
				}

				Uri videoUri = Uri.fromFile(tempVideoFile);
				preparedVideoUri = videoUri;

				runOnUiThread(() -> {
					isLoadingVideo = false;
					startPlayback(videoUri);
				});

			} catch (Exception e) {
				cleanupTempFile();
				runOnUiThread(() -> {
					isLoadingVideo = false;
					showLoading(false);
					showError(getString(R.string.video_playback_error));
				});
			}
		});
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

	private void startPlayback(Uri videoUri) {
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
				cleanupTempFile();
			}
		});

		MediaItem mediaItem = MediaItem.fromUri(videoUri);
		player.setMediaItem(mediaItem);
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

	private void cleanupTempFile() {
		if (tempVideoFile != null) {
			try {
				if (tempVideoFile.exists()) {
					tempVideoFile.delete();
				}
			} catch (SecurityException e) {
			}
			tempVideoFile = null;
		}
		preparedVideoUri = null;
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
		getWindow().getDecorView().setSystemUiVisibility(
				View.SYSTEM_UI_FLAG_FULLSCREEN |
				View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
				View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
				View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
				View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
				View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
	}
}
