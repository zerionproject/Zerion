package com.professor.zerion.android.privategroup.conversation;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.privategroup.creation.GroupInviteActivity;
import com.professor.zerion.android.privategroup.memberlist.GroupMemberListActivity;
import com.professor.zerion.android.threaded.ThreadListActivity;
import com.professor.zerion.android.threaded.ThreadListViewModel;
import com.professor.zerion.android.widget.LinkDialogFragment;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.professor.zerion.android.activity.RequestCodes.REQUEST_GROUP_INVITE;
import static com.professor.zerion.android.util.UiUtils.observeOnce;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_GROUP_POST_TEXT_LENGTH;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class GroupActivity extends
		ThreadListActivity<GroupMessageItem, GroupMessageAdapter> {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private GroupViewModel viewModel;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(GroupViewModel.class);
	}

	@Override
	protected ThreadListViewModel<GroupMessageItem> getViewModel() {
		return viewModel;
	}

	@Override
	protected GroupMessageAdapter createAdapter() {
		return new GroupMessageAdapter(this);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);

		Toolbar toolbar = setUpCustomToolbar(false);
		toolbar.setOnClickListener(v -> {
			Intent i = new Intent(GroupActivity.this,
					GroupMemberListActivity.class);
			i.putExtra(GROUP_ID, groupId.getBytes());
			startActivity(i);
		});

		String groupName = getIntent().getStringExtra(GROUP_NAME);
		if (groupName != null) setTitle(groupName);
		observeOnce(viewModel.getPrivateGroup(), this, privateGroup ->
				setTitle(privateGroup.getName())
		);
		observeOnce(viewModel.isCreator(), this, adapter::setIsCreator);

		setGroupEnabled(false);
		viewModel.isDissolved().observe(this, dissolved -> {
			setGroupEnabled(!dissolved);
			if (dissolved && state == null) onGroupDissolved();
		});
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.group_actions, menu);

		observeOnce(viewModel.isCreator(), this, isCreator -> {
			menu.findItem(R.id.action_group_invite).setVisible(isCreator);
			menu.findItem(R.id.action_group_leave).setVisible(!isCreator);
			menu.findItem(R.id.action_group_dissolve).setVisible(isCreator);
		});
		super.onCreateOptionsMenu(menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		if (itemId == R.id.action_group_member_list) {
			Intent i = new Intent(this, GroupMemberListActivity.class);
			i.putExtra(GROUP_ID, groupId.getBytes());
			startActivity(i);
			return true;
		} else if (itemId == R.id.action_group_invite) {
			if (!requireNonNull(viewModel.isCreator().getValue()))
				throw new IllegalStateException();
			Intent i = new Intent(this, GroupInviteActivity.class);
			i.putExtra(GROUP_ID, groupId.getBytes());
			startActivityForResult(i, REQUEST_GROUP_INVITE);
			return true;
		} else if (itemId == R.id.action_group_leave) {
			if (requireNonNull(viewModel.isCreator().getValue()))
				throw new IllegalStateException();
			showLeaveGroupDialog();
			return true;
		} else if (itemId == R.id.action_group_dissolve) {
			if (!requireNonNull(viewModel.isCreator().getValue()))
				throw new IllegalStateException();
			showDissolveGroupDialog();
			return true;
		} else if (itemId == R.id.action_clear_group_chat) {
			showClearChatDialog();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void showClearChatDialog() {
		new MaterialAlertDialogBuilder(this, R.style.ZerionDialogTheme)
				.setTitle(getString(R.string.clear_chat_dialog_title))
				.setMessage(getString(R.string.clear_chat_dialog_message))
				.setNegativeButton(R.string.clear_chat, (dialog, which) -> clearGroupChat())
				.setPositiveButton(R.string.cancel, null)
				.show();
	}

	private void clearGroupChat() {
		displaySnackbar(R.string.group_clear_not_supported);
	}

	@Override
	protected void onActivityResult(int request, int result,
			@Nullable Intent data) {
		if (request == REQUEST_GROUP_INVITE && result == RESULT_OK) {
			displaySnackbar(R.string.groups_invitation_sent);
		} else super.onActivityResult(request, result, data);
	}

	@Override
	protected int getMaxTextLength() {
		return MAX_GROUP_POST_TEXT_LENGTH;
	}

	@Override
	public void onReplyClick(GroupMessageItem item) {
		Boolean isDissolved = viewModel.isDissolved().getValue();
		if (isDissolved != null && !isDissolved) super.onReplyClick(item);
	}

	@Override
	public void onLinkClick(String url){
		LinkDialogFragment f = LinkDialogFragment.newInstance(url);
		f.show(getSupportFragmentManager(), f.getUniqueTag());
	}

	private void setGroupEnabled(boolean enabled) {
		sendController.setReady(enabled);
		list.getRecyclerView().setAlpha(enabled ? 1f : 0.5f);

		if (!enabled) {
			textInput.setVisibility(GONE);
			if (textInput.isKeyboardOpen()) textInput.hideSoftKeyboard();
		} else {
			textInput.setVisibility(VISIBLE);
		}
	}

	private void showLeaveGroupDialog() {
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				this, R.style.ZerionDialogTheme);
		builder.setTitle(getString(R.string.groups_leave_dialog_title));
		builder.setMessage(getString(R.string.groups_leave_dialog_message));
		builder.setNegativeButton(R.string.dialog_button_leave,
				(d, w) -> deleteGroup());
		builder.setPositiveButton(R.string.cancel, null);
		builder.show();
	}

	private void showDissolveGroupDialog() {
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				this, R.style.ZerionDialogTheme);
		builder.setTitle(getString(R.string.groups_dissolve_dialog_title));
		builder.setMessage(getString(R.string.groups_dissolve_dialog_message));
		builder.setNegativeButton(R.string.groups_dissolve_button,
				(d, w) -> deleteGroup());
		builder.setPositiveButton(R.string.cancel, null);
		builder.show();
	}

	private void deleteGroup() {
		viewModel.deletePrivateGroup();
	}

	private void onGroupDissolved() {
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				this, R.style.ZerionDialogTheme);
		builder.setTitle(getString(R.string.groups_dissolved_dialog_title));
		builder.setMessage(getString(R.string.groups_dissolved_dialog_message));
		builder.setNeutralButton(R.string.ok, null);
		builder.show();
	}

}
