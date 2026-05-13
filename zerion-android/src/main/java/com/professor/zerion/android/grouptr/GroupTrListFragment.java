package com.professor.zerion.android.grouptr;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.briar.api.grouptr.GroupTrManager;
import org.briarproject.briar.api.grouptr.GroupTrState;
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
public class GroupTrListFragment extends BaseFragment {

	public static final String TAG = GroupTrListFragment.class.getName();

	public static GroupTrListFragment newInstance() {
		return new GroupTrListFragment();
	}

	@Inject
	GroupTrManager groupTrManager;
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

		FloatingActionButton fab = new FloatingActionButton(requireContext());
		fab.setImageResource(android.R.drawable.ic_input_add);
		fab.setContentDescription(getString(R.string.grouptr_create));
		fab.setOnClickListener(v -> showCreateDialog());
		FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.WRAP_CONTENT,
				FrameLayout.LayoutParams.WRAP_CONTENT);
		fabLp.gravity = Gravity.BOTTOM | Gravity.END;
		fabLp.bottomMargin = dp(24);
		fabLp.rightMargin = dp(24);
		root.addView(fab, fabLp);

		return root;
	}

	@Override
	public void onStart() {
		super.onStart();
		loadGroups();
	}

	private void loadGroups() {
		ioExecutor.execute(() -> {
			List<GroupTrState> groups;
			try {
				Collection<GroupTrState> c = groupTrManager.getGroups();
				groups = new ArrayList<>(c);
			} catch (DbException ex) {
				groups = Collections.emptyList();
			}
			List<GroupTrState> finalGroups = groups;
			requireActivity().runOnUiThread(() -> renderGroups(finalGroups));
		});
	}

	private void renderGroups(List<GroupTrState> groups) {
		listContainer.removeAllViews();
		if (groups.isEmpty()) {
			emptyView.setVisibility(View.VISIBLE);
			return;
		}
		emptyView.setVisibility(View.GONE);
		for (GroupTrState s : groups) {
			listContainer.addView(buildRow(s));
		}
	}

	private View buildRow(GroupTrState s) {
		LinearLayout row = new LinearLayout(requireContext());
		row.setOrientation(LinearLayout.VERTICAL);
		row.setPadding(dp(20), dp(16), dp(20), dp(16));
		row.setBackgroundResource(
				android.R.drawable.list_selector_background);
		row.setClickable(true);
		row.setFocusable(true);
		row.setOnClickListener(v ->
				startActivity(GroupTrConversationActivity.intent(
						requireContext(), s.getGroupId())));

		TextView name = new TextView(requireContext());
		name.setText(s.getName().isEmpty()
				? getString(R.string.grouptr_unnamed_group)
				: s.getName());
		name.setTextSize(17);
		name.setTypeface(name.getTypeface(),
				android.graphics.Typeface.BOLD);
		row.addView(name);

		TextView meta = new TextView(requireContext());
		int memberCount = s.getMembers().size();
		String metaText = getResources().getQuantityString(
				R.plurals.grouptr_member_count, memberCount, memberCount);
		if (s.isDissolved()) {
			metaText = metaText + " · "
					+ getString(R.string.grouptr_dissolved_suffix);
		}
		meta.setText(metaText);
		meta.setTextSize(13);
		meta.setAlpha(0.7f);
		meta.setPadding(0, dp(4), 0, 0);
		row.addView(meta);

		View divider = new View(requireContext());
		divider.setBackgroundColor(0x22FFFFFF);
		LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, 1);
		divider.setLayoutParams(dlp);

		LinearLayout wrapper = new LinearLayout(requireContext());
		wrapper.setOrientation(LinearLayout.VERTICAL);
		wrapper.addView(row);
		wrapper.addView(divider);
		return wrapper;
	}

	private void showCreateDialog() {
		com.google.android.material.textfield.TextInputLayout wrap =
				new com.google.android.material.textfield.TextInputLayout(
						requireContext());
		wrap.setHint(getString(R.string.grouptr_group_name_hint));
		wrap.setBoxBackgroundMode(com.google.android.material.textfield
				.TextInputLayout.BOX_BACKGROUND_OUTLINE);
		final com.google.android.material.textfield.TextInputEditText input =
				new com.google.android.material.textfield.TextInputEditText(
						wrap.getContext());
		input.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
		input.setMaxLines(1);
		input.setSingleLine(true);
		wrap.addView(input);
		FrameLayout pad = new FrameLayout(requireContext());
		int p = dp(20);
		pad.setPadding(p, dp(8), p, 0);
		pad.addView(wrap);
		new AlertDialog.Builder(requireContext())
				.setTitle(R.string.grouptr_create)
				.setView(pad)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					String name = input.getText() == null
							? "" : input.getText().toString().trim();
					if (name.isEmpty()) return;
					ioExecutor.execute(() -> {
						try {
							GroupTrState s = groupTrManager.createGroup(name);
							requireActivity().runOnUiThread(() -> {
								loadGroups();
								startActivity(GroupTrConversationActivity
										.intent(requireContext(),
												s.getGroupId()));
							});
						} catch (DbException ex) {
							requireActivity().runOnUiThread(() ->
									Toast.makeText(requireContext(),
													R.string.grouptr_error_create,
													Toast.LENGTH_SHORT)
											.show());
						}
					});
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
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
