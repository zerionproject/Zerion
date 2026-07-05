package com.professor.zerion.android.grouptr;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.vanniktech.emoji.EmojiEditText;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.grouptr.voice.GroupTrVoicePlayer;
import com.professor.zerion.android.grouptr.voice.GroupTrVoiceRecorder;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.util.StringUtils;
import org.briarproject.briar.api.grouptr.GroupTrBody;
import org.briarproject.briar.api.grouptr.GroupTrManager;
import org.briarproject.briar.api.grouptr.GroupTrPost;
import org.briarproject.briar.api.grouptr.GroupTrState;
import org.briarproject.briar.api.messaging.event.GroupPostReceivedEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.inject.Inject;

public class GroupTrConversationActivity extends ZerionActivity
		implements EventListener {

	public static final String EXTRA_GROUP_ID = "groupTrId";

	@Inject
	GroupTrManager groupTrManager;
	@Inject
	IdentityManager identityManager;
	@Inject
	EventBus eventBus;
	@Inject
	AndroidNotificationManager notificationManager;
	@Inject
	@IoExecutor
	Executor ioExecutor;

	private static final int REQ_RECORD_AUDIO = 4011;
	private static final long MAX_INLINE_MEDIA_BYTES = 8L * 1024L * 1024L;
	private static final int MAX_POST_BODY_BYTES = 9_500_000;
	private static final int MAX_IMAGE_DIM = 1600;
	private static final int IMAGE_JPEG_QUALITY = 78;

	private final ActivityResultLauncher<String[]> mediaPicker =
			registerForActivityResult(
					new ActivityResultContracts.OpenDocument(),
					this::onMediaPicked);

	private byte[] groupId;
	private androidx.recyclerview.widget.RecyclerView postsRecycler;
	private androidx.recyclerview.widget.LinearLayoutManager layoutManager;
	private GroupTrPostAdapter postAdapter;
	private final java.util.Set<String> autoDeleteScheduled =
			new java.util.HashSet<>();
	private TextView emptyState;
	private TextView titleView;
	private TextView subtitleView;
	private TextView avatarView;
	private EmojiEditText input;
	private AppCompatImageButton sendButton;
	private AppCompatImageButton voiceButton;
	private LinearLayout composerRow;
	private LinearLayout recordingOverlay;
	private TextView recordingTimer;
	@Nullable
	private GroupTrVoiceRecorder recorder;
	@Nullable
	private GroupTrVoicePlayer player;
	@Nullable
	private View currentlyPlayingView;
	private final Runnable timerTick = new Runnable() {
		@Override
		public void run() {
			if (recorder == null || !recorder.isRecording()) return;
			long ms = recorder.elapsedMs();
			recordingTimer.setText(formatDuration(ms));
			if (ms >= com.professor.zerion.android.grouptr.voice
					.GroupTrVoiceRecorder.MAX_DURATION_MS) {
				finishVoiceRecording();
				return;
			}
			main.postDelayed(this, 200);
		}
	};
	private final Handler main = new Handler(Looper.getMainLooper());
	private final SimpleDateFormat tsFmt =
			new SimpleDateFormat("HH:mm", Locale.getDefault());
	@Nullable
	private byte[] localPub;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		String hex = getIntent().getStringExtra(EXTRA_GROUP_ID);
		if (hex == null) {
			finish();
			return;
		}
		try {
			groupId = StringUtils.fromHexString(hex);
		} catch (org.briarproject.bramble.api.FormatException ex) {
			finish();
			return;
		}

		getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
		final byte[] gidForLoad = groupId;
		ioExecutor.execute(() -> {
			try {
				boolean blocked = groupTrManager
						.isLocalScreenshotBlocked(gidForLoad);
				if (!blocked) {
					runOnUiThread(() -> getWindow().clearFlags(
							WindowManager.LayoutParams.FLAG_SECURE));
				}
			} catch (org.briarproject.bramble.api.db.DbException ex) {
			}
		});

		setContentView(R.layout.activity_grouptr_conversation);

		MaterialToolbar toolbar = findViewById(R.id.toolbar);
		toolbar.setNavigationOnClickListener(v -> finish());

		titleView = findViewById(R.id.groupTitle);
		subtitleView = findViewById(R.id.groupSubtitle);
		avatarView = findViewById(R.id.groupAvatar);
		postsRecycler = findViewById(R.id.postsRecycler);
		layoutManager = new androidx.recyclerview.widget.LinearLayoutManager(
				this);
		layoutManager.setStackFromEnd(true);
		postsRecycler.setLayoutManager(layoutManager);
		postAdapter = new GroupTrPostAdapter(new PostCallback());
		postsRecycler.setAdapter(postAdapter);
		emptyState = findViewById(R.id.emptyState);
		input = findViewById(R.id.messageInput);
		sendButton = findViewById(R.id.sendButton);
		sendButton.setOnClickListener(v -> onSend());
		updateSendEnabled();
		input.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count,
					int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before,
					int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				updateSendEnabled();
			}
		});
		input.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId
					== android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
				onSend();
				return true;
			}
			return false;
		});
		input.setOnKeyListener((v, keyCode, event) -> {
			if (keyCode == android.view.KeyEvent.KEYCODE_ENTER
					&& event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
				onSend();
				return true;
			}
			return false;
		});

		ImageButton attachmentBtn = findViewById(R.id.attachmentButton);
		attachmentBtn.setOnClickListener(v -> launchMediaPicker());

		ImageButton emojiToggle = findViewById(R.id.emojiToggle);
		emojiToggle.setOnClickListener(v -> input.requestFocus());

		voiceButton = findViewById(R.id.voiceButton);
		voiceButton.setOnClickListener(v -> onVoiceButton());

		composerRow = findViewById(R.id.composerRow);
		recordingOverlay = findViewById(R.id.recordingOverlay);
		recordingTimer = findViewById(R.id.recordingTimer);
		findViewById(R.id.recordingCancel)
				.setOnClickListener(v -> cancelVoiceRecording());
		findViewById(R.id.recordingSend)
				.setOnClickListener(v -> finishVoiceRecording());

		View titleBlock = findViewById(R.id.toolbarTitleBlock);
		titleBlock.setOnClickListener(v -> openSettings());

		ioExecutor.execute(() -> {
			try {
				LocalAuthor la = identityManager.getLocalAuthor();
				localPub = la.getPublicKey().getEncoded();
				GroupTrState s = groupTrManager.getGroup(groupId);
				if (s == null) {
					main.post(this::finish);
					return;
				}
				List<GroupTrPost> posts =
						groupTrManager.getRecentPosts(groupId);
				main.post(() -> {
					updateHeader(s);
					renderPosts(posts);
				});
			} catch (DbException ex) {
				main.post(() -> toast(R.string.grouptr_error_load));
			}
		});
	}

	private void updateHeader(GroupTrState s) {
		String name = s.getName().isEmpty()
				? getString(R.string.grouptr_unnamed_group)
				: s.getName();
		titleView.setText(name);
		String initial = name.isEmpty() ? "?"
				: name.substring(0, 1).toUpperCase();
		avatarView.setText(initial);
		int memberCount = s.getMembers().size();
		String subtitle = getResources().getQuantityString(
				R.plurals.grouptr_member_count, memberCount, memberCount);
		if (s.isDissolved()) {
			subtitle = subtitle + " · "
					+ getString(R.string.grouptr_dissolved_suffix);
		} else {
			subtitle = subtitle + " · "
					+ getString(R.string.grouptr_subtitle_e2ee);
		}
		subtitleView.setText(subtitle);
		bindRemovedBanner(s);
	}

	private void bindRemovedBanner(GroupTrState s) {
		TextView banner = findViewById(R.id.removedBanner);
		boolean stillMember = false;
		if (localPub != null) {
			for (org.briarproject.briar.api.grouptr.GroupTrMember m
					: s.getMembers()) {
				if (Arrays.equals(m.getPubKey(), localPub)) {
					stillMember = true;
					break;
				}
			}
		}
		if (!stillMember && !s.isDissolved()) {
			banner.setText(getString(R.string.grouptr_removed_banner,
					s.getCreatorName()));
			banner.setVisibility(View.VISIBLE);
			composerRow.setVisibility(View.GONE);
			recordingOverlay.setVisibility(View.GONE);
		} else {
			banner.setVisibility(View.GONE);
			if (recorder == null || !recorder.isRecording()) {
				composerRow.setVisibility(View.VISIBLE);
			}
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		eventBus.addListener(this);
		if (groupId != null) {
			notificationManager.blockGroupTrNotification(groupId);
			notificationManager.clearGroupTrPostNotification(groupId);
			byte[] gid = groupId;
			ioExecutor.execute(() -> groupTrManager.markGroupRead(gid));
		}
	}

	@Override
	public void onStop() {
		super.onStop();
		if (recorder != null && recorder.isRecording()) {
			cancelVoiceRecording();
		}
		if (player != null) {
			player.stop();
			currentlyPlayingView = null;
		}
		eventBus.removeListener(this);
		if (groupId != null) {
			notificationManager.unblockGroupTrNotification(groupId);
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		ioExecutor.execute(this::wipeGroupTrViewCache);
	}

	@Override
	public void onDestroy() {
		main.removeCallbacksAndMessages(null);
		ioExecutor.execute(this::wipeGroupTrViewCache);
		super.onDestroy();
	}

	private void wipeGroupTrViewCache() {
		java.io.File dir = new java.io.File(getCacheDir(), "grouptr_view");
		java.io.File[] kids = dir.listFiles();
		if (kids == null) return;
		for (java.io.File f : kids) {
			try {
				long len = f.length();
				if (len > 0) {
					try (java.io.RandomAccessFile raf =
								new java.io.RandomAccessFile(f, "rw")) {
						byte[] zeros = new byte[(int) Math.min(len,
								64L * 1024L)];
						long remaining = len;
						raf.seek(0);
						while (remaining > 0) {
							int n = (int) Math.min(zeros.length, remaining);
							raf.write(zeros, 0, n);
							remaining -= n;
						}
						raf.getFD().sync();
					}
				}
			} catch (java.io.IOException ignored) {
			}
			f.delete();
		}
	}

	@Override
	public void eventOccurred(Event e) {
		if (!(e instanceof GroupPostReceivedEvent)) return;
		GroupPostReceivedEvent ev = (GroupPostReceivedEvent) e;
		if (!Arrays.equals(ev.getGroupId(), groupId)) return;
		main.post(() -> {
			GroupTrPost p = new GroupTrPost(ev.getGroupId(),
					ev.getSenderPubKey(), ev.getSenderName(),
					ev.getCiphertext(), ev.getTimestamp(), ev.getEpoch(),
					false, ev.getAutoDeleteTimerMs());
			if (postAdapter.contains(p)) return;
			boolean pin = isScrolledToBottom();
			postAdapter.addPost(p);
			scheduleAutoDelete(p);
			emptyState.setVisibility(View.GONE);
			if (pin) scrollToBottom();
		});
	}

	@Override
	public boolean onCreateOptionsMenu(android.view.Menu menu) {
		menu.add(0, 1, 0, R.string.grouptr_group_settings)
				.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
		menu.add(0, 2, 0, R.string.grouptr_stealth_name_set)
				.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
		menu.add(0, 3, 0, R.string.grouptr_default_ttl_set)
				.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(android.view.MenuItem item) {
		int id = item.getItemId();
		if (id == 1) {
			openSettings();
			return true;
		} else if (id == 2) {
			showStealthNameDialog();
			return true;
		} else if (id == 3) {
			showTtlDialog();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void openSettings() {
		startActivity(GroupTrAdminActivity.intent(this, groupId));
	}

	private boolean groupRendered = false;

	private void renderPosts(List<GroupTrPost> posts) {
		if (posts.isEmpty()) {
			postAdapter.setPosts(posts);
			emptyState.setVisibility(View.VISIBLE);
			return;
		}
		emptyState.setVisibility(View.GONE);
		boolean pin = !groupRendered || isScrolledToBottom();
		postAdapter.setPosts(posts);
		for (GroupTrPost p : posts) scheduleAutoDelete(p);
		if (pin) scrollToBottom();
		groupRendered = true;
	}

	private boolean isScrolledToBottom() {
		if (postAdapter.getItemCount() == 0) return true;
		return layoutManager.findLastVisibleItemPosition()
				>= postAdapter.getItemCount() - 1;
	}

	private void scrollToBottom() {
		int count = postAdapter.getItemCount();
		if (count > 0) postsRecycler.scrollToPosition(count - 1);
	}

	private String postKey(GroupTrPost p) {
		return p.getEpoch() + ":" + p.getTimestamp() + ":"
				+ Arrays.hashCode(p.getSenderPubKey());
	}

	private void scheduleAutoDelete(GroupTrPost p) {
		long ttl = p.getAutoDeleteTimerMs();
		if (ttl <= 0L) return;
		String key = postKey(p);
		if (!autoDeleteScheduled.add(key)) return;
		long remaining = ttl - (System.currentTimeMillis() - p.getTimestamp());
		if (remaining <= 0L) {
			expirePost(p, key);
			return;
		}
		main.postDelayed(() -> expirePost(p, key), remaining);
	}

	private void expirePost(GroupTrPost p, String key) {
		autoDeleteScheduled.remove(key);
		postAdapter.removePost(p);
		if (postAdapter.getItemCount() == 0) {
			emptyState.setVisibility(View.VISIBLE);
		}
	}

	private class PostCallback implements GroupTrPostAdapter.Callback {
		@Override
		public boolean isMine(GroupTrPost p) {
			return localPub != null
					&& Arrays.equals(p.getSenderPubKey(), localPub);
		}

		@Override
		public void bindSender(TextView tv, GroupTrPost p) {
			GroupTrConversationActivity.this.bindSender(tv, p);
		}

		@Override
		public void onImageClick(byte[] bytes, @Nullable String mime) {
			openMediaFullscreen(bytes, mime, false, ".jpg");
		}

		@Override
		public void onVideoClick(byte[] bytes, @Nullable String mime) {
			openMediaFullscreen(bytes, mime, true, ".mp4");
		}

		@Override
		public void onVoiceClick(AppCompatImageButton btn, byte[] audio) {
			playVoice(btn, audio);
		}

		@Override
		@Nullable
		public Bitmap videoThumb(byte[] videoBytes) {
			return extractVideoThumb(videoBytes);
		}

		@Override
		public String formatTime(long ts) {
			return tsFmt.format(new Date(ts));
		}

		@Override
		public String formatDuration(long ms) {
			return GroupTrConversationActivity.this.formatDuration(ms);
		}
	}

	@Nullable
	private Bitmap extractVideoThumb(byte[] videoBytes) {
		java.io.File tmp = null;
		MediaMetadataRetriever mmr = new MediaMetadataRetriever();
		try {
			tmp = java.io.File.createTempFile("grouptr_vid_thumb_",
					".mp4", getCacheDir());
			try (java.io.FileOutputStream out =
					new java.io.FileOutputStream(tmp)) {
				out.write(videoBytes);
			}
			mmr.setDataSource(tmp.getAbsolutePath());
			return mmr.getFrameAtTime(0);
		} catch (IOException | RuntimeException ex) {
			return null;
		} finally {
			try {
				mmr.release();
			} catch (IOException ignored) {
			}
			if (tmp != null && !tmp.delete()) tmp.deleteOnExit();
		}
	}

	private void openMediaFullscreen(byte[] bytes, @Nullable String mime,
			boolean isVideo, String ext) {
		try {
			java.io.File dir = new java.io.File(getCacheDir(),
					"grouptr_view");
			if (!dir.exists() && !dir.mkdirs()) {
				toast(R.string.grouptr_attach_read_failed);
				return;
			}
			java.io.File tmp = java.io.File.createTempFile(
					"grouptr_view_", ext, dir);
			try (java.io.FileOutputStream out =
					new java.io.FileOutputStream(tmp)) {
				out.write(bytes);
			}
			Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
					getPackageName() + ".fileprovider", tmp);
			Intent i = new Intent(Intent.ACTION_VIEW);
			String type = mime != null ? mime
					: (isVideo ? "video/mp4" : "image/jpeg");
			i.setDataAndType(uri, type);
			i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			startActivity(i);
		} catch (IOException | RuntimeException ex) {
			toast(R.string.grouptr_attach_read_failed);
		}
	}

	private void playVoice(AppCompatImageButton button, byte[] oggOpus) {
		if (player != null && player.isPlaying()) {
			player.stop();
			if (currentlyPlayingView != null) {
				((AppCompatImageButton) currentlyPlayingView).setImageResource(
						R.drawable.ic_play_arrow_24dp);
			}
			if (currentlyPlayingView == button) {
				currentlyPlayingView = null;
				return;
			}
		}
		if (player == null) player = new GroupTrVoicePlayer(this);
		final AppCompatImageButton btn = button;
		currentlyPlayingView = btn;
		player.play(oggOpus, new GroupTrVoicePlayer.Listener() {
			@Override
			public void onStarted() {
				main.post(() -> btn.setImageResource(R.drawable.ic_pause_24dp));
			}

			@Override
			public void onCompleted() {
				main.post(() -> {
					btn.setImageResource(R.drawable.ic_play_arrow_24dp);
					currentlyPlayingView = null;
				});
			}

			@Override
			public void onError() {
				main.post(() -> {
					btn.setImageResource(R.drawable.ic_play_arrow_24dp);
					currentlyPlayingView = null;
					Toast.makeText(GroupTrConversationActivity.this,
							R.string.grouptr_voice_play_failed,
							Toast.LENGTH_SHORT).show();
				});
			}
		});
	}

	private void onSend() {
		String text = input.getText() == null ? ""
				: input.getText().toString().trim();
		if (text.isEmpty()) return;
		byte[] body = text.getBytes(StandardCharsets.UTF_8);
		input.setText("");
		sendBodyAsync(body);
	}

	private void showStealthNameDialog() {
		final EditText editor = new EditText(this);
		editor.setInputType(InputType.TYPE_CLASS_TEXT);
		ioExecutor.execute(() -> {
			try {
				String current = groupTrManager.getStealthName(groupId);
				main.post(() -> {
					if (isFinishing() || isDestroyed()) return;
					if (current != null) editor.setText(current);
					new AlertDialog.Builder(this)
							.setTitle(R.string.grouptr_stealth_name_set)
							.setMessage(R.string.grouptr_stealth_name_msg)
							.setView(editor)
							.setPositiveButton(android.R.string.ok,
									(d, w) -> {
										String v = editor.getText()
												.toString().trim();
										persistStealth(
												v.isEmpty() ? null : v);
									})
							.setNeutralButton(R.string.grouptr_stealth_clear,
									(d, w) -> persistStealth(null))
							.setNegativeButton(android.R.string.cancel, null)
							.show();
				});
			} catch (DbException ex) {
				main.post(() -> toast(R.string.grouptr_error_load));
			}
		});
	}

	private void updateSendEnabled() {
		boolean ready = input != null && input.getText() != null
				&& input.getText().toString().trim().length() > 0;
		if (sendButton != null) {
			sendButton.setEnabled(ready);
			sendButton.setVisibility(ready ? View.VISIBLE : View.INVISIBLE);
		}
		if (voiceButton != null) {
			voiceButton.setVisibility(ready ? View.INVISIBLE : View.VISIBLE);
		}
	}

	private void onVoiceButton() {
		if (ContextCompat.checkSelfPermission(this,
				Manifest.permission.RECORD_AUDIO)
				!= PackageManager.PERMISSION_GRANTED) {
			requestPermissions(
					new String[]{Manifest.permission.RECORD_AUDIO},
					REQ_RECORD_AUDIO);
			return;
		}
		startVoiceRecording();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode,
			@NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions,
				grantResults);
		if (requestCode == REQ_RECORD_AUDIO) {
			if (grantResults.length > 0
					&& grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				startVoiceRecording();
			} else {
				Toast.makeText(this,
						R.string.grouptr_record_permission_required,
						Toast.LENGTH_SHORT).show();
			}
		}
	}

	private void startVoiceRecording() {
		if (recorder == null) recorder = new GroupTrVoiceRecorder(this);
		if (!recorder.start()) {
			Toast.makeText(this, R.string.grouptr_voice_start_failed,
					Toast.LENGTH_SHORT).show();
			return;
		}
		composerRow.setVisibility(View.GONE);
		recordingOverlay.setVisibility(View.VISIBLE);
		recordingTimer.setText(formatDuration(0L));
		main.post(timerTick);
	}

	private void cancelVoiceRecording() {
		if (recorder != null) recorder.cancel();
		exitRecordingUi();
	}

	private void finishVoiceRecording() {
		if (recorder == null || !recorder.isRecording()) {
			exitRecordingUi();
			return;
		}
		recorder.stop(new GroupTrVoiceRecorder.Listener() {
			@Override
			public void onRecordingFinished(byte[] oggOpus, long durationMs) {
				exitRecordingUi();
				byte[] body = GroupTrBody.encodeVoice(oggOpus, durationMs);
				sendBodyAsync(body);
			}

			@Override
			public void onRecordingFailed(String reason) {
				exitRecordingUi();
				Toast.makeText(GroupTrConversationActivity.this,
						R.string.grouptr_voice_send_failed,
						Toast.LENGTH_SHORT).show();
			}
		});
	}

	private void exitRecordingUi() {
		main.removeCallbacks(timerTick);
		recordingOverlay.setVisibility(View.GONE);
		composerRow.setVisibility(View.VISIBLE);
	}

	private void launchMediaPicker() {
		try {
			mediaPicker.launch(new String[]{"image/*", "video/*"});
		} catch (RuntimeException ex) {
			toast(R.string.grouptr_attach_picker_failed);
		}
	}

	private void onMediaPicked(@Nullable Uri uri) {
		if (uri == null) return;
		ioExecutor.execute(() -> processPickedMedia(uri));
	}

	private void processPickedMedia(Uri uri) {
		ContentResolver cr = getContentResolver();
		String mime = cr.getType(uri);
		if (mime == null) {
			main.post(() -> toast(R.string.grouptr_attach_unknown_type));
			return;
		}
		if (mime.startsWith("image/")) {
			byte[] jpeg = readImageDownscaledJpeg(uri);
			if (jpeg == null) {
				main.post(() -> toast(R.string.grouptr_attach_read_failed));
				return;
			}
			byte[] body = GroupTrBody.encodeImage(jpeg, "image/jpeg");
			sendBodyAsync(body);
			return;
		}
		if (mime.startsWith("video/")) {
			byte[] data = readUriBytes(uri, MAX_INLINE_MEDIA_BYTES);
			if (data == null) {
				main.post(() -> toast(R.string.grouptr_attach_video_too_large));
				return;
			}
			long durationMs = probeVideoDurationMs(uri);
			byte[] body = GroupTrBody.encodeVideo(data, mime, durationMs);
			sendBodyAsync(body);
			return;
		}
		main.post(() -> toast(R.string.grouptr_attach_unknown_type));
	}

	@Nullable
	private byte[] readImageDownscaledJpeg(Uri uri) {
		ContentResolver cr = getContentResolver();
		BitmapFactory.Options probe = new BitmapFactory.Options();
		probe.inJustDecodeBounds = true;
		try (InputStream in = cr.openInputStream(uri)) {
			if (in == null) return null;
			BitmapFactory.decodeStream(in, null, probe);
		} catch (IOException ex) {
			return null;
		}
		int sample = 1;
		while ((probe.outWidth / sample) > MAX_IMAGE_DIM
				|| (probe.outHeight / sample) > MAX_IMAGE_DIM) {
			sample *= 2;
		}
		BitmapFactory.Options opts = new BitmapFactory.Options();
		opts.inSampleSize = sample;
		Bitmap bitmap;
		try (InputStream in = cr.openInputStream(uri)) {
			if (in == null) return null;
			bitmap = BitmapFactory.decodeStream(in, null, opts);
		} catch (IOException ex) {
			return null;
		}
		if (bitmap == null) return null;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG,
				IMAGE_JPEG_QUALITY, out);
		bitmap.recycle();
		return ok ? out.toByteArray() : null;
	}

	@Nullable
	private byte[] readUriBytes(Uri uri, long maxBytes) {
		ContentResolver cr = getContentResolver();
		try (InputStream in = cr.openInputStream(uri);
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			if (in == null) return null;
			byte[] buf = new byte[16 * 1024];
			long total = 0;
			while (true) {
				int n = in.read(buf);
				if (n < 0) break;
				total += n;
				if (total > maxBytes) return null;
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		} catch (IOException ex) {
			return null;
		}
	}

	private long probeVideoDurationMs(Uri uri) {
		MediaMetadataRetriever mmr = new MediaMetadataRetriever();
		try {
			mmr.setDataSource(this, uri);
			String d = mmr.extractMetadata(
					MediaMetadataRetriever.METADATA_KEY_DURATION);
			return d == null ? 0L : Long.parseLong(d);
		} catch (RuntimeException ex) {
			return 0L;
		} finally {
			try {
				mmr.release();
			} catch (IOException ignored) {
			}
		}
	}

	private void sendBodyAsync(byte[] body) {
		if (body.length > MAX_POST_BODY_BYTES) {
			main.post(() -> toast(R.string.grouptr_attach_video_too_large));
			return;
		}
		ioExecutor.execute(() -> {
			try {
				groupTrManager.sendGroupPost(groupId, body, 0L);
				main.post(() -> {
					try {
						List<GroupTrPost> posts =
								groupTrManager.getRecentPosts(groupId);
						renderPosts(posts);
					} catch (Exception ignored) {
					}
				});
			} catch (DbException ex) {
				main.post(() -> toast(R.string.grouptr_error_send));
			}
		});
	}

	private static String formatDuration(long ms) {
		long total = ms / 1000L;
		long m = total / 60L;
		long s = total % 60L;
		return String.format(Locale.US, "%d:%02d", m, s);
	}

	private void persistStealth(@Nullable String alias) {
		ioExecutor.execute(() -> {
			try {
				groupTrManager.setStealthName(groupId, alias);
			} catch (DbException ex) {
				main.post(() -> toast(R.string.grouptr_error_save));
			}
		});
	}

	private void showTtlDialog() {
		final String[] labels = {
				getString(R.string.grouptr_ttl_off),
				getString(R.string.grouptr_ttl_5min),
				getString(R.string.grouptr_ttl_1hr),
				getString(R.string.grouptr_ttl_1day),
				getString(R.string.grouptr_ttl_7days),
				getString(R.string.grouptr_ttl_30days),
		};
		final long[] values = {
				0L, 5L * 60 * 1000, 60L * 60 * 1000,
				24L * 60 * 60 * 1000, 7L * 24 * 60 * 60 * 1000,
				30L * 24L * 60 * 60 * 1000
		};
		new AlertDialog.Builder(this)
				.setTitle(R.string.grouptr_default_ttl_set)
				.setItems(labels, (d, which) -> {
					long v = values[which];
					ioExecutor.execute(() -> {
						try {
							groupTrManager.setGroupAutoDeleteTimer(
									groupId, v);
							main.post(() -> toast(
									R.string.grouptr_default_ttl_saved));
						} catch (DbException ex) {
							main.post(() -> toast(
									R.string.grouptr_error_save));
						}
					});
				})
				.show();
	}

	private void toast(int res) {
		Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
	}

	private void bindSender(TextView sender,
			org.briarproject.briar.api.grouptr.GroupTrPost p) {
		sender.setText(decorateName(p));
		sender.setOnClickListener(v -> showFingerprintDialog(p));
	}

	private void showFingerprintDialog(
			org.briarproject.briar.api.grouptr.GroupTrPost p) {
		String fp = com.professor.zerion.android.contact.identity
				.IdentityFingerprint.forSigningPub(p.getSenderPubKey());
		new com.google.android.material.dialog.MaterialAlertDialogBuilder(
				this, R.style.ZerionDialogTheme)
				.setTitle(decorateName(p))
				.setMessage(getString(R.string.grouptr_member_key_message, fp))
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	private static String decorateName(
			org.briarproject.briar.api.grouptr.GroupTrPost p) {
		String name = p.getSenderName();
		String suffix = shortKeyId(p.getSenderPubKey());
		return name == null || name.isEmpty()
				? "· " + suffix
				: name + " · " + suffix;
	}

	private static String shortKeyId(byte[] pubKey) {
		try {
			java.security.MessageDigest md =
					java.security.MessageDigest.getInstance("SHA-256");
			byte[] h = md.digest(pubKey);
			StringBuilder sb = new StringBuilder(8);
			for (int i = 0; i < 4; i++) {
				sb.append(String.format(java.util.Locale.US,
						"%02x", h[i]));
			}
			return sb.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			return "????????";
		}
	}

	public static Intent intent(android.content.Context ctx, byte[] gid) {
		Intent i = new Intent(ctx, GroupTrConversationActivity.class);
		i.putExtra(EXTRA_GROUP_ID, StringUtils.toHexString(gid));
		return i;
	}
}
