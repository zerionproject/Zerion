package com.professor.zerion.android.privategroup.invitation;

import android.view.View;

import com.professor.zerion.R;
import com.professor.zerion.android.sharing.InvitationAdapter.InvitationClickListener;
import com.professor.zerion.android.sharing.InvitationViewHolder;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationItem;

import javax.annotation.Nullable;

import static com.professor.zerion.android.util.UiUtils.getContactDisplayName;

class GroupInvitationViewHolder
		extends InvitationViewHolder<GroupInvitationItem> {

	GroupInvitationViewHolder(View v) {
		super(v);
	}

	@Override
	public void onBind(@Nullable GroupInvitationItem item,
			InvitationClickListener<GroupInvitationItem> listener) {
		super.onBind(item, listener);
		if (item == null) return;

		sharedBy.setText(
				sharedBy.getContext().getString(R.string.groups_created_by,
						getContactDisplayName(item.getCreator())));
	}

}