package com.professor.zerion.android.privategroup.creation;

import android.os.Bundle;

import org.briarproject.bramble.api.sync.GroupId;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.contactselection.ContactSelectorController;
import com.professor.zerion.android.contactselection.ContactSelectorFragment;
import com.professor.zerion.android.contactselection.SelectableContactItem;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.Nullable;

import static com.professor.zerion.android.activity.BriarActivity.GROUP_ID;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class GroupInviteFragment extends ContactSelectorFragment {

	public static final String TAG = GroupInviteFragment.class.getName();

	@Inject
	CreateGroupController controller;

	public static GroupInviteFragment newInstance(GroupId groupId) {
		Bundle args = new Bundle();
		args.putByteArray(GROUP_ID, groupId.getBytes());
		GroupInviteFragment fragment = new GroupInviteFragment();
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requireActivity().setTitle(R.string.groups_invite_members);
	}

	@Override
	protected ContactSelectorController<SelectableContactItem> getController() {
		return controller;
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

}
