package com.professor.zerion.android.grouptr;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.util.StringUtils;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPermissions;
import org.zerionproject.app.api.grouptr.GroupTrSentInvite;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberInviteAvailability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
	private View rowMembersMayInvite;
	private MaterialSwitch membersMayInviteSwitch;
	private View actionInviteContacts;
	private TextView actionInviteContactsHint;
	private View rowIncognito;
	private TextView incognitoNameText;
	private long permissions = 0L;
	private Set<String> granted = new HashSet<>();
	private Set<String> grantable = new HashSet<>();
	@Nullable
	private String incognitoName;
	private MemberInviteAvailability memberInvites =
			MemberInviteAvailability.UNAVAILABLE;

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
		rowMembersMayInvite = findViewById(R.id.rowMembersMayInvite);
		membersMayInviteSwitch = findViewById(R.id.membersMayInviteSwitch);
		actionInviteContacts = findViewById(R.id.actionInviteContacts);
		actionInviteContactsHint = findViewById(R.id.actionInviteContactsHint);
		rowIncognito = findViewById(R.id.rowIncognito);
		incognitoNameText = findViewById(R.id.incognitoNameText);

		openChatButton.setOnClickListener(v -> startActivity(
				GroupTrConversationActivity.intent(this, groupId)));

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
				List<GroupTrMember> outOfReach = s == null
						? new ArrayList<>()
						: groupTrManager.getMembersOutOfReach(groupId);
				List<GroupTrSentInvite> invites = new ArrayList<>();
				if (s != null && Arrays.equals(localPub,
						s.getCreatorPubKey())) {
					invites.addAll(groupTrManager.getSentInvites(groupId));
				}
				long perms = s == null ? 0L
						: groupTrManager.getGroupPermissions(groupId);
				MemberInviteAvailability availability = s == null
						? MemberInviteAvailability.UNAVAILABLE
						: groupTrManager.getMemberInviteAvailability(groupId);
				Set<String> grants = new HashSet<>();
				Set<String> canGrant = new HashSet<>();
				if (s != null && Arrays.equals(localPub,
						s.getCreatorPubKey())) {
					for (GroupTrMember m : s.getMembers()) {
						if (Arrays.equals(m.getPubKey(), localPub)) continue;
						String hex = StringUtils.toHexString(m.getPubKey());
						if (groupTrManager.hasInviterGrant(groupId,
								m.getPubKey())) {
							grants.add(hex);
						}
						if (groupTrManager.canReceiveInviterGrant(groupId,
								m.getPubKey())) {
							canGrant.add(hex);
						}
					}
				}
				String incognito = s == null ? null
						: groupTrManager.getIncognitoName(groupId);
				runOnUiThread(() -> {
					if (s == null) {
						finish();
						return;
					}
					this.outOfReach = outOfReach;
					this.sentInvites = invites;
					this.permissions = perms;
					this.granted = grants;
					this.grantable = canGrant;
					this.incognitoName = incognito;
					this.memberInvites = availability == null
							? MemberInviteAvailability.UNAVAILABLE
							: availability;
					bind(s);
				});
			} catch (DbException ex) {
				runOnUiThread(() -> toast(R.string.grouptr_error_load));
			}
		});
	}

	private List<GroupTrMember> outOfReach = new ArrayList<>();
	private List<GroupTrSentInvite> sentInvites = new ArrayList<>();

	private boolean outOfReach(GroupTrMember m) {
		for (GroupTrMember o : outOfReach) {
			if (Arrays.equals(o.getPubKey(), m.getPubKey())) return true;
		}
		return false;
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
			MaterialSwitch mayAdd = row.findViewById(R.id.memberMayAddSwitch);

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
			}
			if (!self && outOfReach(m)) {
				String reach = getString(R.string.grouptr_member_out_of_reach);
				role = role == null ? reach : role + " \u00b7 " + reach;
			}
			String hex = StringUtils.toHexString(m.getPubKey());
			boolean grantShown = isCreator && !memberIsCreator && !self
					&& !dissolved;
			boolean holdsGrant = granted.contains(hex);
			boolean mayGrant = grantable.contains(hex);
			if (grantShown && !mayGrant && !holdsGrant && !outOfReach(m)) {
				String older = getString(
						R.string.grouptr_member_invite_contact_outdated);
				role = role == null ? older : role + " \u00b7 " + older;
			}
			mayAdd.setOnCheckedChangeListener(null);
			mayAdd.setVisibility(grantShown ? View.VISIBLE : View.GONE);
			mayAdd.setChecked(holdsGrant);
			mayAdd.setEnabled(mayGrant || holdsGrant);
			final byte[] memberKey = m.getPubKey();
			mayAdd.setOnCheckedChangeListener((button, checked) ->
					setInviterGrant(memberKey, checked));
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
		bindSentInvites(s, isCreator, dissolved, inf);
	}

	private void bindSentInvites(GroupTrState s, boolean isCreator,
			boolean dissolved, LayoutInflater inf) {
		if (!isCreator || dissolved) return;
		for (GroupTrSentInvite invite : sentInvites) {
			View row = inf.inflate(R.layout.list_item_grouptr_admin_member,
					membersContainer, false);
			TextView avatar = row.findViewById(R.id.memberAvatar);
			TextView nameView = row.findViewById(R.id.memberName);
			TextView roleView = row.findViewById(R.id.memberRole);
			MaterialButton menu = row.findViewById(R.id.memberMenuButton);
			String name = invite.getContactName().isEmpty() ? "?"
					: invite.getContactName();
			avatar.setText(name.substring(0, 1).toUpperCase());
			nameView.setText(name);
			roleView.setText(R.string.grouptr_member_invited);
			roleView.setVisibility(View.VISIBLE);
			menu.setVisibility(View.VISIBLE);
			menu.setOnClickListener(v -> confirmRevoke(s, invite));
			membersContainer.addView(row);
		}
	}

	private void confirmRevoke(GroupTrState s, GroupTrSentInvite invite) {
		new SecureAlertDialogBuilder(this)
				.setMessage(getString(R.string.grouptr_confirm_revoke,
						invite.getContactName()))
				.setPositiveButton(R.string.grouptr_revoke_invite, (d, w) ->
						ioExecutor.execute(() -> {
							try {
								groupTrManager.revokeInvite(s.getGroupId(),
										invite.getContactId());
								runOnUiThread(this::render);
							} catch (DbException ex) {
								runOnUiThread(() ->
										toast(R.string.grouptr_error_save));
							}
						}))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void setInviterGrant(byte[] memberKey, boolean allowed) {
		ioExecutor.execute(() -> {
			try {
				groupTrManager.setInviterGrant(groupId, memberKey, allowed);
				runOnUiThread(this::render);
			} catch (DbException ex) {
				runOnUiThread(() -> {
					toast(R.string.grouptr_error_save);
					render();
				});
			}
		});
	}

	private void bindIncognito() {
		String name = incognitoName;
		if (name == null) {
			rowIncognito.setVisibility(View.GONE);
			return;
		}
		rowIncognito.setVisibility(View.VISIBLE);
		incognitoNameText.setText(getString(R.string.grouptr_incognito_name,
				name));
		rowIncognito.setOnClickListener(v ->
				GroupTrIncognitoPicker.showIncognitoInfo(this, name));
	}

	private void bindActions(GroupTrState s, boolean isCreator,
			boolean dissolved) {
		bindMemberInvites(isCreator, dissolved);
		bindIncognito();
		if (dissolved) {
			actionAddMember.setVisibility(View.GONE);
			actionTtl.setVisibility(View.GONE);
			return;
		}
		actionAddMember.setVisibility(isCreator ? View.VISIBLE : View.GONE);
		actionAddMember.setOnClickListener(v -> showAddMemberDialog(s));
		actionTtl.setVisibility(View.VISIBLE);
		actionTtl.setOnClickListener(v -> {
			if (isCreator) {
				showTtlDialog();
			} else {
				new SecureAlertDialogBuilder(this)
						.setTitle(R.string.grouptr_default_ttl_set)
						.setMessage(getString(
								R.string.grouptr_ttl_set_by_creator,
								formatTtl(s.getDefaultAutoDeleteTimerMs())))
						.setPositiveButton(android.R.string.ok, null)
						.show();
			}
		});
	}

	private void bindMemberInvites(boolean isCreator, boolean dissolved) {
		rowMembersMayInvite.setVisibility(isCreator && !dissolved
				? View.VISIBLE : View.GONE);
		membersMayInviteSwitch.setOnCheckedChangeListener(null);
		membersMayInviteSwitch.setChecked(
				(permissions & GroupTrPermissions.MEMBERS_MAY_INVITE) != 0L);
		membersMayInviteSwitch.setOnCheckedChangeListener(
				(button, checked) -> setMembersMayInvite(checked));
		boolean allowed = memberInvites == MemberInviteAvailability.ALLOWED;
		int hint = 0;
		if (memberInvites == MemberInviteAvailability.NOT_ENABLED) {
			hint = R.string.grouptr_member_invite_creator_only;
		} else if (memberInvites
				== MemberInviteAvailability.CREATOR_NOT_A_CONTACT) {
			hint = R.string.grouptr_member_invite_needs_creator_contact;
		} else if (memberInvites
				== MemberInviteAvailability.CREATOR_ON_OLDER_VERSION) {
			hint = R.string.grouptr_member_invite_creator_outdated;
		}
		boolean shown = !isCreator && !dissolved && (allowed || hint != 0);
		actionInviteContacts.setVisibility(shown ? View.VISIBLE : View.GONE);
		actionInviteContacts.setEnabled(allowed);
		if (hint != 0) {
			actionInviteContactsHint.setText(hint);
			actionInviteContactsHint.setVisibility(View.VISIBLE);
		} else {
			actionInviteContactsHint.setVisibility(View.GONE);
		}
		actionInviteContacts.setOnClickListener(v -> {
			if (memberInvites == MemberInviteAvailability.ALLOWED) {
				startActivity(GroupTrInviteMembersActivity
						.memberIntent(this, groupId));
			}
		});
	}

	private void setMembersMayInvite(boolean allowed) {
		ioExecutor.execute(() -> {
			try {
				groupTrManager.setMembersMayInvite(groupId, allowed);
				runOnUiThread(this::render);
			} catch (DbException ex) {
				runOnUiThread(() -> {
					toast(R.string.grouptr_error_save);
					render();
				});
			}
		});
	}

	private void bindDanger(GroupTrState s, boolean isCreator,
			boolean dissolved) {
		dangerContainer.removeAllViews();
		if (dissolved) {
			TextView msg = new TextView(this);
			msg.setText(R.string.grouptr_detail_status_dissolved);
			msg.setTextColor(getResources().getColor(
					R.color.zerion_destructive_text));
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
					R.color.zerion_destructive_text));
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
					R.color.zerion_destructive_text));
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
		return GroupTrTimerLabels.label(this, ms);
	}

	private void showMemberMenu(GroupTrState s, GroupTrMember m) {
		String removeAction =
				getString(R.string.grouptr_member_action_remove);
		new SecureAlertDialogBuilder(this)
				.setTitle(m.getName())
				.setItems(new String[]{removeAction},
						(d, which) -> confirmRemove(s, m))
				.show();
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
		if (isFinishing() || isDestroyed()) return;
		if (candidates.isEmpty()) {
			toast(R.string.grouptr_no_candidates);
			return;
		}
		String[] names = new String[candidates.size()];
		for (int i = 0; i < candidates.size(); i++) {
			names[i] = candidates.get(i).getAuthor().getName();
		}
		new SecureAlertDialogBuilder(this)
				.setTitle(R.string.grouptr_add_member)
				.setItems(names, (d, which) -> {
					Contact picked = candidates.get(which);
					org.zerionproject.core.api.contact.ContactId pickedId =
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
		new SecureAlertDialogBuilder(this)
				.setTitle(R.string.grouptr_default_ttl_set)
				.setItems(labels, (d, which) -> {
					long v = values[which];
					ioExecutor.execute(() -> {
						try {
							groupTrManager.setGroupAutoDeleteTimer(
									groupId, v);
							int older = groupTrManager
									.countMembersOnOlderVersion(groupId);
							runOnUiThread(() -> {
								toast(R.string.grouptr_default_ttl_saved);
								if (older > 0) {
									Toast.makeText(this, getResources()
											.getQuantityString(
													R.plurals.grouptr_ttl_older_members,
													older, older),
											Toast.LENGTH_LONG).show();
								}
								render();
							});
						} catch (DbException ex) {
							runOnUiThread(() -> toast(
									R.string.grouptr_error_save));
						}
					});
				})
				.show();
	}

	private void confirmRemove(GroupTrState s, GroupTrMember m) {
		new SecureAlertDialogBuilder(this)
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
		new SecureAlertDialogBuilder(this)
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
		new SecureAlertDialogBuilder(this)
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
