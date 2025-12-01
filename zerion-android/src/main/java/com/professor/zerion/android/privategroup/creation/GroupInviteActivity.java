package com.professor.zerion.android.privategroup.creation;

import android.content.Intent;
import android.os.Bundle;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.GroupId;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.contactselection.ContactSelectorActivity;
import com.professor.zerion.android.controller.handler.UiResultExceptionHandler;
import com.professor.zerion.android.sharing.BaseMessageFragment.MessageFragmentListener;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Collection;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_GROUP_INVITATION_TEXT_LENGTH;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class GroupInviteActivity extends ContactSelectorActivity
		implements MessageFragmentListener {

	@Inject
	CreateGroupController controller;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle bundle) {
		super.onCreate(bundle);

		Intent i = getIntent();
		byte[] g = i.getByteArrayExtra(GROUP_ID);
		if (g == null) throw new IllegalStateException("No GroupId in intent");
		groupId = new GroupId(g);

		if (bundle == null) {
			showInitialFragment(GroupInviteFragment.newInstance(groupId));
		}
	}

	@Override
	public void contactsSelected(Collection<ContactId> contacts) {
		super.contactsSelected(contacts);

		showNextFragment(new CreateGroupMessageFragment());
	}

	@Override
	public void onButtonClick(@Nullable String text) {
		if (groupId == null)
			throw new IllegalStateException("GroupId was not initialized");
		controller.sendInvitation(groupId, contacts, text,
				new UiResultExceptionHandler<Void, DbException>(this) {
					@Override
					public void onResultUi(Void result) {
						setResult(RESULT_OK);
						supportFinishAfterTransition();
					}

					@Override
					public void onExceptionUi(DbException exception) {
						setResult(RESULT_CANCELED);
						handleException(exception);
					}
				});
	}

	@Override
	public int getMaximumTextLength() {
		return MAX_GROUP_INVITATION_TEXT_LENGTH;
	}
}
