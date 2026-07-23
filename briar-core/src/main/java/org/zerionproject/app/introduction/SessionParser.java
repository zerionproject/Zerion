package org.zerionproject.app.introduction;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.app.api.client.SessionId;
import org.zerionproject.app.api.introduction.Role;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
interface SessionParser {

	BdfDictionary getSessionQuery(SessionId s);

	Role getRole(BdfDictionary d) throws FormatException;

	IntroducerSession parseIntroducerSession(BdfDictionary d)
			throws FormatException;

	IntroduceeSession parseIntroduceeSession(GroupId introducerGroupId,
			BdfDictionary d) throws FormatException;

}
