package com.professor.zerion.android.privategroup.creation;

import android.content.Intent;
import android.os.Bundle;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.GroupId;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.BriarActivity;
import com.professor.zerion.android.controller.handler.UiResultExceptionHandler;
import com.professor.zerion.android.privategroup.conversation.GroupActivity;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class CreateGroupActivity extends BriarActivity
		implements CreateGroupListener {

	@Inject
	CreateGroupController controller;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle bundle) {
		super.onCreate(bundle);

		setContentView(R.layout.activity_fragment_container);

		if (bundle == null) {
			showInitialFragment(new CreateGroupFragment());
		}
	}

	@Override
	public void onGroupNameChosen(String name) {
		controller.createGroup(name,
				new UiResultExceptionHandler<GroupId, DbException>(this) {
					@Override
					public void onResultUi(GroupId g) {
						openNewGroup(g);
					}

					@Override
					public void onExceptionUi(DbException exception) {
						handleException(exception);
					}
				});
	}

	private void openNewGroup(GroupId g) {
		Intent i = new Intent(this, GroupActivity.class);
		i.putExtra(GROUP_ID, g.getBytes());
		startActivity(i);
		finish();
	}
}
