package com.professor.zerion.android.privategroup.creation;

import com.professor.zerion.R;
import com.professor.zerion.android.sharing.BaseMessageFragment;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.annotation.StringRes;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class CreateGroupMessageFragment extends BaseMessageFragment {

	private final static String TAG =
			CreateGroupMessageFragment.class.getName();

	@Override
	@StringRes
	protected int getButtonText() {
		return R.string.groups_create_group_invitation_button;
	}

	@Override
	@StringRes
	protected int getHintText() {
		return R.string.forum_share_message;
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

}
