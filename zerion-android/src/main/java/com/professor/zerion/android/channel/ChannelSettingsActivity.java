package com.professor.zerion.android.channel;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.channel.ChannelSettingsRows.Row;
import com.professor.zerion.android.channel.ChannelSettingsRows.Section;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.professor.zerion.android.util.SecureClipboard;
import com.professor.zerion.android.util.ZerionSnackbarBuilder;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelRequestQueuedException;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.zerionproject.app.api.channel.ChannelSyncStatus;
import org.zerionproject.app.api.channel.event.ChannelStateChangedEvent;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChannelSettingsActivity extends ZerionActivity
		implements EventListener {

	private static final String EXTRA_CHANNEL_ID =
			"com.professor.zerion.android.channel.SETTINGS_CHANNEL_ID";

	public static Intent intent(Context ctx, byte[] channelId) {
		Intent i = new Intent(ctx, ChannelSettingsActivity.class);
		i.putExtra(EXTRA_CHANNEL_ID, channelId);
		return i;
	}

	@Inject
	ChannelManager channelManager;
	@Inject
	EventBus eventBus;
	@Inject
	@IoExecutor
	Executor ioExecutor;
	@Inject
	AccountManager accountManager;

	private byte[] channelId = new byte[0];
	@Nullable
	private Snapshot shown;
	private int generation = 0;
	private boolean binding = false;
	private final Map<Row, View> rows = new EnumMap<>(Row.class);
	private final Map<Section, View> sections = new EnumMap<>(Section.class);
	private View root;
	private TextView avatar;
	private TextView name;
	private TextView access;
	private TextView description;
	private MaterialSwitch mute;
	private MaterialSwitch comments;
	private TextView whoCanJoin;
	private TextView whoCanPost;
	private TextView connection;
	private TextView waiting;
	private TextView subscribers;
	private TextView editors;
	private TextView blocked;
	private ChannelDeleteFlow deleteFlow;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
				WindowManager.LayoutParams.FLAG_SECURE);
		setContentView(R.layout.activity_channel_settings);
		byte[] cid = getIntent().getByteArrayExtra(EXTRA_CHANNEL_ID);
		if (cid != null) channelId = cid;

		Toolbar toolbar = findViewById(R.id.channelSettingsToolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		toolbar.setNavigationOnClickListener(v -> finish());

		root = findViewById(R.id.channelSettingsScroll);
		avatar = findViewById(R.id.channelSettingsAvatar);
		name = findViewById(R.id.channelSettingsName);
		access = findViewById(R.id.channelSettingsAccess);
		description = findViewById(R.id.channelSettingsDescription);
		mute = findViewById(R.id.channelSettingsMute);
		comments = findViewById(R.id.channelSettingsComments);
		whoCanJoin = findViewById(R.id.channelSettingsWhoCanJoinSummary);
		whoCanPost = findViewById(R.id.channelSettingsWhoCanPostValue);
		connection = findViewById(R.id.channelSettingsConnectionSummary);
		waiting = findViewById(R.id.channelSettingsWaitingCount);
		subscribers = findViewById(R.id.channelSettingsSubscribersValue);
		editors = findViewById(R.id.channelSettingsEditorsValue);
		blocked = findViewById(R.id.channelSettingsBlockedValue);

		rows.put(Row.MUTE, mute);
		rows.put(Row.SHARE, findViewById(R.id.channelSettingsShare));
		rows.put(Row.SEND, findViewById(R.id.channelSettingsSend));
		rows.put(Row.WHO_CAN_JOIN, findViewById(R.id.channelSettingsWhoCanJoin));
		rows.put(Row.RENEW, findViewById(R.id.channelSettingsRenew));
		rows.put(Row.COMMENTS, comments);
		rows.put(Row.WHO_CAN_POST, findViewById(R.id.channelSettingsWhoCanPost));
		rows.put(Row.ANNOUNCE, findViewById(R.id.channelSettingsAnnounce));
		rows.put(Row.CONNECTION, findViewById(R.id.channelSettingsConnection));
		rows.put(Row.WAITING, findViewById(R.id.channelSettingsWaiting));
		rows.put(Row.SUBSCRIBERS, findViewById(R.id.channelSettingsSubscribers));
		rows.put(Row.EDITORS, findViewById(R.id.channelSettingsEditors));
		rows.put(Row.BLOCKED, findViewById(R.id.channelSettingsBlocked));
		rows.put(Row.DELETE, findViewById(R.id.channelSettingsDelete));
		rows.put(Row.LEAVE, findViewById(R.id.channelSettingsLeave));
		sections.put(Section.CHANNEL,
				findViewById(R.id.channelSettingsSectionChannel));
		sections.put(Section.ACCESS,
				findViewById(R.id.channelSettingsSectionAccess));
		sections.put(Section.PARTICIPATION,
				findViewById(R.id.channelSettingsSectionParticipation));
		sections.put(Section.AVAILABILITY,
				findViewById(R.id.channelSettingsSectionAvailability));
		sections.put(Section.MEMBERS,
				findViewById(R.id.channelSettingsSectionMembers));
		sections.put(Section.ADMINISTRATION,
				findViewById(R.id.channelSettingsSectionAdministration));

		mute.setOnCheckedChangeListener((b, checked) -> {
			if (binding) return;
			setMuted(checked);
		});
		comments.setOnCheckedChangeListener((b, checked) -> {
			if (binding) return;
			binding = true;
			b.setChecked(!checked);
			binding = false;
			confirmComments(checked);
		});
		rows.get(Row.SHARE).setOnClickListener(v -> ChannelShare.copyInvite(
				this, channelManager, ioExecutor, channelId));
		rows.get(Row.SEND).setOnClickListener(v -> ChannelShare.sendInvite(
				this, channelManager, ioExecutor, channelId));
		rows.get(Row.RENEW).setOnClickListener(v -> confirmRenew());
		rows.get(Row.WHO_CAN_POST).setOnClickListener(v -> startActivity(
				ChannelDelegationsActivity.intent(this, channelId)));
		rows.get(Row.ANNOUNCE).setOnClickListener(v -> showAnnounceDialog());
		rows.get(Row.CONNECTION).setOnClickListener(v -> showAdvanced());
		rows.get(Row.WAITING).setOnClickListener(v -> startActivity(
				ChannelPendingApplicationsActivity.intent(this, channelId)));
		rows.get(Row.SUBSCRIBERS).setOnClickListener(v -> startActivity(
				ChannelSubscribersActivity.intent(this, channelId)));
		rows.get(Row.EDITORS).setOnClickListener(v -> startActivity(
				ChannelDelegationsActivity.intent(this, channelId)));
		rows.get(Row.BLOCKED).setOnClickListener(v -> startActivity(
				ChannelSubscribersActivity.blockedIntent(this, channelId)));
		deleteFlow = new ChannelDeleteFlow(this, channelManager,
				accountManager, ioExecutor, channelId,
				new ChannelDeleteFlow.Listener() {
					@Override
					public void onDeleted() {
						finish();
					}

					@Override
					public void onNotDeleted(CharSequence reason) {
						say(reason);
						load();
					}
				});
		rows.get(Row.DELETE).setOnClickListener(v -> startDelete());
		rows.get(Row.LEAVE).setOnClickListener(v -> confirmLeave());
	}

	@Override
	public void onStart() {
		super.onStart();
		eventBus.addListener(this);
		load();
	}

	@Override
	public void onStop() {
		super.onStop();
		eventBus.removeListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ChannelStateChangedEvent && Arrays.equals(
				((ChannelStateChangedEvent) e).getChannelId(), channelId)) {
			runOnUiThreadUnlessDestroyed(this::load);
		}
	}

	private void load() {
		int load = ++generation;
		ioExecutor.execute(() -> {
			Snapshot s = Snapshot.read(channelManager, channelId);
			runOnUiThreadUnlessDestroyed(() -> {
				if (load != generation) return;
				if (s == null) {
					finish();
					return;
				}
				render(s);
			});
		});
	}

	static final class Snapshot {

		final ChannelState state;
		boolean editor;
		boolean commentsOn = true;
		int waiting;
		int subscribers;
		int editors;
		int blocked;
		boolean outdated;
		long givenUp;
		String myCode = "";
		@Nullable
		ChannelSyncStatus sync;

		private Snapshot(ChannelState state) {
			this.state = state;
		}

		@Nullable
		static Snapshot read(ChannelManager m, byte[] id) {
			ChannelState s;
			try {
				s = m.getChannel(id);
			} catch (DbException e) {
				return null;
			}
			if (s == null) return null;
			Snapshot out = new Snapshot(s);
			if (s.weArePublisher()) {
				out.commentsOn = quiet(() -> m.areDiscussionsEnabled(id), true);
				if (!s.isPublicChannel() && s.requiresApproval()) {
					out.waiting = quiet(() -> m.listPendingApplications(id),
							new ArrayList<>()).size();
				}
				List<byte[]> bans = new ArrayList<>(quiet(
						() -> m.getBannedKeys(id), new ArrayList<>()));
				for (ChannelSubscriber sub : quiet(
						() -> m.getAnnouncedSubscribers(id),
						new ArrayList<ChannelSubscriber>())) {
					byte[] key = sub.getEd25519PubKey();
					if (sub.isBanned() || contains(bans, key)) {
						if (!contains(bans, key)) bans.add(key);
					} else {
						out.subscribers++;
					}
				}
				out.blocked = bans.size();
				out.editors = quiet(() -> m.listActiveDelegations(id),
						new ArrayList<>()).size();
				out.outdated = quiet(() -> m.hasOutdatedSubscribers(id), false);
			} else {
				out.editor = quiet(() -> m.canPost(id), false);
				out.sync = quiet(() -> m.getSyncStatus(id), null);
				out.givenUp = quiet(() -> m.getPostsGivenUp(id), 0L);
				byte[] mine = quiet(() -> m.getMyChannelPublicKey(id),
						new byte[0]);
				out.myCode = ChannelMember.ownSafetyCode(mine);
			}
			return out;
		}

		private static boolean contains(List<byte[]> keys, byte[] key) {
			for (byte[] k : keys) if (Arrays.equals(k, key)) return true;
			return false;
		}
	}

	private interface Read<T> {
		T get() throws DbException;
	}

	private static <T> T quiet(Read<T> read, T fallback) {
		try {
			T value = read.get();
			return value == null ? fallback : value;
		} catch (DbException | RuntimeException e) {
			return fallback;
		}
	}

	private void render(Snapshot s) {
		shown = s;
		ChannelState state = s.state;
		boolean owner = state.weArePublisher();
		String title = state.getName().isEmpty()
				? getString(R.string.channels_button) : state.getName();
		avatar.setText(initial(title));
		name.setText(title);
		access.setText(ChannelAccessLabel.withRole(this, state, s.editor));
		String about = state.getDescription().trim();
		description.setText(about);
		description.setVisibility(about.isEmpty() ? View.GONE : View.VISIBLE);

		Set<Row> visible = ChannelSettingsRows.visible(owner,
				state.isPublicChannel(), state.requiresApproval(), s.blocked);
		for (Map.Entry<Row, View> e : rows.entrySet()) {
			e.getValue().setVisibility(visible.contains(e.getKey())
					? View.VISIBLE : View.GONE);
		}
		List<Section> shownSections = ChannelSettingsRows.sections(visible);
		for (Map.Entry<Section, View> e : sections.entrySet()) {
			e.getValue().setVisibility(shownSections.contains(e.getKey())
					? View.VISIBLE : View.GONE);
		}

		binding = true;
		mute.setChecked(isMuted());
		comments.setChecked(s.commentsOn);
		binding = false;

		whoCanJoin.setText(joinSummary(state));
		whoCanPost.setText(s.editors == 0
				? getString(R.string.channels_settings_only_you)
				: getResources().getQuantityString(
						R.plurals.channels_settings_you_and_editors,
						s.editors, s.editors));
		connection.setText(owner
				? getString(R.string.channels_settings_connection_owner)
				: connectionSummary(this, s.sync));
		count(Row.WAITING, waiting, R.string.channels_settings_waiting,
				s.waiting);
		waiting.setVisibility(s.waiting > 0 ? View.VISIBLE : View.GONE);
		subscribers.setText(NumberFormat.getIntegerInstance().format(
				s.subscribers));
		rows.get(Row.SUBSCRIBERS).setContentDescription(
				getResources().getQuantityString(
						R.plurals.channels_settings_named_people_description,
						s.subscribers, s.subscribers));
		count(Row.EDITORS, editors, R.string.channels_settings_editors,
				s.editors);
		count(Row.BLOCKED, blocked, R.string.channels_settings_blocked,
				s.blocked);
	}

	boolean isMuted() {
		return ChannelFeedActivity.isChannelMuted(this, channelId);
	}

	void setMuted(boolean muted) {
		ChannelFeedActivity.setChannelMuted(this, channelId, muted);
	}

	private void count(Row row, TextView value, @StringRes int title,
			int n) {
		value.setText(NumberFormat.getIntegerInstance().format(n));
		View v = rows.get(row);
		if (v != null) {
			v.setContentDescription(getString(
					R.string.channels_settings_row_count, getString(title),
					getResources().getQuantityString(
							R.plurals.channels_settings_people, n, n)));
		}
	}

	static String initial(String name) {
		String trimmed = name.trim();
		if (trimmed.isEmpty()) return "";
		int end = Character.charCount(trimmed.codePointAt(0));
		return trimmed.substring(0, end).toUpperCase(Locale.getDefault());
	}

	private String joinSummary(ChannelState state) {
		if (state.isPublicChannel()) {
			return getString(R.string.channels_settings_join_public);
		}
		return getString(state.requiresApproval()
				? R.string.channels_settings_join_approval
				: R.string.channels_settings_join_private);
	}

	static String connectionSummary(Context ctx,
			@Nullable ChannelSyncStatus sync) {
		long at = sync == null ? ChannelSyncStatus.NEVER
				: sync.getLastUpdatedMs();
		if (at < 0L) {
			return ctx.getString(R.string.channels_settings_not_updated_yet);
		}
		if (sync.isDormant()) {
			return ctx.getString(R.string.channels_sync_not_updated_since,
					DateUtils.formatDateTime(ctx, at,
							DateUtils.FORMAT_SHOW_DATE
									| DateUtils.FORMAT_ABBREV_MONTH));
		}
		Calendar then = Calendar.getInstance();
		then.setTimeInMillis(at);
		Calendar now = Calendar.getInstance();
		boolean today = then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
				&& then.get(Calendar.DAY_OF_YEAR)
				== now.get(Calendar.DAY_OF_YEAR);
		return ctx.getString(R.string.channels_sync_last_updated,
				DateUtils.formatDateTime(ctx, at, today
						? DateUtils.FORMAT_SHOW_TIME
						: DateUtils.FORMAT_SHOW_DATE
								| DateUtils.FORMAT_ABBREV_MONTH));
	}

	private void say(CharSequence text) {
		new ZerionSnackbarBuilder().make(root, text, Snackbar.LENGTH_LONG)
				.show();
	}

	private void say(@StringRes int text) {
		say(getText(text));
	}

	private void confirmComments(boolean target) {
		new SecureAlertDialogBuilder(this)
				.setTitle(target
						? R.string.channels_discussions_confirm_enable_title
						: R.string.channels_discussions_confirm_disable_title)
				.setMessage(target
						? R.string.channels_discussions_confirm_enable_message
						: R.string.channels_discussions_confirm_disable_message)
				.setPositiveButton(android.R.string.ok,
						(d, w) -> applyComments(target))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void applyComments(boolean target) {
		ioExecutor.execute(() -> {
			boolean done;
			try {
				channelManager.setDiscussionsEnabled(channelId, target);
				done = true;
			} catch (DbException e) {
				done = false;
			}
			boolean finalDone = done;
			runOnUiThreadUnlessDestroyed(() -> {
				say(!finalDone
						? R.string.channels_discussions_toggle_failed_toast
						: target
								? R.string.channels_discussions_enabled_toast
								: R.string.channels_discussions_disabled_toast);
				load();
			});
		});
	}

	private void confirmRenew() {
		Snapshot s = shown;
		if (s == null) return;
		new SecureAlertDialogBuilder(this)
				.setTitle(R.string.channels_invite_renew_confirm_title)
				.setMessage(s.state.requiresApproval()
						? R.string.channels_invite_renew_confirm_message_approval
						: R.string.channels_invite_renew_confirm_message)
				.setPositiveButton(R.string.channels_action_renew_link,
						(d, w) -> renew())
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void renew() {
		ioExecutor.execute(() -> {
			try {
				channelManager.rotateJoinCapability(channelId);
				runOnUiThreadUnlessDestroyed(() -> {
					ChannelShare.copyInvite(this, channelManager, ioExecutor,
							channelId);
					load();
				});
			} catch (DbException e) {
				runOnUiThreadUnlessDestroyed(() ->
						say(R.string.channels_invite_rotate_failed));
			}
		});
	}

	private void showAnnounceDialog() {
		View view = LayoutInflater.from(this).inflate(
				R.layout.dialog_announce_channel, null);
		TextInputEditText input =
				view.findViewById(R.id.channelAnnounceNameInput);
		new SecureAlertDialogBuilder(this)
				.setTitle(R.string.channels_announce_title)
				.setMessage(R.string.channels_announce_message_text)
				.setView(view)
				.setPositiveButton(R.string.channels_announce_action,
						(d, w) -> announce(input.getText() == null ? ""
								: input.getText().toString().trim()))
				.setNegativeButton(R.string.channels_announce_skip, null)
				.show();
	}

	private void announce(String displayName) {
		if (displayName.isEmpty()) {
			say(R.string.channels_announce_failed);
			return;
		}
		ioExecutor.execute(() -> {
			int message;
			try {
				channelManager.announceMyself(channelId, displayName);
				message = 0;
			} catch (ChannelRequestQueuedException queued) {
				message = queued.isBehindEarlierItems()
						? R.string.channels_queued_behind_toast
						: R.string.channels_announce_queued_toast;
			} catch (DbException e) {
				message = R.string.channels_announce_failed;
			}
			int finalMessage = message;
			if (finalMessage != 0) {
				runOnUiThreadUnlessDestroyed(() -> say(finalMessage));
			}
		});
	}

	private void showAdvanced() {
		Snapshot s = shown;
		if (s == null) return;
		boolean owner = s.state.weArePublisher();
		List<String> lines = new ArrayList<>();
		if (owner) {
			lines.add(getString(R.string.channels_settings_advanced_owner));
			if (s.outdated) {
				lines.add(getString(
						R.string.channels_outdated_subscribers_notice));
			}
		} else {
			lines.add(connectionSummary(this, s.sync));
			if (s.sync != null && s.sync.isDormant()) {
				lines.add(getString(R.string.channels_settings_advanced_dormant));
			} else if (s.sync != null && s.sync.isLastAttemptFailed()) {
				lines.add(getString(R.string.channels_settings_advanced_failed));
			}
			if (s.givenUp > 0L) {
				lines.add(getString(R.string.channels_posts_given_up_notice));
			}
			if (!s.myCode.isEmpty()) {
				lines.add(getString(R.string.channels_settings_my_safety_code,
						s.myCode));
			}
		}
		SecureAlertDialogBuilder builder = new SecureAlertDialogBuilder(this);
		builder.setTitle(R.string.channels_settings_advanced)
				.setMessage(TextUtils.join("\n\n", lines))
				.setPositiveButton(android.R.string.ok, null);
		if (!owner) {
			builder.setNeutralButton(R.string.channels_action_copy_my_id,
					(d, w) -> copyMyChannelId());
		}
		builder.show();
	}

	private void copyMyChannelId() {
		ioExecutor.execute(() -> {
			byte[] key;
			try {
				key = channelManager.getMyChannelPublicKey(channelId);
			} catch (DbException e) {
				return;
			}
			String text = base32(key);
			runOnUiThreadUnlessDestroyed(() -> {
				SecureClipboard.copy(this, "zerion-channel-key", text);
				Toast.makeText(this, R.string.channels_my_id_copied,
						Toast.LENGTH_LONG).show();
			});
		});
	}

	static String base32(byte[] data) {
		char[] alphabet = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
		StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
		int buffer = 0;
		int bitsLeft = 0;
		for (byte b : data) {
			buffer = (buffer << 8) | (b & 0xFF);
			bitsLeft += 8;
			while (bitsLeft >= 5) {
				sb.append(alphabet[(buffer >> (bitsLeft - 5)) & 0x1F]);
				bitsLeft -= 5;
			}
		}
		if (bitsLeft > 0) {
			sb.append(alphabet[(buffer << (5 - bitsLeft)) & 0x1F]);
		}
		return sb.toString();
	}

	private void startDelete() {
		Snapshot s = shown;
		if (s == null || !s.state.weArePublisher()) return;
		deleteFlow.start(s.state.getName());
	}

	private void confirmLeave() {
		new SecureAlertDialogBuilder(this)
				.setTitle(R.string.channels_leave_confirm_title)
				.setMessage(R.string.channels_leave_confirm_message)
				.setPositiveButton(R.string.channels_leave_confirm_action,
						(d, w) -> leave())
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void leave() {
		Snapshot s = shown;
		if (s == null || s.state.weArePublisher()) return;
		ioExecutor.execute(() -> {
			try {
				channelManager.leaveChannel(channelId);
				runOnUiThreadUnlessDestroyed(this::finish);
			} catch (DbException e) {
				runOnUiThreadUnlessDestroyed(() ->
						say(R.string.channels_leave_failed));
			}
		});
	}
}
