package com.professor.zerion.android.grouptr;

import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.homelist.HomeInvite;
import com.professor.zerion.android.homelist.HomeItem;
import com.professor.zerion.android.homelist.HomeKeys;
import com.professor.zerion.android.homelist.HomeList;
import com.professor.zerion.android.homelist.HomeListAdapter;
import com.professor.zerion.android.homelist.HomeRow;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.util.StringUtils;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrPendingInvite;
import org.zerionproject.app.api.grouptr.GroupTrPostSummary;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.messaging.event.GroupMembershipChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupEpochCommitEvent;
import org.zerionproject.app.api.messaging.event.GroupTrPostAcceptedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrPostsRemovedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrLocalStateChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrSelfRemovedEvent;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class GroupTrListFragment extends BaseFragment
		implements EventListener, HomeListAdapter.Listener {

	public static final String TAG = GroupTrListFragment.class.getName();

	private static final long RELOAD_DEBOUNCE_MS = 250L;

	public static GroupTrListFragment newInstance() {
		return new GroupTrListFragment();
	}

	@Inject
	GroupTrManager groupTrManager;
	@Inject
	EventBus eventBus;
	@Inject
	@IoExecutor
	Executor ioExecutor;
	@Inject
	IdentityManager identityManager;
	@Inject
	ContactManager contactManager;

	@Nullable
	private HomeList home;
	private int generation = 0;
	private final Handler reloadHandler = new Handler(Looper.getMainLooper());
	private final Runnable reloadTask = () -> {
		if (isAdded()) loadGroups();
	};

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		requireActivity().setTitle(R.string.groups_button);
		home = HomeList.inflate(inflater, container, this, ioExecutor,
				R.drawable.ic_group, R.string.groups_list_empty,
				R.string.groups_list_empty_action);
		home.reloadWhenAPreviewExpires(this::loadGroups);
		return home.getView();
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		if (home != null) home.release();
		home = null;
	}

	@Override
	public void onStart() {
		super.onStart();
		eventBus.addListener(this);
		loadGroups();
	}

	@Override
	public void onStop() {
		super.onStop();
		eventBus.removeListener(this);
		reloadHandler.removeCallbacks(reloadTask);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof GroupMembershipChangedEvent
				|| e instanceof GroupEpochCommitEvent
				|| e instanceof GroupTrLocalStateChangedEvent
				|| e instanceof GroupTrSelfRemovedEvent
				|| e instanceof GroupTrPostAcceptedEvent
				|| e instanceof GroupTrPostsRemovedEvent) {
			runOnUiThreadUnlessDestroyed(() -> {
				if (!isAdded()) return;
				if (e instanceof GroupMembershipChangedEvent) {
					GroupMembershipChangedEvent ev =
							(GroupMembershipChangedEvent) e;
					if (ev.getKind() == GroupMembershipChangedEvent
							.ChangeKind.MEMBER_ADDED) {
						Toast.makeText(requireContext(),
								R.string.grouptr_invite_received_toast,
								Toast.LENGTH_LONG).show();
					}
				} else if (e instanceof GroupTrSelfRemovedEvent) {
					Toast.makeText(requireContext(),
							R.string.grouptr_removed_neutral,
							Toast.LENGTH_LONG).show();
				}
				reloadHandler.removeCallbacks(reloadTask);
				reloadHandler.postDelayed(reloadTask, RELOAD_DEBOUNCE_MS);
			});
		}
	}

	private void loadGroups() {
		int load = ++generation;
		Resources res = getResources();
		ioExecutor.execute(() -> {
			List<HomeItem> items = new ArrayList<>();
			HomeKeys keys = new HomeKeys();
			boolean failed = false;
			try {
				for (GroupTrPendingInvite p :
						groupTrManager.getPendingInvites()) {
					items.add(invite(res, p, keys));
				}
				byte[] me = localKey();
				Map<String, String> contacts = contactNames();
				List<HomeRow> rows = new ArrayList<>();
				for (GroupTrState s : groupTrManager.getGroups()) {
					rows.add(row(res, s, me, contacts));
				}
				Collections.sort(rows, (a, b) ->
						Long.compare(b.getTime(), a.getTime()));
				items.addAll(rows);
			} catch (DbException ex) {
				items.clear();
				failed = true;
			}
			boolean error = failed;
			runOnUiThreadUnlessDestroyed(() -> {
				if (home == null || load != generation) return;
				if (error) home.showError(items);
				else home.show(items);
			});
		});
	}

	@Nullable
	private byte[] localKey() {
		try {
			return identityManager.getLocalAuthor().getPublicKey()
					.getEncoded();
		} catch (DbException | RuntimeException ex) {
			return null;
		}
	}

	private Map<String, String> contactNames() {
		Map<String, String> names = new HashMap<>();
		try {
			for (Contact c : contactManager.getContacts()) {
				String name = c.getAlias() != null ? c.getAlias()
						: c.getAuthor().getName();
				names.put(StringUtils.toHexString(
						c.getAuthor().getPublicKey().getEncoded()), name);
			}
		} catch (DbException | RuntimeException ex) {
			names.clear();
		}
		return names;
	}

	private static String groupName(Resources res, String name) {
		return name.isEmpty() ? res.getString(R.string.grouptr_unnamed_group)
				: name;
	}

	private static HomeInvite invite(Resources res, GroupTrPendingInvite p,
			HomeKeys keys) {
		String name = groupName(res, p.getGroupName());
		return new HomeInvite(keys.next("invite", StringUtils.toHexString(
				p.getGroupId())), res.getString(
				R.string.grouptr_pending_invite_title, name,
				p.getCreatorName()), name, p.getGroupId());
	}

	private HomeRow row(Resources res, GroupTrState s, @Nullable byte[] me,
			Map<String, String> contacts) {
		byte[] gid = s.getGroupId();
		GroupTrPostSummary latest;
		boolean blocked;
		try {
			latest = groupTrManager.getLatestPostSummary(gid);
			blocked = groupTrManager.isLocalScreenshotBlocked(gid);
		} catch (DbException ex) {
			latest = null;
			blocked = true;
		}
		String sender = null;
		String preview = null;
		long time = 0;
		long expiresAt = Long.MAX_VALUE;
		if (latest != null) {
			time = latest.getTimestamp();
			expiresAt = latest.getExpiryTime();
			preview = GroupRowPreview.summary(res, latest);
			sender = GroupRowPreview.sender(latest.getSenderPubKey(),
					latest.getSenderName(), GroupRowPreview.memberNames(s),
					contacts, me, res.getString(R.string.you),
					res.getString(R.string.grouptr_sender_incognito_format));
		}
		if (s.isDissolved()) {
			sender = null;
			preview = res.getString(R.string.home_row_group_closed);
		} else if (preview == null || preview.isEmpty() || blocked) {
			int members = s.getMembers().size();
			sender = null;
			preview = res.getQuantityString(R.plurals.grouptr_member_count,
					members, members);
		}
		return HomeRow.builder("group:" + StringUtils.toHexString(gid),
				groupName(res, s.getName()))
				.time(time)
				.preview(sender, preview)
				.expiresAt(expiresAt)
				.unread(groupTrManager.getUnreadCount(gid))
				.payload(s)
				.build();
	}

	@Override
	public void onRowClick(HomeRow row) {
		if (!(row.getPayload() instanceof GroupTrState)) return;
		GroupTrState s = (GroupTrState) row.getPayload();
		startActivity(GroupTrConversationActivity.intent(requireContext(),
				s.getGroupId()));
	}

	@Override
	public boolean onRowLongClick(HomeRow row, View anchor) {
		if (!(row.getPayload() instanceof GroupTrState)) return false;
		showDeleteDialog((GroupTrState) row.getPayload());
		return true;
	}

	@Override
	public void onRetry() {
		loadGroups();
	}

	@Override
	public void onInviteAccepted(HomeInvite invite) {
		askToJoin(invite.getId(), invite.getName());
	}

	@Override
	public void onInviteDeclined(HomeInvite invite) {
		respondToInvite(invite.getId(), false);
	}

	private void askToJoin(byte[] groupId, String groupName) {
		ioExecutor.execute(() -> {
			boolean available;
			try {
				available = groupTrManager.canAcceptIncognito(groupId, false);
			} catch (DbException ex) {
				available = false;
			}
			boolean incognitoAvailable = available;
			runOnUiThreadUnlessDestroyed(() -> {
				if (!isAdded()) return;
				GroupTrIncognitoPicker.askToJoin(requireContext(), groupName,
						incognitoAvailable,
						incognito -> respondToInvite(groupId, true, incognito));
			});
		});
	}

	private void respondToInvite(byte[] groupId, boolean accept) {
		respondToInvite(groupId, accept, null);
	}

	private void respondToInvite(byte[] groupId, boolean accept,
			@Nullable String incognitoName) {
		ioExecutor.execute(() -> {
			try {
				if (accept) {
					groupTrManager.acceptInvite(groupId, incognitoName);
				} else {
					groupTrManager.declineInvite(groupId);
				}
			} catch (DbException ex) {
				runOnUiThreadUnlessDestroyed(() -> {
					if (!isAdded()) return;
					Toast.makeText(requireContext(),
							R.string.grouptr_error_respond,
							Toast.LENGTH_SHORT).show();
				});
			}
			runOnUiThreadUnlessDestroyed(this::loadGroups);
		});
	}

	private void showDeleteDialog(GroupTrState s) {
		String name = groupName(getResources(), s.getName());
		int msgRes = s.isDissolved()
				? R.string.grouptr_remove_local_dissolved_msg
				: R.string.grouptr_remove_local_active_msg;
		new SecureAlertDialogBuilder(requireContext())
				.setTitle(getString(
						R.string.grouptr_remove_local_title, name))
				.setMessage(msgRes)
				.setPositiveButton(R.string.grouptr_remove_local_confirm,
						(d, w) -> removeLocal(s.getGroupId()))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void removeLocal(byte[] gid) {
		ioExecutor.execute(() -> {
			try {
				groupTrManager.removeFromDevice(gid);
				if (isAdded()) {
					runOnUiThreadUnlessDestroyed(this::loadGroups);
				}
			} catch (DbException ex) {
				if (isAdded()) {
					runOnUiThreadUnlessDestroyed(() -> Toast
							.makeText(requireContext(),
									R.string.grouptr_error_save,
									Toast.LENGTH_SHORT).show());
				}
			}
		});
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}
}
