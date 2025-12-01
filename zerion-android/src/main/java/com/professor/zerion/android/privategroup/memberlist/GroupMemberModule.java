package com.professor.zerion.android.privategroup.memberlist;

import com.professor.zerion.android.activity.ActivityScope;

import dagger.Module;
import dagger.Provides;

@Module
public class GroupMemberModule {

	@ActivityScope
	@Provides
	GroupMemberListController provideGroupMemberListController(
			GroupMemberListControllerImpl groupMemberListController) {
		return groupMemberListController;
	}
}
