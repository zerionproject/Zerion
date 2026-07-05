package com.professor.zerion.android.channel;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.briar.api.channel.ChannelInviteLink;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.ChannelState;
import org.briarproject.briar.api.channel.event.ChannelPostReceivedEvent;
import org.briarproject.briar.api.channel.event.ChannelStateChangedEvent;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChannelListFragment extends BaseFragment
		implements EventListener {

	public static final String TAG = ChannelListFragment.class.getName();

	public static ChannelListFragment newInstance() {
		return new ChannelListFragment();
	}

	@Inject
	ChannelManager channelManager;
	@Inject
	EventBus eventBus;
	@Inject
	@IoExecutor
	Executor ioExecutor;

	private LinearLayout listContainer;
	private View emptyView;

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		requireActivity().setTitle(R.string.channels_button);

		FrameLayout root = new FrameLayout(requireContext());
		root.setLayoutParams(new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));

		ScrollView scroll = new ScrollView(requireContext());
		listContainer = new LinearLayout(requireContext());
		listContainer.setOrientation(LinearLayout.VERTICAL);
		listContainer.setPadding(0, 0, 0, dp(120));
		scroll.addView(listContainer, new ScrollView.LayoutParams(
				ScrollView.LayoutParams.MATCH_PARENT,
				ScrollView.LayoutParams.WRAP_CONTENT));
		root.addView(scroll, new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT));

		LinearLayout emptyContainer = new LinearLayout(requireContext());
		emptyContainer.setOrientation(LinearLayout.VERTICAL);
		emptyContainer.setGravity(Gravity.CENTER_HORIZONTAL);
		emptyContainer.setPadding(dp(32), dp(64), dp(32), dp(32));

		android.widget.ImageView emptyIcon =
				new android.widget.ImageView(requireContext());
		emptyIcon.setImageResource(R.drawable.ic_channels);
		emptyIcon.setColorFilter(getResources()
				.getColor(R.color.zerion_text_secondary));
		emptyIcon.setAlpha(0.5f);
		LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
				dp(64), dp(64));
		iconLp.bottomMargin = dp(16);
		emptyContainer.addView(emptyIcon, iconLp);

		TextView emptyText = new TextView(requireContext());
		emptyText.setText(R.string.channels_list_empty);
		emptyText.setTextSize(15);
		emptyText.setTextColor(getResources()
				.getColor(R.color.zerion_text_secondary));
		emptyText.setGravity(Gravity.CENTER);
		emptyContainer.addView(emptyText, new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT));

		emptyView = emptyContainer;
		FrameLayout.LayoutParams emptyLp = new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.WRAP_CONTENT);
		emptyLp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
		emptyLp.topMargin = dp(96);
		root.addView(emptyView, emptyLp);

		return root;
	}

	private static final long RELOAD_DEBOUNCE_MS = 250L;
	private final android.os.Handler reloadHandler =
			new android.os.Handler(android.os.Looper.getMainLooper());
	private final Runnable reloadTask = () -> {
		if (isAdded()) loadChannels();
	};

	@Override
	public void onStart() {
		super.onStart();
		eventBus.addListener(this);
		loadChannels();
	}

	@Override
	public void onStop() {
		super.onStop();
		eventBus.removeListener(this);
		reloadHandler.removeCallbacks(reloadTask);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ChannelStateChangedEvent
				|| e instanceof ChannelPostReceivedEvent) {
			reloadHandler.removeCallbacks(reloadTask);
			reloadHandler.postDelayed(reloadTask, RELOAD_DEBOUNCE_MS);
		}
	}

	private void loadChannels() {
		ioExecutor.execute(() -> {
			List<ChannelState> channels;
			Map<String, Integer> unread = new HashMap<>();
			Map<String, String> latestPreview = new HashMap<>();
			Map<String, Long> latestTs = new HashMap<>();
			try {
				Collection<ChannelState> c = channelManager.getChannels();
				channels = new ArrayList<>(c);
				for (ChannelState s : channels) {
					String idHex = hex(s.getChannelId());
					unread.put(idHex,
							channelManager.getUnreadCount(s.getChannelId()));
					try {
						List<org.briarproject.briar.api.channel.ChannelPost>
								recent = channelManager.getRecentPosts(
										s.getChannelId(), 1L);
						if (!recent.isEmpty()) {
							org.briarproject.briar.api.channel.ChannelPost
									p = recent.get(recent.size() - 1);
							latestPreview.put(idHex, summarisePost(p));
							latestTs.put(idHex, p.getTimestampHourMs());
						}
					} catch (DbException ignored) {
					}
				}
			} catch (DbException ex) {
				channels = Collections.emptyList();
			}
			List<ChannelState> finalChannels = channels;
			Map<String, Integer> finalUnread = unread;
			Map<String, String> finalPreview = latestPreview;
			Map<String, Long> finalTs = latestTs;
			if (isAdded()) {
				runOnUiThreadUnlessDestroyed(
						() -> render(finalChannels, finalUnread,
								finalPreview, finalTs));
			}
		});
	}

	private String summarisePost(
			org.briarproject.briar.api.channel.ChannelPost p) {
		String body = p.getBody() == null ? "" : p.getBody().trim();
		boolean hasAttachments = !p.getAttachments().isEmpty();
		if (body.isEmpty() && hasAttachments) {
			int count = p.getAttachments().size();
			return getResources().getQuantityString(
					R.plurals.channels_row_attachment_only, count, count);
		}
		String oneLine = body.replace('\n', ' ').replace('\r', ' ');
		int max = 80;
		if (oneLine.length() > max) {
			oneLine = oneLine.substring(0, max) + "…";
		}
		if (hasAttachments) {
			oneLine = "📎 " + oneLine;
		}
		return oneLine;
	}

	private void render(List<ChannelState> channels,
			Map<String, Integer> unread,
			Map<String, String> latestPreview,
			Map<String, Long> latestTs) {
		listContainer.removeAllViews();
		if (channels.isEmpty()) {
			emptyView.setVisibility(View.VISIBLE);
		} else {
			emptyView.setVisibility(View.GONE);
			for (ChannelState s : channels) {
				String idHex = hex(s.getChannelId());
				int count = unread.containsKey(idHex)
						? unread.get(idHex) : 0;
				String preview = latestPreview.get(idHex);
				Long ts = latestTs.get(idHex);
				listContainer.addView(
						buildRow(s, count, preview, ts));
			}
		}
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
		return sb.toString();
	}

	private View buildRow(ChannelState s, int unreadCount,
			@Nullable String latestPreview, @Nullable Long latestTs) {
		View row = LayoutInflater.from(requireContext()).inflate(
				R.layout.list_item_channel, listContainer, false);
		String name = s.getName().isEmpty()
				? getString(R.string.channels_button) : s.getName();

		TextView avatar = row.findViewById(R.id.avatarView);
		TextView nameView = row.findViewById(R.id.channelNameView);
		TextView subtitle = row.findViewById(R.id.channelSubtitleView);
		TextView unreadView = row.findViewById(R.id.channelUnreadCountView);

		avatar.setText(name.substring(0, 1).toUpperCase(Locale.ROOT));
		if (latestTs != null) {
			CharSequence rel = android.text.format.DateUtils
					.getRelativeTimeSpanString(latestTs,
							System.currentTimeMillis(),
							android.text.format.DateUtils.MINUTE_IN_MILLIS,
							android.text.format.DateUtils
									.FORMAT_ABBREV_RELATIVE);
			nameView.setText(name + "  "
					+ "• " + rel.toString());
		} else {
			nameView.setText(name);
		}

		String sub;
		if (latestPreview != null && !latestPreview.isEmpty()) {
			sub = latestPreview;
		} else {
			String visibility = s.isPublicChannel()
					? getString(R.string.channels_row_public)
					: getString(R.string.channels_row_closed);
			sub = s.weArePublisher()
					? visibility + " · "
							+ getString(R.string.channels_row_publisher_self)
					: visibility;
		}
		subtitle.setText(sub);

		if (unreadCount > 0) {
			unreadView.setText(unreadCount > 99 ? "99+"
					: String.valueOf(unreadCount));
			unreadView.setVisibility(View.VISIBLE);
		} else {
			unreadView.setVisibility(View.GONE);
		}

		row.setOnClickListener(v -> startActivity(
				ChannelFeedActivity.intent(requireContext(),
						s.getChannelId())));
		row.setOnLongClickListener(v -> {
			showChannelMenu(s);
			return true;
		});

		View divider = new View(requireContext());
		divider.setBackgroundColor(0x14FFFFFF);
		LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				(int) (0.5f * getResources().getDisplayMetrics().density));
		dlp.leftMargin = dp(82);
		divider.setLayoutParams(dlp);

		LinearLayout wrapper = new LinearLayout(requireContext());
		wrapper.setOrientation(LinearLayout.VERTICAL);
		wrapper.addView(row);
		wrapper.addView(divider);
		return wrapper;
	}

	public void showCreateDialog() {
		View view = LayoutInflater.from(requireContext()).inflate(
				R.layout.dialog_create_channel, null);
		TextInputEditText nameInput =
				view.findViewById(R.id.channelCreateNameInput);
		TextInputEditText descInput =
				view.findViewById(R.id.channelCreateDescriptionInput);
		android.widget.RadioGroup group = view.findViewById(
				R.id.channelCreateVisibilityGroup);
		MaterialCheckBox approvalCheckbox = view.findViewById(
				R.id.channelCreateRequireApprovalCheckbox);

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.channels_create_title)
				.setView(view)
				.setPositiveButton(R.string.channels_create_action,
						(d, w) -> handleCreate(nameInput, descInput, group,
								approvalCheckbox))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void handleCreate(TextInputEditText nameInput,
			TextInputEditText descInput, android.widget.RadioGroup group,
			MaterialCheckBox approvalCheckbox) {
		String name = nameInput.getText() == null
				? "" : nameInput.getText().toString().trim();
		String desc = descInput.getText() == null
				? "" : descInput.getText().toString().trim();
		if (name.isEmpty()) {
			Toast.makeText(requireContext(),
					R.string.channels_create_error_name,
					Toast.LENGTH_SHORT).show();
			return;
		}
		boolean publicChannel = group.getCheckedRadioButtonId()
				== R.id.channelCreatePublicRadio;
		boolean requiresApproval = !publicChannel
				&& approvalCheckbox.isChecked();
		ioExecutor.execute(() -> {
			try {
				ChannelState created = channelManager.createChannel(
						name, desc, publicChannel, requiresApproval);
				if (!isAdded()) return;
				runOnUiThreadUnlessDestroyed(() -> {
					loadChannels();
					shareInvite(created);
				});
			} catch (DbException ex) {
				if (!isAdded()) return;
				runOnUiThreadUnlessDestroyed(() ->
						Toast.makeText(requireContext(),
								R.string.channels_create_failed,
								Toast.LENGTH_LONG).show());
			}
		});
	}

	public void showJoinDialog() {
		View view = LayoutInflater.from(requireContext()).inflate(
				R.layout.dialog_join_channel, null);
		TextInputEditText linkInput =
				view.findViewById(R.id.channelJoinLinkInput);

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.channels_join_title)
				.setView(view)
				.setPositiveButton(R.string.channels_join_action,
						(d, w) -> handleJoin(linkInput))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void handleJoin(TextInputEditText linkInput) {
		String url = linkInput.getText() == null
				? "" : linkInput.getText().toString().trim();
		ChannelInviteLink link = channelManager.parseInviteLink(url);
		if (link == null) {
			Toast.makeText(requireContext(),
					R.string.channels_join_error_link,
					Toast.LENGTH_LONG).show();
			return;
		}
		if (link.requiresApproval()) {
			showApplyDialog(link);
			return;
		}
		android.app.ProgressDialog progress = showJoinProgress();
		final byte[] cid = link.getChannelId();
		ioExecutor.execute(() -> {
			try {
				ChannelState s = channelManager.getChannel(cid);
				if (s != null) {
					if (isAdded()) {
						runOnUiThreadUnlessDestroyed(() -> {
							progress.dismiss();
							Toast.makeText(requireContext(),
									R.string.channels_join_already_subscribed,
									Toast.LENGTH_SHORT).show();
						});
					}
					return;
				}
				channelManager.joinChannel(link);
				ioExecutor.execute(() -> {
					try {
						channelManager.bootstrapChannel(cid);
					} catch (DbException ignored) {
					}
				});
				if (!isAdded()) return;
				runOnUiThreadUnlessDestroyed(() -> {
					progress.dismiss();
					loadChannels();
				});
			} catch (DbException ex) {
				if (!isAdded()) return;
				runOnUiThreadUnlessDestroyed(() -> {
					progress.dismiss();
					Toast.makeText(requireContext(),
							R.string.channels_join_error_link,
							Toast.LENGTH_SHORT).show();
				});
			}
		});
	}

	private android.app.ProgressDialog showJoinProgress() {
		android.app.ProgressDialog d =
				new android.app.ProgressDialog(requireContext());
		d.setMessage(getString(R.string.channels_apply_progress));
		d.setCancelable(false);
		d.show();
		return d;
	}

	private void showApplyDialog(ChannelInviteLink link) {
		View view = LayoutInflater.from(requireContext()).inflate(
				R.layout.dialog_apply_to_join, null);
		TextInputEditText nameInput =
				view.findViewById(R.id.channelApplyNameInput);
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.channels_apply_title)
				.setView(view)
				.setPositiveButton(R.string.channels_apply_action,
						(d, w) -> handleApply(link, nameInput))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void handleApply(ChannelInviteLink link,
			TextInputEditText nameInput) {
		String name = nameInput.getText() == null
				? "" : nameInput.getText().toString().trim();
		if (name.isEmpty()) {
			Toast.makeText(requireContext(),
					R.string.channels_create_error_name,
					Toast.LENGTH_SHORT).show();
			return;
		}
		android.app.ProgressDialog progress = showJoinProgress();
		final byte[] cid = link.getChannelId();
		ioExecutor.execute(() -> {
			try {
				ChannelState existing = channelManager.getChannel(cid);
				if (existing == null) {
					channelManager.joinChannel(link);
				}
				channelManager.applyToJoin(cid, name);
				ioExecutor.execute(() -> {
					try {
						channelManager.bootstrapChannel(cid);
					} catch (DbException ignored) {
					}
				});
				if (!isAdded()) return;
				runOnUiThreadUnlessDestroyed(() -> {
					progress.dismiss();
					loadChannels();
				});
			} catch (DbException ex) {
				if (!isAdded()) return;
				runOnUiThreadUnlessDestroyed(() -> {
					progress.dismiss();
					Toast.makeText(requireContext(),
							R.string.channels_apply_failed,
							Toast.LENGTH_SHORT).show();
				});
			}
		});
	}

	private void showChannelMenu(ChannelState s) {
		List<CharSequence> labels = new ArrayList<>();
		List<Runnable> actions = new ArrayList<>();

		labels.add(getString(R.string.channels_action_share_invite));
		actions.add(() -> shareInvite(s));

		labels.add(getString(R.string.channels_action_send_to_contact));
		actions.add(() -> shareInviteViaSystemSheet(s));

		if (s.weArePublisher()) {
			labels.add(getString(R.string.channels_delegations_title));
			actions.add(() -> startActivity(
					ChannelDelegationsActivity.intent(
							requireContext(), s.getChannelId())));
			labels.add(getString(R.string.channels_subscribers_title));
			actions.add(() -> startActivity(
					ChannelSubscribersActivity.intent(
							requireContext(), s.getChannelId())));
			if (s.requiresApproval()) {
				labels.add(getString(R.string.channels_pending_title));
				actions.add(() -> startActivity(
						ChannelPendingApplicationsActivity.intent(
								requireContext(), s.getChannelId())));
			}
		}

		if (s.weArePublisher() && !s.isPublicChannel()) {
			labels.add(getString(R.string.channels_action_rotate_capability));
			actions.add(() -> confirmRotate(s));
		}

		if (!s.weArePublisher()) {
			labels.add(getString(R.string.channels_announce_action));
			actions.add(() -> showAnnounceDialog(s));
		}

		if (s.weArePublisher()) {
			labels.add(getString(R.string.channels_action_delete_channel));
			actions.add(() -> confirmDelete(s));
		} else {
			labels.add(getString(R.string.channels_action_leave));
			actions.add(() -> confirmLeave(s));
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(s.getName())
				.setItems(labels.toArray(new CharSequence[0]),
						(d, which) -> actions.get(which).run())
				.show();
	}

	private void showAnnounceDialog(ChannelState s) {
		View view = LayoutInflater.from(requireContext()).inflate(
				R.layout.dialog_announce_channel, null);
		TextInputEditText nameInput =
				view.findViewById(R.id.channelAnnounceNameInput);
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.channels_announce_title)
				.setMessage(R.string.channels_announce_message)
				.setView(view)
				.setPositiveButton(R.string.channels_announce_action,
						(d, w) -> handleAnnounce(s, nameInput))
				.setNegativeButton(R.string.channels_announce_skip, null)
				.show();
	}

	private void handleAnnounce(ChannelState s,
			TextInputEditText nameInput) {
		String name = nameInput.getText() == null
				? "" : nameInput.getText().toString().trim();
		if (name.isEmpty()) {
			Toast.makeText(requireContext(),
					R.string.channels_announce_failed,
					Toast.LENGTH_SHORT).show();
			return;
		}
		ioExecutor.execute(() -> {
			try {
				channelManager.announceMyself(s.getChannelId(), name);
			} catch (DbException ignored) {
				if (!isAdded()) return;
				runOnUiThreadUnlessDestroyed(() ->
						Toast.makeText(requireContext(),
								R.string.channels_announce_failed,
								Toast.LENGTH_LONG).show());
			}
		});
	}

	private void shareInvite(ChannelState s) {
		toastPreparingInvite();
		ioExecutor.execute(() -> {
			try {
				String link = channelManager.exportInviteLink(
						s.getChannelId());
				if (!isAdded()) return;
				runOnUiThreadUnlessDestroyed(() ->
						copyAndToast(link));
			} catch (DbException e) {
				runOnUiThreadUnlessDestroyed(this::toastInviteNotReady);
			}
		});
	}

	private void toastInviteNotReady() {
		if (!isAdded()) return;
		Toast.makeText(requireContext(),
				R.string.channels_invite_not_ready,
				Toast.LENGTH_LONG).show();
	}

	private void toastPreparingInvite() {
		if (!isAdded()) return;
		Toast.makeText(requireContext(),
				R.string.channels_invite_preparing,
				Toast.LENGTH_SHORT).show();
	}

	private void copyAndToast(String link) {
		com.professor.zerion.android.util.SecureClipboard.copy(
				requireContext(), "zerion-channel", link);
		Toast.makeText(requireContext(),
				R.string.channels_invite_copied,
				Toast.LENGTH_SHORT).show();
	}

	private static void markSensitive(ClipData clip) {
		if (android.os.Build.VERSION.SDK_INT
				>= android.os.Build.VERSION_CODES.TIRAMISU) {
			android.os.PersistableBundle extras =
					new android.os.PersistableBundle();
			extras.putBoolean(
					android.content.ClipDescription
							.EXTRA_IS_SENSITIVE,
					true);
			clip.getDescription().setExtras(extras);
		}
	}

	private void shareInviteViaSystemSheet(ChannelState s) {
		toastPreparingInvite();
		ioExecutor.execute(() -> {
			try {
				String link = channelManager.exportInviteLink(
						s.getChannelId());
				if (!isAdded()) return;
				runOnUiThreadUnlessDestroyed(() -> {
					String body = getString(
							R.string.channels_share_message_prefix) + link;
					android.content.Intent send =
							new android.content.Intent(
									android.content.Intent.ACTION_SEND);
					send.setType("text/plain");
					send.putExtra(android.content.Intent.EXTRA_TEXT, body);
					startActivity(android.content.Intent.createChooser(send,
							getString(
									R.string.channels_share_chooser_title)));
				});
			} catch (DbException e) {
				runOnUiThreadUnlessDestroyed(this::toastInviteNotReady);
			}
		});
	}

	private void confirmRotate(ChannelState s) {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.channels_invite_rotate_confirm_title)
				.setMessage(R.string.channels_invite_rotate_confirm_message)
				.setPositiveButton(R.string.channels_action_rotate_capability,
						(d, w) -> rotate(s))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void rotate(ChannelState s) {
		ioExecutor.execute(() -> {
			try {
				channelManager.rotateJoinCapability(s.getChannelId());
				if (!isAdded()) return;
				runOnUiThreadUnlessDestroyed(() -> shareInvite(s));
			} catch (DbException ignored) {
			}
		});
	}

	private void confirmLeave(ChannelState s) {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.channels_leave_confirm_title)
				.setMessage(R.string.channels_leave_confirm_message)
				.setPositiveButton(R.string.channels_leave_confirm_action,
						(d, w) -> doLeave(s))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void confirmDelete(ChannelState s) {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.channels_delete_confirm_title)
				.setMessage(R.string.channels_delete_confirm_message)
				.setPositiveButton(R.string.channels_delete_confirm_action,
						(d, w) -> doLeave(s))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void doLeave(ChannelState s) {
		ioExecutor.execute(() -> {
			try {
				channelManager.leaveChannel(s.getChannelId());
				if (!isAdded()) return;
				runOnUiThreadUnlessDestroyed(this::loadChannels);
			} catch (DbException ignored) {
			}
		});
	}

	private int dp(int value) {
		return (int) (value
				* getResources().getDisplayMetrics().density);
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}
}
