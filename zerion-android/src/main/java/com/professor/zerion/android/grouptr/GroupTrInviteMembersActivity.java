package com.professor.zerion.android.grouptr;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.util.StringUtils;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;

public class GroupTrInviteMembersActivity extends ZerionActivity {

	private static final String EXTRA_GROUP_ID = "groupId";

	@Inject
	GroupTrManager groupTrManager;
	@Inject
	ContactManager contactManager;
	@Inject
	@IoExecutor
	Executor ioExecutor;

	private byte[] groupId;
	private LinearLayout listContainer;
	private FloatingActionButton confirmFab;
	private final Set<byte[]> existingPubKeys = new HashSet<>();
	private final List<Contact> selected = new ArrayList<>();

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
		} catch (Exception e) {
			finish();
			return;
		}

		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundResource(R.drawable.bg_zerion_gradient);
		root.setLayoutParams(new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		root.setFitsSystemWindows(true);

		AppBarLayout appBar = new AppBarLayout(this);
		appBar.setBackgroundResource(R.drawable.bg_toolbar_gradient);
		appBar.setElevation(0);
		MaterialToolbar toolbar = new MaterialToolbar(this);
		toolbar.setTitle(R.string.grouptr_invite_members_title);
		toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
		toolbar.setNavigationIconTint(getResources().getColor(
				R.color.zerion_primary_accent));
		toolbar.setTitleTextColor(getResources().getColor(
				R.color.zerion_text_primary));
		toolbar.setNavigationOnClickListener(v -> finish());
		appBar.addView(toolbar, new AppBarLayout.LayoutParams(
				AppBarLayout.LayoutParams.MATCH_PARENT,
				(int) getResources().getDimension(
						androidx.appcompat.R.dimen.abc_action_bar_default_height_material)));
		root.addView(appBar);

		android.widget.FrameLayout content = new android.widget.FrameLayout(this);
		content.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

		NestedScrollView scroll = new NestedScrollView(this);
		listContainer = new LinearLayout(this);
		listContainer.setOrientation(LinearLayout.VERTICAL);
		listContainer.setPadding(0, dp(8), 0, dp(96));
		scroll.addView(listContainer, new NestedScrollView.LayoutParams(
				NestedScrollView.LayoutParams.MATCH_PARENT,
				NestedScrollView.LayoutParams.WRAP_CONTENT));
		content.addView(scroll, new android.widget.FrameLayout.LayoutParams(
				android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
				android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

		confirmFab = new FloatingActionButton(this);
		confirmFab.setImageResource(R.drawable.ic_check_white);
		confirmFab.setBackgroundTintList(android.content.res.ColorStateList
				.valueOf(getResources().getColor(R.color.zerion_primary_accent)));
		confirmFab.setContentDescription(getString(R.string.grouptr_invite_confirm));
		confirmFab.setOnClickListener(v -> onConfirm());
		confirmFab.hide();
		android.widget.FrameLayout.LayoutParams fabLp =
				new android.widget.FrameLayout.LayoutParams(
						android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
						android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
		fabLp.gravity = Gravity.BOTTOM | Gravity.END;
		fabLp.bottomMargin = dp(20);
		fabLp.rightMargin = dp(20);
		content.addView(confirmFab, fabLp);

		root.addView(content);
		setContentView(root);

		loadContacts();
	}

	private void loadContacts() {
		ioExecutor.execute(() -> {
			List<Contact> contacts;
			GroupTrState state = null;
			try {
				contacts = new ArrayList<>(contactManager.getContacts());
				state = groupTrManager.getGroup(groupId);
			} catch (DbException e) {
				contacts = new ArrayList<>();
			}
			final GroupTrState finalState = state;
			final List<Contact> finalContacts = contacts;
			runOnUiThread(() -> renderList(finalContacts, finalState));
		});
	}

	private void renderList(List<Contact> contacts, @Nullable GroupTrState s) {
		existingPubKeys.clear();
		if (s != null) {
			for (org.zerionproject.app.api.grouptr.GroupTrMember m :
					s.getMembers()) {
				existingPubKeys.add(m.getPubKey());
			}
		}
		listContainer.removeAllViews();
		for (Contact c : contacts) {
			byte[] pk = c.getAuthor().getPublicKey().getEncoded();
			if (containsKey(existingPubKeys, pk)) continue;
			listContainer.addView(buildRow(c));
		}
	}

	private boolean containsKey(Set<byte[]> set, byte[] key) {
		for (byte[] k : set) {
			if (java.util.Arrays.equals(k, key)) return true;
		}
		return false;
	}

	private View buildRow(Contact c) {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(20), dp(14), dp(20), dp(14));
		row.setBackgroundResource(android.R.drawable.list_selector_background);
		row.setClickable(true);
		row.setFocusable(true);

		TextView avatar = new TextView(this);
		String name = nameFor(c);
		avatar.setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
		avatar.setGravity(Gravity.CENTER);
		avatar.setTextColor(0xFFFFFFFF);
		avatar.setTextSize(16);
		avatar.setTypeface(avatar.getTypeface(), Typeface.BOLD);
		android.graphics.drawable.GradientDrawable bg =
				new android.graphics.drawable.GradientDrawable();
		bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
		bg.setColor(getResources().getColor(R.color.zerion_primary_accent));
		avatar.setBackground(bg);
		LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
				dp(40), dp(40));
		row.addView(avatar, alp);

		TextView nameView = new TextView(this);
		nameView.setText(name);
		nameView.setTextColor(getResources().getColor(
				R.color.zerion_text_primary));
		nameView.setTextSize(16);
		LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
				0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		nlp.leftMargin = dp(14);
		row.addView(nameView, nlp);

		CheckBox cb = new CheckBox(this);
		cb.setButtonTintList(android.content.res.ColorStateList.valueOf(
				getResources().getColor(R.color.zerion_primary_accent)));
		cb.setOnCheckedChangeListener((b, checked) -> {
			if (checked) {
				if (!selected.contains(c)) selected.add(c);
			} else {
				selected.remove(c);
			}
			if (selected.isEmpty()) confirmFab.hide();
			else confirmFab.show();
		});
		row.addView(cb);

		row.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
		return row;
	}

	private String nameFor(Contact c) {
		String alias = c.getAlias();
		if (alias != null && !alias.isEmpty()) return alias;
		return c.getAuthor().getName();
	}

	private void onConfirm() {
		if (selected.isEmpty()) return;
		confirmFab.setEnabled(false);
		final List<Contact> snapshot = new ArrayList<>(selected);
		ioExecutor.execute(() -> {
			int ok = 0;
			final StringBuilder failures = new StringBuilder();
			for (Contact c : snapshot) {
				try {
					byte[] pk = c.getAuthor().getPublicKey().getEncoded();
					groupTrManager.inviteContactToGroup(groupId, c.getId(),
							pk, nameFor(c));
					ok++;
				} catch (Exception e) {
					if (failures.length() > 0) failures.append(", ");
					failures.append(nameFor(c)).append(": ")
							.append(e.getClass().getSimpleName());
					String msg = e.getMessage();
					if (msg != null && !msg.isEmpty()) {
						failures.append(" (").append(msg).append(")");
					}
				}
			}
			final int okFinal = ok;
			final boolean hasFailures = failures.length() > 0;
			final String failureSummary = failures.toString();
			runOnUiThread(() -> {
				if (!hasFailures) {
					Toast.makeText(this,
							getResources().getQuantityString(
									R.plurals.grouptr_invite_sent,
									okFinal, okFinal),
							Toast.LENGTH_SHORT).show();
					Intent i = GroupTrConversationActivity.intent(this, groupId);
					i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
					startActivity(i);
					finish();
				} else {
					new androidx.appcompat.app.AlertDialog.Builder(this)
							.setTitle(R.string.grouptr_invite_partial)
							.setMessage(failureSummary)
							.setPositiveButton(android.R.string.ok, null)
							.show();
					confirmFab.setEnabled(true);
				}
			});
		});
	}

	private int dp(int v) {
		return (int) (v * getResources().getDisplayMetrics().density);
	}

	public static Intent intent(Context ctx, byte[] gid) {
		Intent i = new Intent(ctx, GroupTrInviteMembersActivity.class);
		i.putExtra(EXTRA_GROUP_ID, StringUtils.toHexString(gid));
		return i;
	}
}
