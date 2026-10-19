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
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.app.api.attachment.Attachment;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.AttachmentNotYetAvailableException;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.zerionproject.app.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.attachment.SaveCleanCopy;
import com.professor.zerion.android.util.ExternalHandoff;
import com.professor.zerion.android.util.ExternalViewerTypes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;

import androidx.annotation.Nullable;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
@UnstableApi
public class VideoPlayerActivity extends ZerionActivity
		implements EventListener {

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
	@Inject
	@IoExecutor
	Executor ioExecutor;
	@Inject
	EventBus eventBus;

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
	@Nullable
	private String token;
	@Nullable
	private MessageId message;

	private boolean playWhenReady = true;
	private int currentWindow = 0;
	private long playbackPosition = 0;
	private boolean isLoadingVideo = false;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	protected boolean forceScreenshotProtection() {
		String shown = getIntent().getStringExtra(MediaViewer.EXTRA_TOKEN);
		return shown != null && MediaViewer.secure(shown);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		token = getIntent().getStringExtra(MediaViewer.EXTRA_TOKEN);
		if (token != null) {
			if (!MediaViewer.watch(token, this::contentGone)) {
				finish();
				return;
			}
		} else {
			byte[] id = getIntent().getByteArrayExtra(ITEM_ID);
			if (id != null) {
				message = new MessageId(id);
				eventBus.addListener(this);
			}
		}

		cleanupOrphanedTempFiles();

		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		setContentView(R.layout.activity_video_player);
		hideSystemUi();

		playerView = findViewById(R.id.player_view);
		loadingIndicator = findViewById(R.id.loading_indicator);
		errorText = findViewById(R.id.error_text);

		ImageButton closeButton = findViewById(R.id.close_button);
		closeButton.setOnClickListener(v -> finish());
		ImageButton saveButton = findViewById(R.id.save_button);
		saveButton.setVisibility(SaveCleanCopy.canSave(shownType())
				? View.VISIBLE : View.GONE);
		saveButton.setOnClickListener(v -> save());
		ImageButton openButton = findViewById(R.id.open_elsewhere_button);
		openButton.setOnClickListener(v -> openElsewhere());
		showHeading(MediaViewer.get(token));

		if (savedInstanceState != null) {
			playWhenReady = savedInstanceState.getBoolean("playWhenReady", true);
			currentWindow = savedInstanceState.getInt("currentWindow", 0);
			playbackPosition = savedInstanceState.getLong("playbackPosition", 0);
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		if (token != null && MediaViewer.get(token) == null) {
			contentGone();
			return;
		}
		initializePlayer();
	}

	@Override
	public void onResume() {
		super.onResume();
		if (isFinishing()) return;
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
		if (message != null) eventBus.removeListener(this);
		MediaViewer.unwatch(token);
		if (isFinishing()) MediaViewer.close(token);
	}

	@Override
	public void eventOccurred(Event e) {
		MessageId id = message;
		if (id != null && e instanceof ConversationMessagesDeletedEvent
				&& ((ConversationMessagesDeletedEvent) e).getMessageIds()
				.contains(id)) {
			runOnUiThread(this::contentGone);
		}
	}

	private void contentGone() {
		retryHandler.removeCallbacksAndMessages(null);
		releasePlayer();
		wipeVideo();
		if (playerView != null) playerView.setVisibility(View.GONE);
		if (!isFinishing()) finish();
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

		if (token != null) {
			MediaViewer.Shown shown = MediaViewer.get(token);
			if (shown == null) {
				contentGone();
				return;
			}
			startPlayback(shown.data);
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

	private void showHeading(@Nullable MediaViewer.Shown shown) {
		if (shown == null || shown.title.isEmpty()) return;
		findViewById(R.id.video_heading).setVisibility(View.VISIBLE);
		TextView title = findViewById(R.id.video_title);
		title.setText(shown.title);
		TextView date = findViewById(R.id.video_date);
		if (shown.time > 0) {
			date.setText(com.professor.zerion.android.util.UiUtils
					.formatDateAbsolute(this, shown.time));
		} else {
			date.setVisibility(View.GONE);
		}
	}

	private String shownType() {
		MediaViewer.Shown shown = MediaViewer.get(token);
		if (shown != null) return shown.type;
		AttachmentItem attachment = getIntent().getParcelableExtra(ATTACHMENT);
		return attachment == null ? "" : attachment.getMimeType();
	}

	private void readContent(Consumer<byte[]> ready, int failure) {
		if (token != null) {
			MediaViewer.Shown shown = MediaViewer.get(token);
			if (shown == null) toast(failure);
			else ready.accept(shown.data.clone());
			return;
		}
		AttachmentItem attachment = getIntent().getParcelableExtra(ATTACHMENT);
		if (attachment == null) {
			toast(failure);
			return;
		}
		dbExecutor.execute(() -> {
			byte[] data;
			try {
				Attachment att =
						attachmentReader.getAttachment(attachment.getHeader());
				data = SaveCleanCopy.readAll(att.getStream());
			} catch (Exception | OutOfMemoryError e) {
				data = null;
			}
			byte[] read = data;
			runOnUiThreadUnlessDestroyed(() -> {
				if (read == null) toast(failure);
				else ready.accept(read);
			});
		});
	}

	private void save() {
		String type = shownType();
		SaveCleanCopy.confirm(this, type, () -> readContent(
				data -> SaveCleanCopy.startCopy(this, data, type),
				R.string.save_file_error));
	}

	private void openElsewhere() {
		readContent(data -> {
			String type = ExternalViewerTypes.typeForContent(shownType(),
					data);
			if (type == null) {
				Arrays.fill(data, (byte) 0);
				toast(R.string.video_playback_error);
				return;
			}
			ExternalHandoff.openCopy(this, ioExecutor, data, type,
					() -> toast(R.string.media_document_no_app));
		}, R.string.video_playback_error);
	}

	private void toast(int res) {
		android.widget.Toast.makeText(this, res,
				android.widget.Toast.LENGTH_SHORT).show();
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
