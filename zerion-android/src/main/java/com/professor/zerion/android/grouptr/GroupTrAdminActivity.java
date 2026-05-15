package com.professor.zerion.android.grouptr;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.util.StringUtils;
import org.briarproject.briar.api.grouptr.GroupTrManager;
import org.briarproject.briar.api.grouptr.GroupTrMember;
import org.briarproject.briar.api.grouptr.GroupTrState;
import org.briarproject.briar.api.grouptr.MemberRole;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

public class GroupTrAdminActivity extends ZerionActivity {

	public static final String EXTRA_GROUP_ID = "groupTrId";

	@Inject
	GroupTrManager groupTrManager;
	@Inject
	ContactManager contactManager;
	@Inject
	IdentityManager identityManager;
	@Inject
	@IoExecutor
	Executor ioExecutor;

	private byte[] groupId;
	@Nullable
	private byte[] localPub;

	private TextView headerAvatar;
	private TextView headerName;
	private TextView headerSubtitle;
	private TextView membersHeader;
	private LinearLayout membersContainer;
	private LinearLayout dangerContainer;
	private View actionAddMember;
	private View actionTtl;
	private TextView actionTtlText;
	private MaterialButton openChatButton;

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
		} catch (FormatException ex) {
			finish();
			return;
		}

		setContentView(R.layout.activity_grouptr_admin);

		MaterialToolbar toolbar = findViewById(R.id.toolbar);
		toolbar.setNavigationOnClickListener(v -> finish());

		headerAvatar = findViewById(R.id.headerAvatar);
		headerName = findViewById(R.id.headerName);
		headerSubtitle = findViewById(R.id.headerSubtitle);
		membersHeader = findViewById(R.id.membersHeader);
		membersContainer = findViewById(R.id.membersContainer);
		dangerContainer = findViewById(R.id.dangerContainer);
		actionAddMember = findViewById(R.id.actionAddMember);
		actionTtl = findViewById(R.id.actionTtl);
		actionTtlText = findViewById(R.id.actionTtlText);
		openChatButton = findViewById(R.id.openChatButton);

		openChatButton.setOnClickListener(v -> startActivity(
				GroupTrConversationActivity.intent(this, groupId)));
		actionTtl.setOnClickListener(v -> showTtlDialog());

		render();
	}

	@Override
	public void onResume() {
		super.onResume();
		render();
	}

	private void render() {
		ioExecutor.execute(() -> {
			try {
				LocalAuthor la = identityManager.getLocalAuthor();
				localPub = la.getPublicKey().getEncoded();
				GroupTrState s = groupTrManager.getGroup(groupId);
				runOnUiThread(() -> {
					if (s == null) {
						finish();
						return;
					}
					bind(s);
				});
			} catch (DbException ex) {
				runOnUiThread(() -> toast(R.string.grouptr_error_load));
			}
		});
	}

	private void bind(GroupTrState s) {
		String name = s.getName().isEmpty()
				? getString(R.string.grouptr_unnamed_group) : s.getName();
		headerName.setText(name);
		headerAvatar.setText(name.isEmpty() ? "?"
				: name.substring(0, 1).toUpperCase());
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
		headerSubtitle.setText(subtitle);

		membersHeader.setText(getString(
				R.string.grouptr_admin_members_count, memberCount));

		boolean isCreator =
				localPub != null && Arrays.equals(localPub, s.getCreatorPubKey());
		boolean dissolved = s.isDissolved();

		bindMembers(s, isCreator, dissolved);
		bindActions(s, isCreator, dissolved);
		bindDanger(s, isCreator, dissolved);
		bindDetails(s);
	}

	private void bindMembers(GroupTrState s, boolean isCreator,
			boolean dissolved) {
		membersContainer.removeAllViews();
		LayoutInflater inf = LayoutInflater.from(this);
		List<GroupTrMember> members = s.getMembers();
		for (int i = 0; i < members.size(); i++) {
			GroupTrMember m = members.get(i);
			View row = inf.inflate(R.layout.list_item_grouptr_admin_member,
					membersContainer, false);
			TextView avatar = row.findViewById(R.id.memberAvatar);
			TextView nameView = row.findViewById(R.id.memberName);
			TextView roleView = row.findViewById(R.id.memberRole);
			MaterialButton menu = row.findViewById(R.id.memberMenuButton);

			String mName = m.getName().isEmpty() ? "?" : m.getName();
			boolean self = localPub != null
					&& Arrays.equals(m.getPubKey(), localPub);
			boolean memberIsCreator = Arrays.equals(m.getPubKey(),
					s.getCreatorPubKey());

			avatar.setText(mName.substring(0, 1).toUpperCase());
			nameView.setText(self ? mName + " ("
					+ getString(R.string.grouptr_member_role_you) + ")"
					: mName);

			String role = null;
			if (memberIsCreator) {
				role = getString(R.string.grouptr_member_role_creator);
			} else if (m.getRole() == MemberRole.ADMIN) {
				role = getString(R.string.grouptr_member_role_admin);
			}
			if (role != null) {
				roleView.setText(role);
				roleView.setVisibility(View.VISIBLE);
			} else {
				roleView.setVisibility(View.GONE);
			}

			boolean canManage = isCreator && !memberIsCreator && !self
					&& !dissolved;
			if (canManage) {
				menu.setVisibility(View.VISIBLE);
				final GroupTrMember target = m;
				menu.setOnClickListener(v -> showMemberMenu(s, target));
			} else {
				menu.setVisibility(View.GONE);
			}

			membersContainer.addView(row);

			if (i < members.size() - 1) {
				View divider = new View(this);
				divider.setBackgroundColor(0x1AFFFFFF);
				LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.MATCH_PARENT,
						(int) (0.5f * getResources()
								.getDisplayMetrics().density));
				lp.leftMargin = (int) (70 * getResources()
						.getDisplayMetrics().density);
				divider.setLayoutParams(lp);
				membersContainer.addView(divider);
			}
		}
	}

	private void bindActions(GroupTrState s, boolean isCreator,
			boolean dissolved) {
		if (dissolved) {
			actionAddMember.setVisibility(View.GONE);
			actionTtl.setVisibility(View.GONE);
			return;
		}
		actionAddMember.setVisibility(isCreator ? View.VISIBLE : View.GONE);
		actionAddMember.setOnClickListener(v -> showAddMemberDialog(s));
		actionTtl.setVisibility(isCreator ? View.VISIBLE : View.GONE);
	}

	private void bindDanger(GroupTrState s, boolean isCreator,
			boolean dissolved) {
		dangerContainer.removeAllViews();
		if (dissolved) {
			TextView msg = new TextView(this);
			msg.setText(R.string.grouptr_detail_status_dissolved);
			msg.setTextColor(getResources().getColor(
					R.color.zerion_red_500_new));
			msg.setTextSize(14);
			int p = (int) (20 * getResources().getDisplayMetrics().density);
			msg.setPadding(p, p / 2, p, p / 2);
			dangerContainer.addView(msg);
			return;
		}
		android.util.TypedValue tv = new android.util.TypedValue();
		getTheme().resolveAttribute(android.R.attr.selectableItemBackground,
				tv, true);
		int rippleRes = tv.resourceId;
		LayoutInflater inf = LayoutInflater.from(this);
		if (isCreator) {
			View row = inf.inflate(R.layout.list_item_grouptr_admin_member,
					dangerContainer, false);
			row.findViewById(R.id.memberAvatar).setVisibility(View.GONE);
			row.findViewById(R.id.memberMenuButton).setVisibility(View.GONE);
			TextView label = row.findViewById(R.id.memberName);
			label.setText(R.string.grouptr_dissolve);
			label.setTextColor(getResources().getColor(
					R.color.zerion_red_500_new));
			row.findViewById(R.id.memberRole).setVisibility(View.GONE);
			if (rippleRes != 0) row.setBackgroundResource(rippleRes);
			row.setOnClickListener(v -> confirmDissolve(s));
			dangerContainer.addView(row);
		} else {
			View row = inf.inflate(R.layout.list_item_grouptr_admin_member,
					dangerContainer, false);
			row.findViewById(R.id.memberAvatar).setVisibility(View.GONE);
			row.findViewById(R.id.memberMenuButton).setVisibility(View.GONE);
			TextView label = row.findViewById(R.id.memberName);
			label.setText(R.string.grouptr_leave);
			label.setTextColor(getResources().getColor(
					R.color.zerion_red_500_new));
			row.findViewById(R.id.memberRole).setVisibility(View.GONE);
			if (rippleRes != 0) row.setBackgroundResource(rippleRes);
			row.setOnClickListener(v -> confirmLeave(s));
			dangerContainer.addView(row);
		}
	}

	private void bindDetails(GroupTrState s) {
		if (actionTtlText != null) {
			actionTtlText.setText(formatTtl(s.getDefaultAutoDeleteTimerMs()));
		}
	}

	private String formatTtl(long ms) {
		if (ms <= 0L) return getString(R.string.grouptr_ttl_off_label);
		if (ms == 5L * 60_000L) return getString(R.string.grouptr_ttl_5min);
		if (ms == 60L * 60_000L) return getString(R.string.grouptr_ttl_1hr);
		if (ms == 24L * 60L * 60_000L) return getString(R.string.grouptr_ttl_1day);
		if (ms == 7L * 24L * 60L * 60_000L)
			return getString(R.string.grouptr_ttl_7days);
		if (ms == 30L * 24L * 60L * 60_000L)
			return getString(R.string.grouptr_ttl_30days);
		return getString(R.string.grouptr_ttl_off_label);
	}

	private void showMemberMenu(GroupTrState s, GroupTrMember m) {
		boolean isAdmin = m.getRole() == MemberRole.ADMIN;
		String roleAction = isAdmin
				? getString(R.string.grouptr_member_action_demote)
				: getString(R.string.grouptr_member_action_promote);
		String removeAction =
				getString(R.string.grouptr_member_action_remove);
		new AlertDialog.Builder(this)
				.setTitle(m.getName())
				.setItems(new String[]{roleAction, removeAction},
						(d, which) -> {
							if (which == 0) {
								if (isAdmin) demote(s, m);
								else promote(s, m);
							} else if (which == 1) {
								confirmRemove(s, m);
							}
						})
				.show();
	}

	private void promote(GroupTrState s, GroupTrMember m) {
		ioExecutor.execute(() -> {
			try {
				groupTrManager.promoteToAdmin(s.getGroupId(), m.getPubKey());
				runOnUiThread(this::render);
			} catch (DbException ex) {
				runOnUiThread(() -> toast(R.string.grouptr_error_save));
			}
		});
	}

	private void demote(GroupTrState s, GroupTrMember m) {
		ioExecutor.execute(() -> {
			try {
				groupTrManager.demoteToMember(s.getGroupId(), m.getPubKey());
				runOnUiThread(this::render);
			} catch (DbException ex) {
				runOnUiThread(() -> toast(R.string.grouptr_error_save));
			}
		});
	}

	private void showAddMemberDialog(GroupTrState s) {
		ioExecutor.execute(() -> {
			try {
				List<Contact> all =
						new ArrayList<>(contactManager.getContacts());
				List<Contact> candidates = new ArrayList<>();
				for (Contact c : all) {
					byte[] p = c.getAuthor().getPublicKey().getEncoded();
					if (!isMember(s, p)) candidates.add(c);
				}
				runOnUiThread(() -> showCandidatePicker(s, candidates));
			} catch (DbException ex) {
				runOnUiThread(() -> toast(R.string.grouptr_error_load));
			}
		});
	}

	private void showCandidatePicker(GroupTrState s, List<Contact> candidates) {
		if (candidates.isEmpty()) {
			toast(R.string.grouptr_no_candidates);
			return;
		}
		String[] names = new String[candidates.size()];
		for (int i = 0; i < candidates.size(); i++) {
			names[i] = candidates.get(i).getAuthor().getName();
		}
		new AlertDialog.Builder(this)
				.setTitle(R.string.grouptr_add_member)
				.setItems(names, (d, which) -> {
					Contact picked = candidates.get(which);
					org.briarproject.bramble.api.contact.ContactId pickedId =
							picked.getId();
					byte[] pub = picked.getAuthor().getPublicKey()
							.getEncoded();
					String name = picked.getAuthor().getName();
					ioExecutor.execute(() -> {
						try {
							groupTrManager.inviteContactToGroup(
									s.getGroupId(), pickedId, pub, name);
							runOnUiThread(() -> {
								toast(R.string.grouptr_invite_sent);
								render();
							});
						} catch (DbException ex) {
							runOnUiThread(() ->
									toast(R.string.grouptr_error_add));
						}
					});
				})
				.show();
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
							runOnUiThread(() -> toast(
									R.string.grouptr_default_ttl_saved));
						} catch (DbException ex) {
							runOnUiThread(() -> toast(
									R.string.grouptr_error_save));
						}
					});
				})
				.show();
	}

	private void confirmRemove(GroupTrState s, GroupTrMember m) {
		new AlertDialog.Builder(this)
				.setMessage(getString(R.string.grouptr_confirm_remove,
						m.getName()))
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					ioExecutor.execute(() -> {
						try {
							groupTrManager.removeMember(s.getGroupId(),
									m.getPubKey());
							runOnUiThread(this::render);
						} catch (DbException ex) {
							runOnUiThread(() ->
									toast(R.string.grouptr_error_remove));
						}
					});
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void confirmDissolve(GroupTrState s) {
		new AlertDialog.Builder(this)
				.setMessage(R.string.grouptr_confirm_dissolve)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					ioExecutor.execute(() -> {
						try {
							groupTrManager.dissolveGroup(s.getGroupId());
							runOnUiThread(this::render);
						} catch (DbException ex) {
							runOnUiThread(() ->
									toast(R.string.grouptr_error_dissolve));
						}
					});
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void confirmLeave(GroupTrState s) {
		new AlertDialog.Builder(this)
				.setMessage(R.string.grouptr_confirm_leave)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					ioExecutor.execute(() -> {
						try {
							groupTrManager.leaveGroup(s.getGroupId());
							runOnUiThread(this::finish);
						} catch (DbException ex) {
							runOnUiThread(() ->
									toast(R.string.grouptr_error_leave));
						}
					});
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private boolean isMember(GroupTrState s, byte[] pub) {
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), pub)) return true;
		}
		return false;
	}

	private void toast(int res) {
		Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
	}

	public static Intent intent(Context ctx, byte[] gid) {
		Intent i = new Intent(ctx, GroupTrAdminActivity.class);
		i.putExtra(EXTRA_GROUP_ID, StringUtils.toHexString(gid));
		return i;
	}
}
