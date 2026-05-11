package com.professor.zerion.android.grouptr;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.briar.api.grouptr.GroupTrManager;
import org.briarproject.briar.api.grouptr.GroupTrMember;
import org.briarproject.briar.api.grouptr.GroupTrState;
import org.briarproject.bramble.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

public class GroupTrAdminActivity extends ZerionActivity {

	@Inject
	GroupTrManager groupTrManager;
	@Inject
	ContactManager contactManager;
	@Inject
	IdentityManager identityManager;
	@Inject
	@IoExecutor
	Executor ioExecutor;

	private LinearLayout root;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(32, 32, 32, 32);
		setContentView(root);
		setTitle(R.string.grouptr_title);
		render();
	}

	private void render() {
		root.removeAllViews();
		Button create = new Button(this);
		create.setText(R.string.grouptr_create);
		create.setOnClickListener(v -> showCreateDialog());
		root.addView(create);
		ioExecutor.execute(() -> {
			try {
				Collection<GroupTrState> groups = groupTrManager.getGroups();
				LocalAuthor la = identityManager.getLocalAuthor();
				byte[] localPub = la.getPublicKey().getEncoded();
				runOnUiThread(() -> renderGroups(groups, localPub));
			} catch (DbException ex) {
				runOnUiThread(() -> toast(R.string.grouptr_error_load));
			}
		});
	}

	private void renderGroups(Collection<GroupTrState> groups,
			byte[] localPub) {
		if (groups.isEmpty()) {
			TextView empty = new TextView(this);
			empty.setText(R.string.grouptr_empty);
			empty.setPadding(0, 24, 0, 0);
			root.addView(empty);
			return;
		}
		for (GroupTrState s : groups) {
			root.addView(buildGroupSection(s, localPub));
		}
	}

	private View buildGroupSection(GroupTrState s, byte[] localPub) {
		LinearLayout section = new LinearLayout(this);
		section.setOrientation(LinearLayout.VERTICAL);
		section.setPadding(0, 24, 0, 24);
		TextView title = new TextView(this);
		title.setText(s.getName() + (s.isDissolved()
				? " " + getString(R.string.grouptr_dissolved_suffix) : ""));
		title.setTextSize(18);
		section.addView(title);
		TextView meta = new TextView(this);
		meta.setText(getString(R.string.grouptr_meta,
				s.getEpoch(), s.getMembers().size()));
		section.addView(meta);
		boolean isCreator = Arrays.equals(localPub, s.getCreatorPubKey());
		for (GroupTrMember m : s.getMembers()) {
			LinearLayout row = new LinearLayout(this);
			row.setOrientation(LinearLayout.HORIZONTAL);
			TextView name = new TextView(this);
			boolean self = Arrays.equals(m.getPubKey(), localPub);
			boolean creator = Arrays.equals(m.getPubKey(),
					s.getCreatorPubKey());
			String label = m.getName();
			if (creator) label += " " + getString(R.string.grouptr_creator_tag);
			if (self) label += " " + getString(R.string.grouptr_you_tag);
			name.setText(label);
			LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
					0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
			row.addView(name, nameLp);
			if (isCreator && !creator) {
				Button rm = new Button(this);
				rm.setText(R.string.grouptr_remove);
				rm.setOnClickListener(v -> confirmRemove(s, m));
				row.addView(rm);
			}
			section.addView(row);
		}
		if (!s.isDissolved()) {
			LinearLayout actions = new LinearLayout(this);
			actions.setOrientation(LinearLayout.HORIZONTAL);
			if (isCreator) {
				Button addBtn = new Button(this);
				addBtn.setText(R.string.grouptr_add_member);
				addBtn.setOnClickListener(v -> showAddMemberDialog(s));
				actions.addView(addBtn);
				Button diss = new Button(this);
				diss.setText(R.string.grouptr_dissolve);
				diss.setOnClickListener(v -> confirmDissolve(s));
				actions.addView(diss);
			} else if (isMember(s, localPub)) {
				Button leave = new Button(this);
				leave.setText(R.string.grouptr_leave);
				leave.setOnClickListener(v -> confirmLeave(s));
				actions.addView(leave);
			}
			section.addView(actions);
		}
		return section;
	}

	private boolean isMember(GroupTrState s, byte[] pub) {
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), pub)) return true;
		}
		return false;
	}

	private void showCreateDialog() {
		final EditText input = new EditText(this);
		input.setInputType(InputType.TYPE_CLASS_TEXT);
		new AlertDialog.Builder(this)
				.setTitle(R.string.grouptr_create)
				.setView(input)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					String name = input.getText().toString().trim();
					if (name.isEmpty()) return;
					ioExecutor.execute(() -> {
						try {
							groupTrManager.createGroup(name);
							runOnUiThread(this::render);
						} catch (DbException ex) {
							runOnUiThread(() ->
									toast(R.string.grouptr_error_create));
						}
					});
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void showAddMemberDialog(GroupTrState s) {
		ioExecutor.execute(() -> {
			try {
				List<Contact> contacts =
						new ArrayList<>(contactManager.getContacts());
				List<Contact> candidates = new ArrayList<>();
				for (Contact c : contacts) {
					byte[] p = c.getAuthor().getPublicKey().getEncoded();
					if (!isMember(s, p)) candidates.add(c);
				}
				runOnUiThread(() -> showCandidatePicker(s, candidates));
			} catch (DbException ex) {
				runOnUiThread(() -> toast(R.string.grouptr_error_load));
			}
		});
	}

	private void showCandidatePicker(GroupTrState s,
			List<Contact> candidates) {
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
					byte[] pub =
							picked.getAuthor().getPublicKey().getEncoded();
					String name = picked.getAuthor().getName();
					ioExecutor.execute(() -> {
						try {
							groupTrManager.addMember(s.getGroupId(),
									pub, name);
							runOnUiThread(this::render);
						} catch (DbException ex) {
							runOnUiThread(() ->
									toast(R.string.grouptr_error_add));
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
							runOnUiThread(this::render);
						} catch (DbException ex) {
							runOnUiThread(() ->
									toast(R.string.grouptr_error_leave));
						}
					});
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void toast(int res) {
		Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
	}

	@SuppressWarnings("unused")
	private static String hex(byte[] b) {
		return StringUtils.toHexString(b);
	}
}
