package org.briarproject.briar.api.grouptr;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import javax.annotation.Nullable;

@NotNullByDefault
public interface GroupTrManager {

	@Nullable
	GroupTrState getGroup(byte[] groupId) throws DbException;

	Collection<GroupTrState> getGroups() throws DbException;

	GroupTrState createGroup(String name) throws DbException;

	boolean isCreator(byte[] groupId, byte[] pubKey) throws DbException;

	boolean isMember(byte[] groupId, byte[] pubKey) throws DbException;

	long getEpoch(byte[] groupId) throws DbException;

	boolean isDissolved(byte[] groupId) throws DbException;
}
