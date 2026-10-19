package com.professor.zerion.android.grouptr;

import com.professor.zerion.android.vault.utils.SecureMemory;

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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;

import com.google.android.material.appbar.MaterialToolbar;
import com.vanniktech.emoji.EmojiEditText;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.conversation.MediaViewer;
import com.professor.zerion.android.conversation.SwipeToReplyCallback;
import com.professor.zerion.android.conversation.TextForwarder;
import com.professor.zerion.android.grouptr.voice.GroupTrVoicePlayer;
import com.professor.zerion.android.grouptr.voice.GroupTrVoiceRecorder;
import com.professor.zerion.android.message.MessageState;
import com.professor.zerion.android.message.PendingSends;
import com.professor.zerion.android.selection.MessageInfo;
import com.professor.zerion.android.selection.RowActions;
import com.professor.zerion.android.selection.SelectionAction;
import com.professor.zerion.android.selection.SelectionBar;
import com.professor.zerion.android.selection.SelectionRows;
import com.professor.zerion.android.util.Feedback;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.util.StringUtils;
import org.zerionproject.app.api.grouptr.GroupTrBody;
import com.professor.zerion.android.attachment.SaveCleanCopy;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrPostDelivery;
import org.zerionproject.app.api.grouptr.GroupTrSendResult;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.messaging.event.GroupPostReceivedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrLocalStateChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrOwnPostStateChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrPostAcceptedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrPostsRemovedEvent;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;

