package com.professor.zerion.android.privategroup.invitation;

import com.professor.zerion.android.activity.ActivityScope;

import dagger.Module;
import dagger.Provides;

@Module
public class GroupInvitationModule {

	@ActivityScope
	@Provides
	GroupInvitationController provideInvitationGroupController(
			GroupInvitationControllerImpl groupInvitationController) {
		return groupInvitationController;
	}
}
