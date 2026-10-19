package com.professor.zerion.android.channel;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.homelist.HomeItem;
import com.professor.zerion.android.homelist.HomeKeys;
import com.professor.zerion.android.homelist.HomeList;
import com.professor.zerion.android.homelist.HomeListAdapter;
import com.professor.zerion.android.homelist.HomeNotice;
import com.professor.zerion.android.homelist.HomePreview;
import com.professor.zerion.android.homelist.HomeRow;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.util.StringUtils;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelInviteLink;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelRejoinResult;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.event.ChannelPostReceivedEvent;
import org.zerionproject.app.api.channel.event.ChannelStateChangedEvent;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChannelListFragment extends BaseFragment
		implements EventListener, HomeListAdapter.Listener {

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

	@Nullable
	private HomeList home;
	private int generation = 0;

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
		home = HomeList.inflate(inflater, container, this, ioExecutor,
				R.drawable.ic_channels, R.string.channels_list_empty, 0);
		home.reloadWhenAPreviewExpires(this::loadChannels);
		return home.getView();
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		if (home != null) home.release();
		home = null;
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
		int load = ++generation;
		Context ctx = requireContext();
		Context app = ctx.getApplicationContext();
		Resources res = ctx.getResources();
		ioExecutor.execute(() -> {
			List<HomeItem> items = new ArrayList<>();
			HomeKeys keys = new HomeKeys();
			for (String name : ownerDeletedNames()) {
				items.add(new HomeNotice(keys.next("deleted", name),
						name.isEmpty()
						? res.getString(
								R.string.channels_owner_deleted_notice_unnamed)
						: res.getString(R.string.channels_owner_deleted_notice,
								name), name));
			}
			List<HomeRow> rows = new ArrayList<>();
			boolean failed = false;
			try {
				for (ChannelState s : channelManager.getChannels()) {
					rows.add(row(ctx, app, s));
				}
			} catch (DbException ex) {
				rows.clear();
				failed = true;
			}
			boolean error = failed;
			Collections.sort(rows, (a, b) ->
					Long.compare(b.getTime(), a.getTime()));
			items.addAll(rows);
			runOnUiThreadUnlessDestroyed(() -> {
				if (home == null || load != generation) return;
				if (error) home.showError(items);
				else home.show(items);
			});
		});
	}

	private HomeRow row(Context ctx, Context app, ChannelState s)
			throws DbException {
		byte[] id = s.getChannelId();
		ChannelPost latest = null;
		try {
			List<ChannelPost> recent = channelManager.getRecentPosts(id, 1L);
			if (!recent.isEmpty()) latest = recent.get(recent.size() - 1);
		} catch (DbException ignored) {
		}
		long expiresAt = latest == null ? Long.MAX_VALUE : expiryOf(latest);
		if (System.currentTimeMillis() >= expiresAt) {
			latest = null;
			expiresAt = Long.MAX_VALUE;
		}
		String preview = latest == null ? "" : summarisePost(ctx, latest);
		String sender = null;
		if (preview.isEmpty()) {
			preview = ChannelAccessLabel.withRole(ctx, s);
		} else if (s.weArePublisher() && latest != null
				&& !latest.signedByDelegate()) {
			sender = ctx.getString(R.string.you);
		}
		String name = s.getName().isEmpty()
				? ctx.getString(R.string.channels_button) : s.getName();
		return HomeRow.builder("channel:" + StringUtils.toHexString(id), name)
				.time(latest == null ? 0 : latest.getTimestampHourMs())
				.hourPrecision(true)
				.preview(sender, preview)
				.expiresAt(expiresAt)
				.unread(channelManager.getUnreadCount(id))
				.muted(ChannelFeedActivity.isChannelMuted(app, id))
				.payload(s)
				.build();
	}

	static long expiryOf(ChannelPost p) {
		long ttl = p.getTtlMs();
		if (ttl <= 0L) return Long.MAX_VALUE;
		long at = p.getTimestampHourMs();
		return at > Long.MAX_VALUE - ttl ? Long.MAX_VALUE : at + ttl;
	}

	private List<String> ownerDeletedNames() {
		try {
			List<String> names = channelManager.getOwnerDeletedChannelNames();
			return names == null ? Collections.<String>emptyList() : names;
		} catch (DbException ignored) {
			return Collections.emptyList();
		}
	}

	private void dismissOwnerDeleted(String name) {
		ioExecutor.execute(() -> {
			try {
				channelManager.dismissOwnerDeletedChannelName(name);
			} catch (DbException ignored) {
			}
			if (isAdded()) runOnUiThreadUnlessDestroyed(this::loadChannels);
		});
	}

	static String summarisePost(Context ctx, ChannelPost p) {
		if (p.isWithheld()) {
			return HomePreview.oneLine(
					ctx.getString(R.string.channels_post_withheld));
		}
		if (ChannelConstants.DELETED_POST_PLACEHOLDER.equals(p.getBody())) {
			return ctx.getString(R.string.channels_post_deleted);
		}
		String body = p.getBody() == null ? "" : p.getBody().trim();
		boolean hasAttachments = !p.getAttachments().isEmpty();
		if (body.isEmpty() && hasAttachments) {
			int count = p.getAttachments().size();
			return ctx.getResources().getQuantityString(
					R.plurals.channels_row_attachment_only, count, count);
		}
		String oneLine = HomePreview.oneLine(body);
		if (hasAttachments && !oneLine.isEmpty()) {
			oneLine = ATTACHMENT_MARK + oneLine;
		}
		return oneLine;
	}

	private static final String ATTACHMENT_MARK = "\uD83D\uDCCE ";

	@Override
	public void onRowClick(HomeRow row) {
		if (!(row.getPayload() instanceof ChannelState)) return;
		startActivity(ChannelFeedActivity.intent(requireContext(),
				((ChannelState) row.getPayload()).getChannelId()));
	}

	@Override
	public boolean onRowLongClick(HomeRow row, View anchor) {
		if (!(row.getPayload() instanceof ChannelState)) return false;
		showChannelMenu((ChannelState) row.getPayload());
		return true;
	}

	@Override
	public void onRetry() {
		loadChannels();
	}

	@Override
	public void onNoticeDismissed(HomeNotice notice) {
		generation++;
		if (home != null) home.remove(notice.getKey());
		dismissOwnerDeleted(notice.getPayload());
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
		matchApprovalToAccess(group, approvalCheckbox);
		group.setOnCheckedChangeListener((g, checkedId) ->
				matchApprovalToAccess(group, approvalCheckbox));

		new SecureAlertDialogBuilder(requireContext())
				.setTitle(R.string.channels_create_title)
				.setView(view)
				.setPositiveButton(R.string.channels_create_action,
						(d, w) -> handleCreate(nameInput, descInput, group,
								approvalCheckbox))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private static void matchApprovalToAccess(
			android.widget.RadioGroup group, MaterialCheckBox approval) {
		boolean publicChannel = group.getCheckedRadioButtonId()
				== R.id.channelCreatePublicRadio;
		approval.setEnabled(!publicChannel);
		if (publicChannel) approval.setChecked(false);
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

		new SecureAlertDialogBuilder(requireContext())
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
		android.app.ProgressDialog progress = showJoinProgress();
		final byte[] cid = link.getChannelId();
		ioExecutor.execute(() -> {
			try {
				ChannelState s = channelManager.getChannel(cid);
				if (s != null) {
					checkHeld(link, progress);
					return;
				}
				if (link.requiresApproval()) {
					runOnUiThreadUnlessDestroyed(() -> {
						try { progress.dismiss(); } catch (Exception ignored) { }
						if (!isAdded()) return;
						showApplyDialog(link, 0);
					});
					return;
				}
				channelManager.joinChannel(link);
				ioExecutor.execute(() -> {
					try {
						channelManager.bootstrapChannel(cid);
					} catch (DbException ignored) {
					}
				});
				runOnUiThreadUnlessDestroyed(() -> {
					try { progress.dismiss(); } catch (Exception ignored) { }
					if (!isAdded()) return;
					loadChannels();
				});
			} catch (DbException ex) {
				runOnUiThreadUnlessDestroyed(() -> {
					try { progress.dismiss(); } catch (Exception ignored) { }
					if (!isAdded()) return;
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
		com.professor.zerion.android.security.SecureDialogs.applyHostPolicy(d);
		d.show();
		return d;
	}

	private void checkHeld(ChannelInviteLink link,
			android.app.ProgressDialog progress) {
		String name = nameOf(link.getChannelId());
		ChannelRejoinResult result;
		try {
			result = channelManager.rejoinChannel(link);
		} catch (DbException ex) {
			runOnUiThreadUnlessDestroyed(() -> {
				try { progress.dismiss(); } catch (Exception ignored) { }
			});
			return;
		}
		runOnUiThreadUnlessDestroyed(() -> {
			try { progress.dismiss(); } catch (Exception ignored) { }
			if (!isAdded()) return;
			if (result == ChannelRejoinResult.DELETED) {
				loadChannels();
				Toast.makeText(requireContext(),
						ChannelInviteHandlerActivity.deletedMessage(
								requireContext(), name),
						Toast.LENGTH_LONG).show();
			} else if (ChannelInviteHandlerActivity.applyMessage(result)
					!= 0) {
				showApplyDialog(link,
						ChannelInviteHandlerActivity.applyMessage(result));
			} else if (result == ChannelRejoinResult.CHECK_LINK) {
				showTryLinkDialog(link);
			} else if (ChannelInviteHandlerActivity.rejoinMessage(result)
					!= 0) {
				Toast.makeText(requireContext(),
						ChannelInviteHandlerActivity.rejoinMessage(result),
						Toast.LENGTH_LONG).show();
			} else {
				Toast.makeText(requireContext(),
						R.string.channels_join_already_subscribed,
						Toast.LENGTH_SHORT).show();
			}
		});
	}

	private void showTryLinkDialog(ChannelInviteLink link) {
		new SecureAlertDialogBuilder(requireContext())
				.setTitle(R.string.channels_rejoin_check_title)
				.setMessage(R.string.channels_rejoin_check_message)
				.setPositiveButton(R.string.channels_rejoin_check_action,
						(d, w) -> tryLink(link))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void tryLink(ChannelInviteLink link) {
		android.app.ProgressDialog progress = showJoinProgress();
		final byte[] cid = link.getChannelId();
		ioExecutor.execute(() -> {
			String name = nameOf(cid);
			ChannelRejoinResult result;
			try {
				result = channelManager.rejoinChannelThroughLink(link);
			} catch (DbException ex) {
				runOnUiThreadUnlessDestroyed(() -> {
					try { progress.dismiss(); } catch (Exception ignored) { }
					if (!isAdded()) return;
					Toast.makeText(requireContext(),
							R.string.channels_rejoin_unreachable,
							Toast.LENGTH_LONG).show();
				});
				return;
			}
			if (result == ChannelRejoinResult.UPDATED) {
				ioExecutor.execute(() -> {
					try {
						channelManager.refreshChannel(cid);
					} catch (DbException ignored) {
					}
				});
			} else if (result == ChannelRejoinResult.TRYING) {
				ioExecutor.execute(() -> {
					try {
						channelManager.bootstrapChannel(cid);
					} catch (DbException ignored) {
					}
				});
			}
			runOnUiThreadUnlessDestroyed(() -> {
				try { progress.dismiss(); } catch (Exception ignored) { }
				if (!isAdded()) return;
				if (result == ChannelRejoinResult.DELETED) {
					loadChannels();
					Toast.makeText(requireContext(),
							ChannelInviteHandlerActivity.deletedMessage(
									requireContext(), name),
							Toast.LENGTH_LONG).show();
				} else if (result == ChannelRejoinResult.UPDATED) {
					loadChannels();
					Toast.makeText(requireContext(),
							getString(R.string.channels_rejoin_updated, name),
							Toast.LENGTH_LONG).show();
					startActivity(ChannelFeedActivity.intent(
							requireContext(), cid));
				} else if (ChannelInviteHandlerActivity.rejoinMessage(result)
						!= 0) {
					Toast.makeText(requireContext(),
							ChannelInviteHandlerActivity.rejoinMessage(result),
							Toast.LENGTH_LONG).show();
				} else {
					Toast.makeText(requireContext(),
							R.string.channels_join_already_subscribed,
							Toast.LENGTH_SHORT).show();
				}
			});
		});
	}

	private String nameOf(byte[] channelId) {
		try {
			ChannelState s = channelManager.getChannel(channelId);
			return s == null ? "" : s.getName();
		} catch (DbException e) {
			return "";
		}
	}

	private void showApplyDialog(ChannelInviteLink link, int message) {
		View view = LayoutInflater.from(requireContext()).inflate(
				R.layout.dialog_apply_to_join, null);
		if (message != 0) {
			((TextView) view.findViewById(R.id.channelApplyMessage))
					.setText(message);
		}
		TextInputEditText nameInput =
				view.findViewById(R.id.channelApplyNameInput);
		nameInput.setFilters(new android.text.InputFilter[] {
				new com.professor.zerion.android.util.Utf8ByteLengthFilter(
						org.zerionproject.app.api.channel.ChannelConstants
								.MAX_DISPLAY_NAME_BYTES)});
		new SecureAlertDialogBuilder(requireContext())
				.setTitle(R.string.channels_request_access_title)
				.setView(view)
				.setPositiveButton(R.string.channels_request_access_action,
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
					R.string.channels_request_access_name_missing,
					Toast.LENGTH_SHORT).show();
			return;
		}
		android.app.ProgressDialog progress = showJoinProgress();
		final byte[] cid = link.getChannelId();
		ioExecutor.execute(() -> {
			try {
				ChannelState existing = channelManager.getChannel(cid);
				try {
					if (existing == null) {
						channelManager.joinChannel(link);
						channelManager.applyToJoin(cid, name);
					} else {
						channelManager.applyToJoin(link, name);
					}
				} catch (DbException ex) {
					runOnUiThreadUnlessDestroyed(() -> {
						try { progress.dismiss(); } catch (Exception ignored) { }
						if (!isAdded()) return;
						loadChannels();
						Toast.makeText(requireContext(),
								ex instanceof org.zerionproject.app.api.channel
										.ChannelApplicationUnconfirmedException
										? R.string.channels_request_access_unconfirmed
										: R.string.channels_request_access_failed,
								Toast.LENGTH_LONG).show();
					});
					return;
				}
				ioExecutor.execute(() -> {
					try {
						channelManager.bootstrapChannel(cid);
					} catch (DbException ignored) {
					}
				});
				runOnUiThreadUnlessDestroyed(() -> {
					try { progress.dismiss(); } catch (Exception ignored) { }
					if (!isAdded()) return;
					loadChannels();
				});
			} catch (DbException ex) {
				runOnUiThreadUnlessDestroyed(() -> {
					try { progress.dismiss(); } catch (Exception ignored) { }
					if (!isAdded()) return;
					Toast.makeText(requireContext(),
							R.string.channels_request_access_failed,
							Toast.LENGTH_SHORT).show();
				});
			}
		});
	}

	private void showChannelMenu(ChannelState s) {
		List<CharSequence> labels = new ArrayList<>();
		List<Runnable> actions = new ArrayList<>();

		labels.add(getString(R.string.channels_settings_title));
		actions.add(() -> startActivity(ChannelSettingsActivity.intent(
				requireContext(), s.getChannelId())));

		labels.add(getString(R.string.channels_action_share_invite));
		actions.add(() -> shareInvite(s));

		labels.add(getString(R.string.channels_action_send_to_contact));
		actions.add(() -> ChannelShare.sendInvite(requireActivity(),
				channelManager, ioExecutor, s.getChannelId()));

		new SecureAlertDialogBuilder(requireContext())
				.setTitle(s.getName())
				.setItems(labels.toArray(new CharSequence[0]),
						(d, which) -> actions.get(which).run())
				.show();
	}

	private void shareInvite(ChannelState s) {
		ChannelShare.copyInvite(requireActivity(), channelManager,
				ioExecutor, s.getChannelId());
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}
}
