package com.professor.zerion.android.grouptr;

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

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrPendingInvite;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.messaging.event.GroupMembershipChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupEpochCommitEvent;
import org.zerionproject.app.api.messaging.event.GroupPostReceivedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrLocalStateChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrSelfRemovedEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class GroupTrListFragment extends BaseFragment
		implements EventListener {

	public static final String TAG = GroupTrListFragment.class.getName();

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

	private LinearLayout listContainer;
	private TextView emptyView;

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

		emptyView = new TextView(requireContext());
		emptyView.setText(R.string.groups_list_empty);
		emptyView.setTextSize(16);
		emptyView.setGravity(Gravity.CENTER);
		emptyView.setPadding(dp(32), dp(64), dp(32), dp(32));
		FrameLayout.LayoutParams emptyLp = new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.WRAP_CONTENT);
		emptyLp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
		emptyLp.topMargin = dp(96);
		root.addView(emptyView, emptyLp);

		return root;
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
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof GroupMembershipChangedEvent
				|| e instanceof GroupEpochCommitEvent
				|| e instanceof GroupTrLocalStateChangedEvent
				|| e instanceof GroupTrSelfRemovedEvent
				|| e instanceof GroupPostReceivedEvent) {
			runOnUiThreadUnlessDestroyed(() -> {
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
					GroupTrSelfRemovedEvent ev =
							(GroupTrSelfRemovedEvent) e;
					String name = ev.getGroupName().isEmpty()
							? getString(R.string.grouptr_unnamed_group)
							: ev.getGroupName();
					Toast.makeText(requireContext(),
							getString(R.string.grouptr_removed_by_admin,
									name),
							Toast.LENGTH_LONG).show();
				}
				loadGroups();
			});
		}
	}

	private void loadGroups() {
		ioExecutor.execute(() -> {
			List<GroupTrState> groups;
			Collection<GroupTrPendingInvite> pending;
			Map<String, Integer> unread = new HashMap<>();
			try {
				Collection<GroupTrState> c = groupTrManager.getGroups();
				groups = new ArrayList<>(c);
				for (GroupTrState s : groups) {
					unread.put(hex(s.getGroupId()),
							groupTrManager.getUnreadCount(s.getGroupId()));
				}
				pending = groupTrManager.getPendingInvites();
			} catch (DbException ex) {
				groups = Collections.emptyList();
				pending = Collections.emptyList();
			}
			List<GroupTrState> finalGroups = groups;
			Collection<GroupTrPendingInvite> finalPending = pending;
			Map<String, Integer> finalUnread = unread;
			runOnUiThreadUnlessDestroyed(
					() -> renderGroups(finalGroups, finalUnread,
							finalPending));
		});
	}

	private void renderGroups(List<GroupTrState> groups,
			Map<String, Integer> unread,
			Collection<GroupTrPendingInvite> pending) {
		listContainer.removeAllViews();
		if (groups.isEmpty() && pending.isEmpty()) {
			emptyView.setVisibility(View.VISIBLE);
			return;
		}
		emptyView.setVisibility(View.GONE);
		for (GroupTrPendingInvite p : pending) {
			listContainer.addView(buildPendingRow(p));
		}
		for (GroupTrState s : groups) {
			int count = unread.containsKey(hex(s.getGroupId()))
					? unread.get(hex(s.getGroupId())) : 0;
			listContainer.addView(buildRow(s, count));
		}
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
		return sb.toString();
	}

	private View buildRow(GroupTrState s, int unreadCount) {
		View row = LayoutInflater.from(requireContext()).inflate(
				R.layout.list_item_grouptr_group, listContainer, false);
		String name = s.getName().isEmpty()
				? getString(R.string.grouptr_unnamed_group)
				: s.getName();
		TextView avatar = row.findViewById(R.id.avatarView);
		TextView nameView = row.findViewById(R.id.nameView);
		TextView subtitle = row.findViewById(R.id.subtitleView);
		TextView unreadView = row.findViewById(R.id.unreadCountView);

		avatar.setText(name.substring(0, 1).toUpperCase());
		nameView.setText(name);

		if (unreadCount > 0) {
			unreadView.setText(unreadCount > 99
					? "99+" : String.valueOf(unreadCount));
			unreadView.setVisibility(View.VISIBLE);
		} else {
			unreadView.setVisibility(View.GONE);
		}

		int memberCount = s.getMembers().size();
		String sub = getResources().getQuantityString(
				R.plurals.grouptr_member_count, memberCount, memberCount);
		if (s.isDissolved()) {
			sub = sub + " · "
					+ getString(R.string.grouptr_dissolved_suffix);
		}
		subtitle.setText(sub);

		row.setOnClickListener(v ->
				startActivity(GroupTrConversationActivity.intent(
						requireContext(), s.getGroupId())));
		row.setOnLongClickListener(v -> {
			showDeleteDialog(s);
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

	private View buildPendingRow(GroupTrPendingInvite p) {
		LinearLayout container = new LinearLayout(requireContext());
		container.setOrientation(LinearLayout.VERTICAL);
		container.setPadding(dp(16), dp(12), dp(16), dp(12));

		String name = p.getGroupName().isEmpty()
				? getString(R.string.grouptr_unnamed_group)
				: p.getGroupName();
		TextView title = new TextView(requireContext());
		title.setText(getString(R.string.grouptr_pending_invite_title,
				name, p.getCreatorName()));
		title.setTextSize(16);
		container.addView(title);

		LinearLayout buttons = new LinearLayout(requireContext());
		buttons.setOrientation(LinearLayout.HORIZONTAL);
		LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		blp.topMargin = dp(8);
		buttons.setLayoutParams(blp);

		android.widget.Button accept =
				new android.widget.Button(requireContext());
		accept.setText(R.string.grouptr_pending_accept);
		accept.setOnClickListener(v ->
				respondToInvite(p.getGroupId(), true));
		buttons.addView(accept);

		android.widget.Button decline =
				new android.widget.Button(requireContext());
		decline.setText(R.string.grouptr_pending_decline);
		decline.setOnClickListener(v ->
				respondToInvite(p.getGroupId(), false));
		buttons.addView(decline);

		container.addView(buttons);
		return container;
	}

	private void respondToInvite(byte[] groupId, boolean accept) {
		ioExecutor.execute(() -> {
			try {
				if (accept) {
					groupTrManager.acceptInvite(groupId);
				} else {
					groupTrManager.declineInvite(groupId);
				}
			} catch (DbException ex) {
				runOnUiThreadUnlessDestroyed(() -> Toast.makeText(
						requireContext(), R.string.grouptr_error_respond,
						Toast.LENGTH_SHORT).show());
			}
			runOnUiThreadUnlessDestroyed(this::loadGroups);
		});
	}

	private int dp(int value) {
		return (int) (value
				* getResources().getDisplayMetrics().density);
	}

	private void showDeleteDialog(GroupTrState s) {
		String name = s.getName().isEmpty()
				? getString(R.string.grouptr_unnamed_group) : s.getName();
		int msgRes = s.isDissolved()
				? R.string.grouptr_remove_local_dissolved_msg
				: R.string.grouptr_remove_local_active_msg;
		new androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
					runOnUiThreadUnlessDestroyed(() -> android.widget.Toast
							.makeText(requireContext(),
									R.string.grouptr_error_save,
									android.widget.Toast.LENGTH_SHORT).show());
				}
			}
		});
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}
}