public class GroupTrConversationActivity extends ZerionActivity
		implements EventListener, SelectionBar.Host {

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
	ContactManager contactManager;
	@Inject
	@IoExecutor
	Executor ioExecutor;
	@Inject
	TextForwarder textForwarder;

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
	@Nullable
	private byte[] localPub;
	private volatile boolean groupBlocksScreenshots = true;
	private final Object reloadLock = new Object();
	private boolean reloadRunning = false;
	private boolean reloadAgain = false;
	private volatile java.util.Map<String, byte[]> knownMlDsaKeys =
			new java.util.HashMap<>();
	private volatile java.util.Map<String, String> knownNames =
			new java.util.HashMap<>();
	private final java.util.concurrent.atomic.AtomicLong stateReads =
			new java.util.concurrent.atomic.AtomicLong();
	private final Set<String> statesWanted = new java.util.HashSet<>();
	private boolean statesQueued = false;
	@Nullable
	private volatile GroupTrState lastState;
	private SelectionBar selectionBar;
	private SwipeToReplyCallback swipeCallback;
	private final LinkedHashSet<String> selected = new LinkedHashSet<>();
	@Nullable
	private GroupTrPost replyingTo;
	private View replyPreview;
	private boolean stillMember = true;
	private volatile boolean waitingForAdmin = false;
	private final PendingSends pendingSends = PendingSends.get();
	private String pendingScope = "";
	private final PendingSends.Listener pendingListener =
			this::onPendingChanged;
	private final PendingSends.ClearListener clearListener =
			prefixes -> {
				if (PendingSends.affects(prefixes, pendingScope)) {
					main.post(this::onPendingCleared);
				}
			};
	@Nullable
	private com.google.android.material.snackbar.Snackbar undoBar;
	private final List<PendingSends.Entry> undoable = new ArrayList<>();

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	protected boolean forceScreenshotProtection() {
		return groupBlocksScreenshots;
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
		} catch (org.zerionproject.core.api.FormatException ex) {
			finish();
			return;
		}
		pendingScope = PendingSends.groupScope(groupId);
		pendingSends.addClearListener(clearListener);

		getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
		final byte[] gidForLoad = groupId;
		ioExecutor.execute(() -> {
			try {
				boolean blocked = groupTrManager
						.isLocalScreenshotBlocked(gidForLoad);
				groupBlocksScreenshots = blocked;
				if (!blocked) {
					runOnUiThread(() -> securityManager
							.applyScreenshotProtection(this,
									forceScreenshotProtection()));
				}
			} catch (org.zerionproject.core.api.db.DbException ex) {
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
		selectionBar = new SelectionBar(this, this);
		SelectionRows.clearOnEmptyTap(postsRecycler, selectionBar);
		swipeCallback = new SwipeToReplyCallback(position -> {
			GroupTrPost p = postAdapter.postAt(position);
			if (p != null && canReplyTo(p)) {
				showReplyPreview(p);
				focusComposer();
			}
		}, position -> {
			GroupTrPost p = postAdapter.postAt(position);
			return p != null && canReplyTo(p);
		});
		new ItemTouchHelper(swipeCallback).attachToRecyclerView(postsRecycler);
		replyPreview = findViewById(R.id.groupReplyPreview);
		replyPreview.setBackgroundResource(R.drawable.bg_input_bar);
		replyPreview.findViewById(R.id.cancel_reply)
				.setOnClickListener(v -> {
					hideReplyPreview();
					takeBackReturned();
				});
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

		requestReload();
	}

	private void requestReload() {
		synchronized (reloadLock) {
			if (reloadRunning) {
				reloadAgain = true;
				return;
			}
			reloadRunning = true;
		}
		ioExecutor.execute(this::reloadLoop);
	}

	private void reloadLoop() {
		while (true) {
			synchronized (reloadLock) {
				reloadAgain = false;
			}
			reloadOnce();
			synchronized (reloadLock) {
				if (!reloadAgain) {
					reloadRunning = false;
					return;
				}
			}
		}
	}

	private void reloadOnce() {
		byte[] gid = groupId;
		try {
			if (localPub == null) {
				LocalAuthor la = identityManager.getLocalAuthor();
				localPub = la.getPublicKey().getEncoded();
			}
			GroupTrState s = groupTrManager.getGroup(gid);
			if (s == null) {
				main.post(this::finish);
				return;
			}
			List<GroupTrPost> posts = groupTrManager.getRecentPosts(gid);
			java.util.Map<String, String> old = knownNames;
			java.util.Map<String, String> fresh = loadKnownNames(s, posts);
			knownNames = fresh;
			boolean renamed = !old.isEmpty() && !fresh.equals(old);
			waitingForAdmin = groupTrManager.isWaitingForAdmin(gid);
			List<GroupTrMember> outOfReach =
					groupTrManager.getMembersOutOfReach(gid);
			long seq = stateReads.incrementAndGet();
			java.util.Map<String, MessageState> states = ownPostStates(gid);
			GroupTrPostAdapter.Update update = postAdapter.prepare(posts);
			main.post(() -> {
				lastState = s;
				updateHeader(s);
				bindReachBanner(s, outOfReach);
				if (states != null) postAdapter.setDeliveries(states, seq);
				renderPosts(posts, update, renamed);
			});
		} catch (DbException | RuntimeException ex) {
			main.post(() -> toast(R.string.grouptr_error_load));
		}
	}

	static java.util.Map<String, MessageState> deliveryStates(
			java.util.Map<String, GroupTrPostDelivery> deliveries) {
		java.util.Map<String, MessageState> out = new java.util.HashMap<>();
		for (java.util.Map.Entry<String, GroupTrPostDelivery> e
				: deliveries.entrySet()) {
			switch (e.getValue()) {
				case DELIVERED:
					out.put(e.getKey(), MessageState.DELIVERED);
					break;
				case SENT:
					out.put(e.getKey(), MessageState.SENT);
					break;
				default:
					out.put(e.getKey(), MessageState.WAITING);
					break;
			}
		}
		return out;
	}

	@Nullable
	private java.util.Map<String, MessageState> ownPostStates(byte[] gid) {
		try {
			return deliveryStates(groupTrManager.getOwnPostDeliveries(gid));
		} catch (DbException | RuntimeException ex) {
			return null;
		}
	}

	private void requestStates(List<byte[]> postIds) {
		byte[] gid = groupId;
		if (gid == null || postIds.isEmpty()) return;
		synchronized (statesWanted) {
			for (byte[] id : postIds) {
				statesWanted.add(StringUtils.toHexString(id));
			}
			if (statesQueued) return;
			statesQueued = true;
		}
		ioExecutor.execute(() -> {
			Set<String> keys;
			synchronized (statesWanted) {
				keys = new java.util.HashSet<>(statesWanted);
				statesWanted.clear();
				statesQueued = false;
			}
			List<byte[]> ids = new ArrayList<>(keys.size());
			for (String key : keys) {
				try {
					ids.add(StringUtils.fromHexString(key));
				} catch (org.zerionproject.core.api.FormatException ex) {
				}
			}
			long seq = stateReads.incrementAndGet();
			java.util.Map<String, MessageState> states;
			try {
				states = deliveryStates(
						groupTrManager.getOwnPostDeliveries(gid, ids));
			} catch (DbException | RuntimeException ex) {
				synchronized (statesWanted) {
					statesWanted.addAll(keys);
				}
				return;
			}
			main.post(() -> {
				if (!isDestroyed()) {
					postAdapter.updateDeliveries(states, keys, seq);
				}
			});
		});
	}

	private java.util.Map<String, String> loadKnownNames(GroupTrState s,
			List<GroupTrPost> posts) {
		java.util.Map<String, String> names = new java.util.HashMap<>();
		java.util.Map<String, byte[]> mlDsa = new java.util.HashMap<>();
		for (GroupTrMember m : s.getMembers()) {
			String key = StringUtils.toHexString(m.getPubKey());
			if (!m.getName().isEmpty()) names.put(key, m.getName());
			byte[] memberMlDsa = m.getMlDsaPubKey();
			if (memberMlDsa != null && memberMlDsa.length > 0) {
				mlDsa.put(key, memberMlDsa);
			}
		}
		try {
			for (Contact c : contactManager.getContacts()) {
				String name = c.getAlias() != null ? c.getAlias()
						: c.getAuthor().getName();
				String key = StringUtils.toHexString(
						c.getAuthor().getPublicKey().getEncoded());
				names.put(key, name);
				if (c.getMlDsaSigPublicKey() != null) {
					mlDsa.put(key, c.getMlDsaSigPublicKey());
				}
			}
			byte[] localMlDsa = identityManager.getLocalMlDsaSigPublicKey();
			if (localPub != null && localMlDsa != null) {
				mlDsa.put(StringUtils.toHexString(localPub), localMlDsa);
			}
		} catch (DbException | RuntimeException ex) {
		}
		knownMlDsaKeys = mlDsa;
		if (localPub != null) {
			names.put(StringUtils.toHexString(localPub),
					getString(R.string.grouptr_member_role_you));
		}
		return GroupTrSenderLabel.tellApart(names, s.getMembers(),
				GroupTrSenderLabel.firstPosts(posts),
				getString(R.string.grouptr_member_joined_format),
				getString(R.string.grouptr_member_first_wrote_format),
				at -> com.professor.zerion.android.util.UiUtils
						.formatDateFull(this, at));
	}

	private void bindReachBanner(GroupTrState s,
			List<GroupTrMember> outOfReach) {
		TextView banner = findViewById(R.id.reachBanner);
		if (banner == null) return;
		if (outOfReach.isEmpty() || s.isDissolved()) {
			banner.setVisibility(View.GONE);
			return;
		}
		int n = outOfReach.size();
		banner.setText(getResources().getQuantityString(
				R.plurals.grouptr_reach_banner, n, n));
		banner.setVisibility(View.VISIBLE);
		StringBuilder names = new StringBuilder();
		for (GroupTrMember m : outOfReach) {
			if (names.length() > 0) names.append('\n');
			names.append(m.getName().isEmpty()
					? getString(R.string.grouptr_member_unnamed)
					: m.getName());
		}
		String list = names.toString();
		banner.setOnClickListener(v -> new SecureAlertDialogBuilder(this)
				.setTitle(R.string.grouptr_reach_title)
				.setMessage(getString(R.string.grouptr_reach_explanation,
						list))
				.setPositiveButton(android.R.string.ok, null)
				.show());
	}

	private void updateHeader(GroupTrState s) {
		String name = s.getName().isEmpty()
				? getString(R.string.grouptr_unnamed_group)
				: s.getName();
		titleView.setText(name);
		String initial = name.isEmpty() ? "?"
				: new String(Character.toChars(name.codePointAt(0)))
						.toUpperCase();
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
			for (org.zerionproject.app.api.grouptr.GroupTrMember m
					: s.getMembers()) {
				if (Arrays.equals(m.getPubKey(), localPub)) {
					stillMember = true;
					break;
				}
			}
		}
		this.stillMember = stillMember && !s.isDissolved() && !waitingForAdmin;
		if (!this.stillMember) hideReplyPreview();
		if (s.isDissolved()) {
			banner.setText(R.string.groups_group_is_dissolved);
			banner.setVisibility(View.VISIBLE);
			composerRow.setVisibility(View.GONE);
			recordingOverlay.setVisibility(View.GONE);
		} else if (waitingForAdmin) {
			banner.setText(getString(R.string.grouptr_waiting_for_admin,
					s.getCreatorName()));
			banner.setVisibility(View.VISIBLE);
			composerRow.setVisibility(View.GONE);
			recordingOverlay.setVisibility(View.GONE);
		} else if (!stillMember) {
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
		postAdapter.refreshPending();
	}

	@Override
	public void onStart() {
		super.onStart();
		eventBus.addListener(this);
		pendingSends.addListener(pendingListener, pendingScope);
		if (groupId != null) {
			notificationManager.blockGroupTrNotification(groupId);
			notificationManager.clearGroupTrPostNotification(groupId);
			byte[] gid = groupId;
			ioExecutor.execute(() -> groupTrManager.markGroupRead(gid));
			requestReload();
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
		pendingSends.removeListener(pendingListener);
		if (groupId != null) {
			notificationManager.unblockGroupTrNotification(groupId);
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		if (postAdapter != null) {
			postAdapter.setMessageStyle(com.professor.zerion.android.settings.MessageStyle.read(this));
		}
		ioExecutor.execute(this::wipeGroupTrViewCache);
	}

	@Override
	public void onDestroy() {
		pendingSends.removeClearListener(clearListener);
		main.removeCallbacksAndMessages(null);
		ioExecutor.execute(this::wipeGroupTrViewCache);
		super.onDestroy();
	}

	private void wipeGroupTrViewCache() {
		SecureMemory.secureDeleteDir(
				new java.io.File(getCacheDir(), "grouptr_view"), 0L);
	}

	@Override
	public void eventOccurred(Event e) {
		byte[] gid;
		if (e instanceof GroupTrPostAcceptedEvent) {
			gid = ((GroupTrPostAcceptedEvent) e).getGroupId();
		} else if (e instanceof GroupTrLocalStateChangedEvent) {
			gid = ((GroupTrLocalStateChangedEvent) e).getGroupId();
		} else if (e instanceof GroupPostReceivedEvent) {
			gid = ((GroupPostReceivedEvent) e).getGroupId();
		} else if (e instanceof GroupTrPostsRemovedEvent) {
			GroupTrPostsRemovedEvent r = (GroupTrPostsRemovedEvent) e;
			if (!Arrays.equals(r.getGroupId(), groupId)) return;
			main.post(() -> onPostsRemoved(r.getPostIds()));
			gid = r.getGroupId();
		} else if (e instanceof GroupTrOwnPostStateChangedEvent) {
			GroupTrOwnPostStateChangedEvent c =
					(GroupTrOwnPostStateChangedEvent) e;
			if (Arrays.equals(c.getGroupId(), groupId)) {
				requestStates(c.getPostIds());
			}
			return;
		} else {
			return;
		}
		if (!Arrays.equals(gid, groupId)) return;
		requestReload();
	}

	public static void evictRemovedMedia(byte[] groupId,
			Collection<byte[]> postIds) {
		GroupTrMediaCache.evictPosts(groupId, postIds);
	}

	private void onPostsRemoved(List<byte[]> postIds) {
		GroupTrMediaCache.evictPosts(groupId, postIds);
		List<String> shown = new ArrayList<>(postIds.size());
		for (byte[] id : postIds) {
			shown.add(MediaViewer.groupPost(groupId,
					StringUtils.toHexString(id)));
		}
		MediaViewer.forgetPosts(shown);
		postAdapter.removePosts(postIds);
		GroupTrPost quoting = replyingTo;
		for (byte[] id : postIds) {
			String key = StringUtils.toHexString(id);
			if (quoting != null && key.equals(GroupTrPostAdapter.key(quoting))) {
				hideReplyPreview();
			}
			selected.remove(key);
		}
		updateSelectionBar();
		if (postAdapter.getItemCount() == 0) {
			emptyState.setVisibility(View.VISIBLE);
		}
	}

	@Override
	public Set<SelectionAction> offeredActions() {
		return offeredFor(selectedPosts(), true);
	}

	private EnumSet<SelectionAction> offeredFor(List<GroupTrPost> chosen,
			boolean save) {
		EnumSet<SelectionAction> offered =
				EnumSet.noneOf(SelectionAction.class);
		if (chosen.isEmpty()) return offered;
		boolean text = true;
		boolean timed = false;
		for (GroupTrPost p : chosen) {
			if (!isStored(p)) return offered;
			if (postText(p) == null) text = false;
			if (p.getAutoDeleteTimerMs() > 0) timed = true;
		}
		offered.add(SelectionAction.DELETE);
		if (text) {
			offered.add(SelectionAction.COPY);
			if (!timed) offered.add(SelectionAction.FORWARD);
		}
		if (chosen.size() == 1) {
			GroupTrPost p = chosen.get(0);
			if (canReplyTo(p)) offered.add(SelectionAction.REPLY);
			if (save && savableMedia(p) != null) {
				offered.add(SelectionAction.SAVE);
			}
			offered.add(SelectionAction.INFO);
		}
		return offered;
	}

	private Set<SelectionAction> rowActions(GroupTrPost p) {
		if (selectionBar.isShowing() || !isStored(p)) return RowActions.none();
		return RowActions.forRow(offeredFor(
				java.util.Collections.singletonList(p), false));
	}

	private boolean onRowAction(GroupTrPost p, SelectionAction action) {
		if (!rowActions(p).contains(action)) return false;
		return selectionBar.performOnly(() -> startOrToggleSelection(p),
				action, selected::size);
	}

	@Override
	public void onSelectionAction(SelectionAction action) {
		switch (action) {
			case REPLY:
				replyToSelectedPost();
				break;
			case COPY:
				copySelectedPosts();
				break;
			case FORWARD:
				forwardSelectedPosts();
				break;
			case DELETE:
				deleteSelectedPosts();
				break;
			case SAVE:
				saveSelectedPost();
				break;
			case INFO:
				showSelectedInfo();
				break;
			default:
				break;
		}
	}

	@Override
	public void onSelectionClosed() {
		List<String> keys = new ArrayList<>(selected);
		selected.clear();
		for (String key : keys) postAdapter.refreshSelection(key);
	}

	private static boolean isStored(GroupTrPost p) {
		return !(p instanceof PendingGroupPost) && p.getPostId() != null;
	}

	@Nullable
	private static String postText(GroupTrPost p) {
		if (!isTextPost(p)) return null;
		String text = GroupTrBody.parse(p.getBody()).text;
		return text == null || text.isEmpty() ? null : text;
	}

	private void updateSelectionBar() {
		if (selected.isEmpty()) {
			selectionBar.close();
		} else {
			selectionBar.show(selected.size());
		}
	}

	private void dropVanishedFromSelection() {
		if (selected.isEmpty()) return;
		boolean changed = false;
		for (String key : new ArrayList<>(selected)) {
			if (postAdapter.find(key) == null) {
				selected.remove(key);
				changed = true;
			}
		}
		if (changed) updateSelectionBar();
	}

	@Nullable
	private static GroupTrBody.Parsed savableMedia(GroupTrPost p) {
		GroupTrBody.Kind kind = GroupTrBody.kindOf(p.getBody());
		if (kind != GroupTrBody.Kind.IMAGE && kind != GroupTrBody.Kind.VIDEO) {
			return null;
		}
		GroupTrBody.Parsed parsed = GroupTrBody.parse(p.getBody());
		if (parsed.payload == null || parsed.payload.length == 0) return null;
		return parsed;
	}

	private void saveSelectedPost() {
		List<GroupTrPost> chosen = selectedPosts();
		finishSelection();
		if (chosen.size() != 1) return;
		GroupTrBody.Parsed parsed = savableMedia(chosen.get(0));
		if (parsed == null) return;
		String mimeType = parsed.mime != null && !parsed.mime.isEmpty()
				? parsed.mime
				: parsed.kind == GroupTrBody.Kind.VIDEO
						? "video/mp4" : "image/jpeg";
		if (!SaveCleanCopy.canSave(mimeType)) return;
		SaveCleanCopy.confirm(this, mimeType,
				() -> SaveCleanCopy.start(this, parsed.payload, mimeType));
	}

	private void startOrToggleSelection(GroupTrPost p) {
		if (!isStored(p)) return;
		String key = GroupTrPostAdapter.key(p);
		if (!selectionBar.isShowing()) {
			selected.clear();
			selected.add(key);
			postAdapter.refreshSelection(key);
			selectionBar.show(1);
			return;
		}
		toggleSelection(key);
	}

	private void toggleSelection(String key) {
		GroupTrPost p = postAdapter.find(key);
		if (p == null || !isStored(p)) return;
		if (!selected.remove(key)) selected.add(key);
		postAdapter.refreshSelection(key);
		updateSelectionBar();
	}

	private List<GroupTrPost> selectedPosts() {
		List<GroupTrPost> out = new ArrayList<>(selected.size());
		for (GroupTrPost p : postAdapter.currentPosts()) {
			if (selected.contains(GroupTrPostAdapter.key(p))) out.add(p);
		}
		return out;
	}

	private static boolean isTextPost(GroupTrPost p) {
		GroupTrBody.Kind kind = GroupTrBody.kindOf(p.getBody());
		return kind == GroupTrBody.Kind.TEXT || kind == GroupTrBody.Kind.REPLY;
	}

	private boolean isMine(GroupTrPost p) {
		return localPub != null && Arrays.equals(p.getSenderPubKey(), localPub);
	}

	private boolean canReplyTo(GroupTrPost p) {
		return p.getPostId() != null && stillMember
				&& composerRow.getVisibility() == View.VISIBLE;
	}

	private void finishSelection() {
		if (selectionBar.isShowing()) {
			selectionBar.close();
		} else {
			onSelectionClosed();
		}
	}

	private void replyToSelectedPost() {
		List<GroupTrPost> chosen = selectedPosts();
		finishSelection();
		if (chosen.size() != 1 || !canReplyTo(chosen.get(0))) return;
		showReplyPreview(chosen.get(0));
		focusComposer();
	}

	private String selectedText() {
		StringBuilder sb = new StringBuilder();
		for (GroupTrPost p : selectedPosts()) {
			String text = postText(p);
			if (text == null) continue;
			if (sb.length() > 0) sb.append("\n");
			sb.append(text);
		}
		return sb.toString();
	}

	private void copySelectedPosts() {
		String text = selectedText();
		if (text.length() > 0) {
			com.professor.zerion.android.util.SecureClipboard.copy(this,
					"message", text);
			toast(R.string.copied_to_clipboard);
		}
		finishSelection();
	}

	private void forwardSelectedPosts() {
		String text = selectedText();
		finishSelection();
		if (text.isEmpty()) return;
		textForwarder.pickAndForward(this, text, null);
	}

	private void showSelectedInfo() {
		List<GroupTrPost> chosen = selectedPosts();
		finishSelection();
		if (chosen.size() != 1) return;
		GroupTrPost p = chosen.get(0);
		MessageInfo info = new MessageInfo(this);
		boolean mine = isMine(p);
		if (!mine) info.line(R.string.message_info_from, senderLabel(p));
		info.line(R.string.message_info_sent,
				com.professor.zerion.android.util.UiUtils.formatDateAbsolute(
						this, p.getTimestamp()));
		MessageState delivery = mine ? postAdapter.deliveryOf(
				GroupTrPostAdapter.key(p)) : null;
		if (delivery != null) {
			info.line(R.string.message_info_status,
					getString(delivery.getLabel()));
		}
		if (p.getAutoDeleteTimerMs() > 0) {
			info.line(R.string.message_info_disappears_after,
					GroupTrTimerLabels.label(this, p.getAutoDeleteTimerMs()));
		}
		info.show();
	}

	private void deleteSelectedPosts() {
		List<GroupTrPost> chosen = selectedPosts();
		if (chosen.isEmpty()) return;
		List<byte[]> ids = new ArrayList<>(chosen.size());
		boolean allMine = stillMember;
		for (GroupTrPost p : chosen) {
			byte[] id = p.getPostId();
			if (id == null) continue;
			ids.add(id);
			if (!isMine(p)) allMine = false;
		}
		if (ids.isEmpty()) return;
		boolean mayAskEveryone = allMine;
		byte[] gid = groupId;
		ioExecutor.execute(() -> {
			int supporting = 0;
			int older = 0;
			if (mayAskEveryone) {
				try {
					supporting = groupTrManager.countMembersWithPostActions(gid);
					older = groupTrManager.countMembersWithoutPostActions(gid);
				} catch (DbException ex) {
				}
			}
			boolean everyone = supporting > 0;
			int keeping = older;
			main.post(() -> {
				if (isFinishing() || isDestroyed()) return;
				showDeleteDialog(ids, everyone, keeping);
			});
		});
	}

	private void showDeleteDialog(List<byte[]> ids, boolean everyone,
			int keeping) {
		String question = getResources().getQuantityString(
				R.plurals.conversation_delete_message, ids.size(), ids.size());
		SecureAlertDialogBuilder dialog = new SecureAlertDialogBuilder(this);
		dialog.setTitle(R.string.conversation_delete_title);
		if (everyone) {
			StringBuilder message = new StringBuilder(question)
					.append("\n\n").append(getString(
							R.string.grouptr_delete_for_everyone_explained));
			if (keeping > 0) {
				message.append("\n\n").append(getResources()
						.getQuantityString(R.plurals.grouptr_delete_kept_older,
								keeping, keeping));
			}
			dialog.setMessage(message.toString());
			dialog.setPositiveButton(R.string.delete_for_everyone, (d, w) -> {
				finishSelection();
				deleteForEveryone(ids);
			});
			dialog.setNeutralButton(R.string.delete_for_me, (d, w) -> {
				finishSelection();
				deleteForMe(ids);
			});
		} else {
			dialog.setMessage(question);
			dialog.setPositiveButton(R.string.conversation_delete_action,
					(d, w) -> {
						finishSelection();
						deleteForMe(ids);
					});
		}
		dialog.setNegativeButton(android.R.string.cancel, null);
		dialog.show();
	}

	private void deleteForEveryone(List<byte[]> ids) {
		onPostsRemoved(ids);
		byte[] gid = groupId;
		ioExecutor.execute(() -> {
			try {
				int keeping = groupTrManager.deletePostsForEveryone(gid, ids);
				if (keeping > 0) {
					main.post(() -> Toast.makeText(this, getResources()
							.getQuantityString(R.plurals.grouptr_delete_kept_older,
									keeping, keeping), Toast.LENGTH_LONG)
							.show());
				}
			} catch (DbException ex) {
				main.post(() -> toast(R.string.delete_failed));
			}
			requestReload();
		});
	}

	private void deleteForMe(List<byte[]> ids) {
		onPostsRemoved(ids);
		byte[] gid = groupId;
		ioExecutor.execute(() -> {
			try {
				groupTrManager.deletePostsForMe(gid, ids);
			} catch (DbException ex) {
				main.post(() -> toast(R.string.delete_failed));
			}
			requestReload();
		});
	}

	private void showReplyPreview(GroupTrPost p) {
		replyingTo = p;
		TextView author = replyPreview.findViewById(R.id.reply_author);
		TextView text = replyPreview.findViewById(R.id.reply_text);
		author.setText(senderLabel(p));
		text.setText(GroupTrPostAdapter.excerpt(this, p));
		replyPreview.setVisibility(View.VISIBLE);
	}

	private void hideReplyPreview() {
		replyingTo = null;
		if (replyPreview != null) replyPreview.setVisibility(View.GONE);
	}

	private void focusComposer() {
		input.requestFocus();
		com.professor.zerion.android.util.UiUtils.showSoftKeyboard(input);
	}

	private void onQuoteClicked(byte[] postId) {
		int position = postAdapter.positionOf(StringUtils.toHexString(postId));
		if (position < 0) {
			toast(R.string.reply_original_unavailable);
			return;
		}
		layoutManager.scrollToPositionWithOffset(position,
				postsRecycler.getHeight() / 3);
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

	private void renderPosts(List<GroupTrPost> posts,
			GroupTrPostAdapter.Update update, boolean renamed) {
		boolean pin = !groupRendered || isScrolledToBottom();
		postAdapter.apply(update);
		if (renamed) postAdapter.refreshNames();
		dropVanishedFromSelection();
		selectionBar.refresh();
		pendingSends.settle(pendingScope, postAdapter::hasStored);
		showPendingRows();
		takeBackReturned();
		showHeldNotices();
		for (GroupTrPost p : posts) scheduleAutoDelete(p);
		if (pin) scrollToBottom();
		if (!posts.isEmpty()) groupRendered = true;
	}

	private void showPendingRows() {
		PendingSends.Shown shown = postAdapter::hasStored;
		List<PendingGroupPost> rows = new ArrayList<>();
		byte[] me = localPub == null ? new byte[0] : localPub;
		for (PendingSends.Entry e : PendingSends.visible(
				pendingSends.entries(pendingScope), shown)) {
			PendingGroupPost.Send s = send(e);
			if (s == null) continue;
			byte[] body = s.replyTo == null ? s.body
					: GroupTrBody.encodeReply(s.replyTo, s.text);
			rows.add(new PendingGroupPost(groupId, me, "", e, body));
		}
		postAdapter.setPending(rows);
		emptyState.setVisibility(postAdapter.getItemCount() == 0
				? View.VISIBLE : View.GONE);
	}

	@Nullable
	private static PendingGroupPost.Send send(PendingSends.Entry e) {
		Object payload = e.getPayload();
		return payload instanceof PendingGroupPost.Send
				? (PendingGroupPost.Send) payload : null;
	}

	private PendingSends.Entry queuePost(PendingGroupPost.Send s) {
		return pendingSends.add(pendingScope, s.text, s, s.body.length,
				s.isText(), sendAttempt(groupTrManager, groupId, s));
	}

	static PendingSends.Attempt sendAttempt(GroupTrManager manager,
			byte[] groupId, PendingGroupPost.Send s) {
		return () -> {
			try {
				GroupTrSendResult sent = s.replyTo == null
						? manager.sendGroupPostReporting(groupId, s.body, 0L)
						: manager.sendGroupReplyReporting(groupId, s.replyTo,
								s.text, 0L);
				PendingSends.Notice notice = noticeFor(sent);
				if (sent.getMembers() > 0 && sent.getReached() == 0) {
					return PendingSends.Result.failed(notice);
				}
				return PendingSends.Result.handedOver(
						StringUtils.toHexString(sent.getPostId()), notice);
			} catch (org.zerionproject.app.api.grouptr
					.GroupTrAuthException refused) {
				return PendingSends.Result.refused(PendingSends.Notice.of(
						R.string.grouptr_error_send));
			} catch (DbException ex) {
				return PendingSends.Result.failed(null);
			}
		};
	}

	@Nullable
	static PendingSends.Notice noticeFor(GroupTrSendResult sent) {
		if (sent.getMembers() == 0) return null;
		PendingSends.Notice first;
		if (sent.getReached() == 0) {
			first = PendingSends.Notice.of(
					R.string.grouptr_post_reached_nobody);
		} else if (sent.getReached() >= sent.getMembers()) {
			return null;
		} else {
			first = PendingSends.Notice.plural(
					R.plurals.grouptr_ready_for_some, sent.getMembers(),
					sent.getReached(), sent.getMembers());
		}
		return sent.getSkipped() > 0 ? first.then(PendingSends.Notice.plural(
				R.plurals.grouptr_post_not_sent_older, sent.getSkipped()))
				: first;
	}

	private void onPendingChanged(PendingSends.Change change) {
		if (!change.getScope().equals(pendingScope)) return;
		main.post(() -> {
			if (isFinishing() || isDestroyed()) return;
			PendingSends.Notice notice = change.getNotice();
			switch (change.getKind()) {
				case STORED:
				case HANDED_OVER:
					if (notice != null) feedback(notice.text(this));
					showPendingRows();
					requestReload();
					break;
				case FAILED:
					showPendingRows();
					if (notice != null) {
						feedback(notice.text(this));
					} else {
						Feedback.couldNotSend(composerAnchor(),
								pendingSends.retryable(pendingScope).size(),
								this::retryAllNotSent);
					}
					break;
				case RETURNED:
					takeBackReturned();
					showPendingRows();
					if (notice != null) feedback(notice.text(this));
					break;
				case REMOVED:
					showPendingRows();
					showHeldNotices();
					break;
				default:
					showPendingRows();
					break;
			}
		});
	}

	private void onPendingCleared() {
		if (isDestroyed()) return;
		if (undoBar != null) {
			undoBar.dismiss();
			undoBar = null;
		}
		undoable.clear();
		showPendingRows();
	}

	private boolean composerShown() {
		return composerRow.getVisibility() == View.VISIBLE;
	}

	private void showHeldNotices() {
		for (PendingSends.Notice n : pendingSends.takeNotices(pendingScope)) {
			feedback(n.text(this));
		}
	}

	private void takeBackReturned() {
		if (replyingTo != null || composerRow.getVisibility() != View.VISIBLE
				|| input.length() > 0) {
			return;
		}
		for (PendingSends.Entry e : pendingSends.entries(pendingScope)) {
			if (e.isReturned() && e.canEdit()) {
				editPending(e);
				return;
			}
		}
	}

	private void retryPending(PendingSends.Entry e) {
		boolean started = pendingSends.retry(e, ioExecutor);
		showPendingRows();
		if (!started) refreshRow(e);
	}

	private void refreshRow(PendingSends.Entry e) {
		postAdapter.refreshState("pending:" + e.getId());
	}

	private void retryAllNotSent() {
		for (PendingSends.Entry e : pendingSends.retryable(pendingScope)) {
			retryPending(e);
		}
	}

	private void deletePending(PendingSends.Entry e) {
		if (!pendingSends.discard(e)) {
			showPendingRows();
			refreshRow(e);
			return;
		}
		showPendingRows();
		if (isFinishing() || isDestroyed()) return;
		if (undoBar == null || !undoBar.isShown()) undoable.clear();
		undoable.add(e);
		undoBar = Feedback.show(composerAnchor(),
				getText(R.string.message_state_deleted),
				R.string.message_state_undo, () -> {
					undoBar = null;
					List<PendingSends.Entry> again = new ArrayList<>(undoable);
					undoable.clear();
					for (PendingSends.Entry held : again) {
						pendingSends.restore(held);
					}
					showPendingRows();
				});
	}

	private void editPending(PendingSends.Entry e) {
		PendingGroupPost.Send s = send(e);
		if (s == null || !e.canEdit()) {
			showPendingRows();
			refreshRow(e);
			return;
		}
		if (!composerShown()) {
			com.professor.zerion.android.util.SecureClipboard.copy(this,
					"message", s.text);
			feedback(R.string.copied_to_clipboard);
			refreshRow(e);
			return;
		}
		if (input.length() > 0) {
			feedback(R.string.message_state_edit_busy);
			refreshRow(e);
			return;
		}
		if (!pendingSends.discard(e)) {
			showPendingRows();
			refreshRow(e);
			return;
		}
		hideReplyPreview();
		input.setText(s.text);
		input.setSelection(s.text.length());
		if (s.replyTo != null) {
			GroupTrPost target = postAdapter.find(
					StringUtils.toHexString(s.replyTo));
			if (target != null) {
				showReplyPreview(target);
			} else {
				feedback(R.string.reply_original_unavailable);
			}
		}
		showPendingRows();
	}

	private View composerAnchor() {
		return findViewById(R.id.input_container);
	}

	private void feedback(CharSequence text) {
		if (isFinishing() || isDestroyed()) return;
		Feedback.show(composerAnchor(), text);
	}

	private void feedback(int res) {
		feedback(getText(res));
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
		long expiry = p.getExpiryTime();
		if (expiry == Long.MAX_VALUE) return;
		String key = postKey(p);
		if (!autoDeleteScheduled.add(key)) return;
		long now = System.currentTimeMillis();
		long remaining = expiry <= now ? 0L : expiry - now;
		if (remaining <= 0L) {
			expirePost(p, key);
			return;
		}
		main.postDelayed(() -> expirePost(p, key), remaining);
	}

	private void expirePost(GroupTrPost p, String key) {
		autoDeleteScheduled.remove(key);
		postAdapter.removePost(p);
		dropVanishedFromSelection();
		if (postAdapter.getItemCount() == 0) {
			emptyState.setVisibility(View.VISIBLE);
		}
	}

	private class PostCallback implements GroupTrPostAdapter.Callback {
		@Override
		public boolean isMine(GroupTrPost p) {
			return p instanceof PendingGroupPost || (localPub != null
					&& Arrays.equals(p.getSenderPubKey(), localPub));
		}

		@Override
		public void onRetry(PendingGroupPost p) {
			retryPending(p.getEntry());
		}

		@Override
		public void onDiscard(PendingGroupPost p) {
			deletePending(p.getEntry());
		}

		@Override
		public void onEdit(PendingGroupPost p) {
			editPending(p.getEntry());
		}

		@Override
		public boolean composerShown() {
			return GroupTrConversationActivity.this.composerShown();
		}

		@Override
		public void bindSender(TextView tv, GroupTrPost p) {
			GroupTrConversationActivity.this.bindSender(tv, p);
		}

		@Override
		public void onImageClick(GroupTrPost p, byte[] bytes,
				@Nullable String mime) {
			openMediaFullscreen(p, bytes, mime, false);
		}

		@Override
		public void onVideoClick(GroupTrPost p, byte[] bytes,
				@Nullable String mime) {
			openMediaFullscreen(p, bytes, mime, true);
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
			return com.professor.zerion.android.util.UiUtils
					.formatDate(GroupTrConversationActivity.this, ts);
		}

		@Override
		public String formatDuration(long ms) {
			return GroupTrConversationActivity.this.formatDuration(ms);
		}

		@Override
		public void onPostLongClick(GroupTrPost p) {
			startOrToggleSelection(p);
		}

		@Override
		public boolean onPostClick(GroupTrPost p) {
			if (!selectionBar.isShowing()) return false;
			toggleSelection(GroupTrPostAdapter.key(p));
			return true;
		}

		@Override
		public boolean isSelected(GroupTrPost p) {
			return selected.contains(GroupTrPostAdapter.key(p));
		}

		@Override
		public boolean selecting() {
			return selectionBar.isShowing();
		}

		@Override
		public Set<SelectionAction> rowActions(GroupTrPost p) {
			return GroupTrConversationActivity.this.rowActions(p);
		}

		@Override
		public boolean onRowAction(GroupTrPost p, SelectionAction action) {
			return GroupTrConversationActivity.this.onRowAction(p, action);
		}

		@Override
		public String quoteAuthor(GroupTrPost p) {
			return senderLabel(p);
		}

		@Override
		public void onQuoteClick(byte[] postId) {
			onQuoteClicked(postId);
		}
	}

	@Nullable
	private Bitmap extractVideoThumb(byte[] videoBytes) {
		MediaMetadataRetriever mmr = new MediaMetadataRetriever();
		try {
			mmr.setDataSource(new com.professor.zerion.android.util
					.ByteArrayMediaDataSource(videoBytes));
			return mmr.getFrameAtTime(0);
		} catch (RuntimeException ex) {
			return null;
		} finally {
			try {
				mmr.release();
			} catch (IOException ignored) {
			}
		}
	}

	private void openMediaFullscreen(GroupTrPost p, byte[] bytes,
			@Nullable String mime, boolean isVideo) {
		MediaViewer.Item item = new MediaViewer.Item(bytes, mime)
				.titled(senderLabel(p), p.getTimestamp())
				.secure(forceScreenshotProtection())
				.from(MediaViewer.groupScope(groupId), MediaViewer.groupPost(
						groupId, GroupTrPostAdapter.key(p)))
				.expiresAt(p.getExpiryTime());
		boolean shown = isVideo ? MediaViewer.playVideo(this, ioExecutor, item)
				: MediaViewer.showImage(this, ioExecutor, item);
		if (!shown) {
			toast(isVideo ? R.string.video_playback_error
					: R.string.grouptr_attach_read_failed);
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
		if (body.length > MAX_POST_BODY_BYTES) {
			feedback(R.string.grouptr_post_too_long);
			return;
		}
		GroupTrPost quoting = replyingTo;
		byte[] target = quoting == null ? null : quoting.getPostId();
		PendingSends.Entry pending = queuePost(
				new PendingGroupPost.Send(body, target, text));
		showPendingRows();
		scrollToBottom();
		input.setText("");
		hideReplyPreview();
		pendingSends.start(pending, ioExecutor);
	}

	private void showStealthNameDialog() {
		final EditText editor = new EditText(this);
		editor.setInputType(InputType.TYPE_CLASS_TEXT);
		ioExecutor.execute(() -> {
			try {
				String incognito = groupTrManager.getIncognitoName(groupId);
				String current = groupTrManager.getStealthName(groupId);
				main.post(() -> {
					if (isFinishing() || isDestroyed()) return;
					if (incognito != null) {
						GroupTrIncognitoPicker.showIncognitoInfo(this,
								incognito);
						return;
					}
					if (current != null) editor.setText(current);
					new SecureAlertDialogBuilder(this)
							.setTitle(R.string.grouptr_stealth_name_set)
							.setMessage(R.string.grouptr_stealth_name_hint)
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
				feedback(R.string.grouptr_voice_send_failed);
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

	private void onMediaPicked(@Nullable Uri picked) {
		Uri uri = com.professor.zerion.android.util.PickedUris.accept(this,
				picked);
		if (uri == null) return;
		ioExecutor.execute(() -> processPickedMedia(uri));
	}

	private void processPickedMedia(Uri uri) {
		ContentResolver cr = getContentResolver();
		String mime = cr.getType(uri);
		if (mime == null) {
			main.post(() -> feedback(R.string.grouptr_attach_unknown_type));
			return;
		}
		if (mime.startsWith("image/")) {
			byte[] jpeg = readImageDownscaledJpeg(uri);
			if (jpeg == null) {
				main.post(() -> feedback(R.string.grouptr_attach_read_failed));
				return;
			}
			byte[] body = GroupTrBody.encodeImage(jpeg, "image/jpeg");
			sendBodyAsync(body);
			return;
		}
		if (mime.startsWith("video/")) {
			byte[] data = readUriBytes(uri, MAX_INLINE_MEDIA_BYTES);
			if (data == null) {
				main.post(() -> feedback(R.string.grouptr_attach_video_too_large));
				return;
			}
			com.professor.zerion.android.attachment.SharedMediaSanitizer
					.Cleaned cleaned;
			try {
				cleaned = new com.professor.zerion.android.attachment
						.SharedMediaSanitizer(getApplicationContext())
						.sanitize(uri, mime, data, MAX_INLINE_MEDIA_BYTES);
			} catch (IOException | RuntimeException ex) {
				main.post(() -> {
					if (ex instanceof com.professor.zerion.android.attachment
							.MediaRefusedException) {
						com.professor.zerion.android.attachment
								.MediaRefusalDialog.show(this,
										(com.professor.zerion.android
												.attachment
												.MediaRefusedException) ex);
					} else {
						feedback(R.string.grouptr_attach_read_failed);
					}
				});
				return;
			}
			long durationMs = probeVideoDurationMs(uri);
			byte[] body = GroupTrBody.encodeVideo(cleaned.getData(),
					cleaned.getMimeType(), durationMs);
			sendBodyAsync(body);
			return;
		}
		main.post(() -> feedback(R.string.grouptr_attach_unknown_type));
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
		try {
			bitmap = com.professor.zerion.android.attachment.ImageOrientation
					.decodeUpright(cr, uri, opts);
		} catch (IOException ex) {
			return null;
		}
		if (bitmap == null) return null;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG,
				IMAGE_JPEG_QUALITY, out);
		bitmap.recycle();
		if (!ok) return null;
		try {
			return com.professor.zerion.android.attachment.ImageScrubber
					.scrub(out.toByteArray());
		} catch (IOException ex) {
			return null;
		}
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
			main.post(() -> feedback(R.string.grouptr_attach_video_too_large));
			return;
		}
		PendingSends.Entry pending = queuePost(
				new PendingGroupPost.Send(body, null, ""));
		pendingSends.start(pending, ioExecutor);
		main.post(() -> {
			if (isFinishing() || isDestroyed()) return;
			showPendingRows();
			scrollToBottom();
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
		GroupTrState s = lastState;
		if (s != null && localPub != null
				&& !Arrays.equals(localPub, s.getCreatorPubKey())) {
			long ms = s.getDefaultAutoDeleteTimerMs();
			new SecureAlertDialogBuilder(this)
					.setTitle(R.string.grouptr_default_ttl_set)
					.setMessage(getString(R.string.grouptr_ttl_set_by_creator,
							ms <= 0L ? getString(R.string.grouptr_ttl_off_label)
									: GroupTrTimerLabels.label(this, ms)))
					.setPositiveButton(android.R.string.ok, null)
					.show();
			return;
		}
		final String[] labels = {
				getString(R.string.grouptr_ttl_off),
				getString(R.string.grouptr_ttl_5min),
				getString(R.string.grouptr_ttl_1hr),
				getString(R.string.grouptr_ttl_1day),
				getString(R.string.grouptr_ttl_7days),
				getString(R.string.grouptr_ttl_30days),
				getString(R.string.dialog_disappearing_custom),
		};
		final long[] values = {
				0L, 5L * 60 * 1000, 60L * 60 * 1000,
				24L * 60 * 60 * 1000, 7L * 24 * 60 * 60 * 1000,
				30L * 24L * 60 * 60 * 1000
		};
		long current = s == null ? 0L : s.getDefaultAutoDeleteTimerMs();
		new SecureAlertDialogBuilder(this)
				.setTitle(R.string.grouptr_default_ttl_set)
				.setItems(labels, (d, which) -> {
					if (which == values.length) {
						com.professor.zerion.android.conversation
								.DisappearingTimers.showCustomPicker(this,
								current, this::applyGroupTtl);
						return;
					}
					applyGroupTtl(values[which]);
				})
				.show();
	}

	private void applyGroupTtl(long v) {
		ioExecutor.execute(() -> {
			try {
				groupTrManager.setGroupAutoDeleteTimer(groupId, v);
				int older = groupTrManager.countMembersOnOlderVersion(groupId);
				main.post(() -> {
					toast(R.string.grouptr_default_ttl_saved);
					if (older > 0) {
						Toast.makeText(this, getResources().getQuantityString(
								R.plurals.grouptr_ttl_older_members, older,
								older), Toast.LENGTH_LONG).show();
					}
				});
			} catch (DbException ex) {
				main.post(() -> toast(R.string.grouptr_error_save));
			}
		});
	}

	private void toast(int res) {
		Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
	}

	private void bindSender(TextView sender,
			org.zerionproject.app.api.grouptr.GroupTrPost p) {
		sender.setText(senderLabel(p));
		sender.setOnClickListener(v -> showSafetyCode(p));
	}

	private String senderLabel(org.zerionproject.app.api.grouptr.GroupTrPost p) {
		String known = knownNames.get(StringUtils.toHexString(
				p.getSenderPubKey()));
		return GroupTrSenderLabel.label(known, p.getSenderName(),
				getString(R.string.grouptr_sender_alias_format),
				getString(R.string.grouptr_sender_unverified_format),
				getString(R.string.grouptr_sender_incognito_format));
	}

	private String safetyCodeName(
			org.zerionproject.app.api.grouptr.GroupTrPost p) {
		String known = knownNames.get(StringUtils.toHexString(
				p.getSenderPubKey()));
		return known == null || known.isEmpty()
				? getString(R.string.grouptr_member_this_person) : known;
	}

	private void showSafetyCode(
			org.zerionproject.app.api.grouptr.GroupTrPost p) {
		byte[] mlDsa = knownMlDsaKeys.get(StringUtils.toHexString(
				p.getSenderPubKey()));
		String code = com.professor.zerion.android.contact.identity
				.IdentityDisplay.lines(p.getSenderPubKey(), mlDsa,
						getString(R.string.identity_fingerprint_hybrid_format),
						getString(R.string.identity_fingerprint_classical_format));
		new com.professor.zerion.android.security.SecureAlertDialogBuilder(
				this, R.style.ZerionDialogTheme)
				.setTitle(R.string.grouptr_member_safety_code)
				.setMessage(getString(
						R.string.grouptr_member_safety_code_message,
						safetyCodeName(p), code))
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	public static Intent intent(android.content.Context ctx, byte[] gid) {
		Intent i = new Intent(ctx, GroupTrConversationActivity.class);
		i.putExtra(EXTRA_GROUP_ID, StringUtils.toHexString(gid));
		return i;
	}
}
