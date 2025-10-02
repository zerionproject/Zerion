package com.professor.zerion.android.privategroup.memberlist;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.GroupId;
import com.professor.zerion.android.controller.DbController;
import com.professor.zerion.android.controller.handler.ResultExceptionHandler;

import java.util.Collection;

public interface GroupMemberListController extends DbController {

	void loadMembers(GroupId groupId,
			ResultExceptionHandler<Collection<MemberListItem>, DbException> handler);

}
