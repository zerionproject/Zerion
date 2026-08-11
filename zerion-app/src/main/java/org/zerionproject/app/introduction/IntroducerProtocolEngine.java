package org.zerionproject.app.introduction;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.client.MessageTracker;
import org.zerionproject.app.api.client.ProtocolStateException;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.identity.AuthorManager;
import org.zerionproject.app.api.introduction.event.IntroductionAbortedEvent;
import org.zerionproject.app.introduction.IntroducerSession.Introducee;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static java.lang.Math.max;
import static org.zerionproject.app.introduction.IntroducerState.AWAIT_ACTIVATES;
import static org.zerionproject.app.introduction.IntroducerState.AWAIT_ACTIVATE_A;
import static org.zerionproject.app.introduction.IntroducerState.AWAIT_ACTIVATE_B;
import static org.zerionproject.app.introduction.IntroducerState.AWAIT_AUTHS;
import static org.zerionproject.app.introduction.IntroducerState.AWAIT_AUTH_A;
import static org.zerionproject.app.introduction.IntroducerState.AWAIT_AUTH_B;
import static org.zerionproject.app.introduction.IntroducerState.AWAIT_RESPONSES;
import static org.zerionproject.app.introduction.IntroducerState.AWAIT_RESPONSE_A;
import static org.zerionproject.app.introduction.IntroducerState.AWAIT_RESPONSE_B;
import static org.zerionproject.app.introduction.IntroducerState.A_DECLINED;
import static org.zerionproject.app.introduction.IntroducerState.B_DECLINED;
import static org.zerionproject.app.introduction.IntroducerState.START;

@Immutable
@NotNullByDefault
class IntroducerProtocolEngine
		extends AbstractProtocolEngine<IntroducerSession> {

	@Inject
	IntroducerProtocolEngine(
			DatabaseComponent db,
			ClientHelper clientHelper,
			ContactManager contactManager,
			ContactGroupFactory contactGroupFactory,
			MessageTracker messageTracker,
			IdentityManager identityManager,
			AuthorManager authorManager,
			MessageParser messageParser,
			MessageEncoder messageEncoder,
			ClientVersioningManager clientVersioningManager,
			AutoDeleteManager autoDeleteManager,
			ConversationManager conversationManager,
			Clock clock) {
		super(db, clientHelper, contactManager, contactGroupFactory,
				messageTracker, identityManager, authorManager, messageParser,
				messageEncoder, clientVersioningManager, autoDeleteManager,
				conversationManager, clock);
	}

	@Override
	public IntroducerSession onRequestAction(Transaction txn,
			IntroducerSession s, @Nullable String text)
			throws DbException {
		switch (s.getState()) {
			case START:
				return onLocalRequest(txn, s, text);
			case AWAIT_RESPONSES:
			case AWAIT_RESPONSE_A:
			case AWAIT_RESPONSE_B:
			case A_DECLINED:
			case B_DECLINED:
			case AWAIT_AUTHS:
			case AWAIT_AUTH_A:
			case AWAIT_AUTH_B:
			case AWAIT_ACTIVATES:
			case AWAIT_ACTIVATE_A:
			case AWAIT_ACTIVATE_B:
				throw new ProtocolStateException();
			default:
				throw new AssertionError();
		}
	}

	@Override
	public IntroducerSession onAcceptAction(Transaction txn,
			IntroducerSession s) {
		throw new UnsupportedOperationException();
	}

	@Override
	public IntroducerSession onDeclineAction(Transaction txn,
			IntroducerSession s, boolean isAutoDecline) {
		throw new UnsupportedOperationException();
	}

	IntroducerSession onIntroduceeRemoved(Transaction txn,
			Introducee remainingIntroducee, IntroducerSession session)
			throws DbException {
		IntroducerSession s = abort(txn, session, remainingIntroducee);
		return new IntroducerSession(s.getSessionId(), s.getState(),
				s.getRequestTimestamp(), s.getIntroduceeA(),
				s.getIntroduceeB());
	}

	@Override
	public IntroducerSession onRequestMessage(Transaction txn,
			IntroducerSession s, RequestMessage m) throws DbException {
		return abort(txn, s, m);
	}

	@Override
	public IntroducerSession onAcceptMessage(Transaction txn,
			IntroducerSession s, AcceptMessage m) throws DbException {
		switch (s.getState()) {
			case AWAIT_RESPONSES:
			case AWAIT_RESPONSE_A:
			case AWAIT_RESPONSE_B:
				return onRemoteAccept(txn, s, m);
			case A_DECLINED:
			case B_DECLINED:
				return onRemoteAcceptWhenDeclined(txn, s, m);
			case START:
			case AWAIT_AUTHS:
			case AWAIT_AUTH_A:
			case AWAIT_AUTH_B:
			case AWAIT_ACTIVATES:
			case AWAIT_ACTIVATE_A:
			case AWAIT_ACTIVATE_B:
				return abort(txn, s, m);
			default:
				throw new AssertionError();
		}
	}

	@Override
	public IntroducerSession onDeclineMessage(Transaction txn,
			IntroducerSession s, DeclineMessage m) throws DbException {
		switch (s.getState()) {
			case AWAIT_RESPONSES:
			case AWAIT_RESPONSE_A:
			case AWAIT_RESPONSE_B:
				return onRemoteDecline(txn, s, m);
			case A_DECLINED:
			case B_DECLINED:
				return onRemoteDeclineWhenDeclined(txn, s, m);
			case START:
			case AWAIT_AUTHS:
			case AWAIT_AUTH_A:
			case AWAIT_AUTH_B:
			case AWAIT_ACTIVATES:
			case AWAIT_ACTIVATE_A:
			case AWAIT_ACTIVATE_B:
				return abort(txn, s, m);
			default:
				throw new AssertionError();
		}
	}

	@Override
	public IntroducerSession onAuthMessage(Transaction txn, IntroducerSession s,
			AuthMessage m) throws DbException {
		switch (s.getState()) {
			case AWAIT_AUTHS:
			case AWAIT_AUTH_A:
			case AWAIT_AUTH_B:
				return onRemoteAuth(txn, s, m);
			case START:
			case AWAIT_RESPONSES:
			case AWAIT_RESPONSE_A:
			case AWAIT_RESPONSE_B:
			case A_DECLINED:
			case B_DECLINED:
			case AWAIT_ACTIVATES:
			case AWAIT_ACTIVATE_A:
			case AWAIT_ACTIVATE_B:
				return abort(txn, s, m);
			default:
				throw new AssertionError();
		}
	}

	@Override
	public IntroducerSession onActivateMessage(Transaction txn,
			IntroducerSession s, ActivateMessage m) throws DbException {
		switch (s.getState()) {
			case AWAIT_ACTIVATES:
			case AWAIT_ACTIVATE_A:
			case AWAIT_ACTIVATE_B:
				return onRemoteActivate(txn, s, m);
			case START:
			case AWAIT_RESPONSES:
			case AWAIT_RESPONSE_A:
			case AWAIT_RESPONSE_B:
			case A_DECLINED:
			case B_DECLINED:
			case AWAIT_AUTHS:
			case AWAIT_AUTH_A:
			case AWAIT_AUTH_B:
				return abort(txn, s, m);
			default:
				throw new AssertionError();
		}
	}

	@Override
	public IntroducerSession onAbortMessage(Transaction txn,
			IntroducerSession s, AbortMessage m) throws DbException {
		return onRemoteAbort(txn, s, m);
	}

	private IntroducerSession onLocalRequest(Transaction txn,
			IntroducerSession s, @Nullable String text) throws DbException {
		long timestampA =
				getTimestampForVisibleMessage(txn, s, s.getIntroduceeA());
		long timestampB =
				getTimestampForVisibleMessage(txn, s, s.getIntroduceeB());
		long localTimestamp = max(timestampA, timestampB);
		Message sentA = sendRequestMessage(txn, s.getIntroduceeA(),
				localTimestamp, s.getIntroduceeB().author, text);
		Message sentB = sendRequestMessage(txn, s.getIntroduceeB(),
				localTimestamp, s.getIntroduceeA().author, text);
		conversationManager.trackOutgoingMessage(txn, sentA);
		conversationManager.trackOutgoingMessage(txn, sentB);
		Introducee introduceeA = new Introducee(s.getIntroduceeA(), sentA);
		Introducee introduceeB = new Introducee(s.getIntroduceeB(), sentB);
		return new IntroducerSession(s.getSessionId(), AWAIT_RESPONSES,
				localTimestamp, introduceeA, introduceeB);
	}

	private IntroducerSession onRemoteAccept(Transaction txn,
			IntroducerSession s, AcceptMessage m) throws DbException {
		if (m.getTimestamp() <= s.getRequestTimestamp())
			return abort(txn, s, m);
		if (isInvalidDependency(s, m.getGroupId(), m.getPreviousMessageId()))
			return abort(txn, s, m);
		boolean senderIsAlice = senderIsAlice(s, m);
		if (s.getState() != AWAIT_RESPONSES) {
			if (senderIsAlice && s.getState() != AWAIT_RESPONSE_A)
				return abort(txn, s, m);
			else if (!senderIsAlice && s.getState() != AWAIT_RESPONSE_B)
				return abort(txn, s, m);
		}
		markMessageVisibleInUi(txn, m.getMessageId());
		conversationManager
				.trackMessage(txn, m.getGroupId(), m.getTimestamp(), false);
		receiveAutoDeleteTimer(txn, m);
		Introducee i = getOtherIntroducee(s, m.getGroupId());
		long localTimestamp = getTimestampForInvisibleMessage(s, i);
		Message sent = sendAcceptMessage(txn, i, localTimestamp,
				m.getEphemeralPublicKey(), m.getAcceptTimestamp(),
				m.getTransportProperties(), false, m.getMlDsaPubKey());
		IntroducerState state = AWAIT_AUTHS;
		Introducee introduceeA, introduceeB;
		Author sender, other;
		if (senderIsAlice) {
			if (s.getState() == AWAIT_RESPONSES) state = AWAIT_RESPONSE_B;
			introduceeA = new Introducee(s.getIntroduceeA(), m.getMessageId());
			introduceeB = new Introducee(s.getIntroduceeB(), sent);
			sender = introduceeA.author;
			other = introduceeB.author;
		} else {
			if (s.getState() == AWAIT_RESPONSES) state = AWAIT_RESPONSE_A;
			introduceeA = new Introducee(s.getIntroduceeA(), sent);
			introduceeB = new Introducee(s.getIntroduceeB(), m.getMessageId());
			sender = introduceeB.author;
			other = introduceeA.author;
		}
		broadcastIntroductionResponseReceivedEvent(txn, s, sender.getId(),
				other, m, true);
		return new IntroducerSession(s.getSessionId(), state,
				s.getRequestTimestamp(), introduceeA, introduceeB);
	}

	private boolean senderIsAlice(IntroducerSession s,
			AbstractIntroductionMessage m) {
		return m.getGroupId().equals(s.getIntroduceeA().groupId);
	}

	private IntroducerSession onRemoteAcceptWhenDeclined(Transaction txn,
			IntroducerSession s, AcceptMessage m) throws DbException {
		if (m.getTimestamp() <= s.getRequestTimestamp())
			return abort(txn, s, m);
		if (isInvalidDependency(s, m.getGroupId(), m.getPreviousMessageId()))
			return abort(txn, s, m);
		boolean senderIsAlice = senderIsAlice(s, m);
		if (senderIsAlice && s.getState() != B_DECLINED)
			return abort(txn, s, m);
		else if (!senderIsAlice && s.getState() != A_DECLINED)
			return abort(txn, s, m);
		markMessageVisibleInUi(txn, m.getMessageId());
		conversationManager
				.trackMessage(txn, m.getGroupId(), m.getTimestamp(), false);
		receiveAutoDeleteTimer(txn, m);
		Introducee i = getOtherIntroducee(s, m.getGroupId());
		long localTimestamp = getTimestampForInvisibleMessage(s, i);
		Message sent = sendAcceptMessage(txn, i, localTimestamp,
				m.getEphemeralPublicKey(), m.getAcceptTimestamp(),
				m.getTransportProperties(), false, m.getMlDsaPubKey());

		Introducee introduceeA, introduceeB;
		Author sender, other;
		if (senderIsAlice) {
			introduceeA = new Introducee(s.getIntroduceeA(), m.getMessageId());
			introduceeB = new Introducee(s.getIntroduceeB(), sent);
			sender = introduceeA.author;
			other = introduceeB.author;
		} else {
			introduceeA = new Introducee(s.getIntroduceeA(), sent);
			introduceeB = new Introducee(s.getIntroduceeB(), m.getMessageId());
			sender = introduceeB.author;
			other = introduceeA.author;
		}
		broadcastIntroductionResponseReceivedEvent(txn, s, sender.getId(),
				other, m, false);

		return new IntroducerSession(s.getSessionId(), START,
				s.getRequestTimestamp(), introduceeA, introduceeB);
	}

	private IntroducerSession onRemoteDecline(Transaction txn,
			IntroducerSession s, DeclineMessage m) throws DbException {
		if (m.getTimestamp() <= s.getRequestTimestamp())
			return abort(txn, s, m);
		if (isInvalidDependency(s, m.getGroupId(), m.getPreviousMessageId()))
			return abort(txn, s, m);
		boolean senderIsAlice = senderIsAlice(s, m);
		if (s.getState() != AWAIT_RESPONSES) {
			if (senderIsAlice && s.getState() != AWAIT_RESPONSE_A)
				return abort(txn, s, m);
			else if (!senderIsAlice && s.getState() != AWAIT_RESPONSE_B)
				return abort(txn, s, m);
		}
		markMessageVisibleInUi(txn, m.getMessageId());
		conversationManager
				.trackMessage(txn, m.getGroupId(), m.getTimestamp(), false);
		receiveAutoDeleteTimer(txn, m);
		Introducee i = getOtherIntroducee(s, m.getGroupId());
		long localTimestamp = getTimestampForVisibleMessage(txn, s, i);
		Message sent = sendDeclineMessage(txn, i, localTimestamp, false, false);
		IntroducerState state = START;
		Introducee introduceeA, introduceeB;
		Author sender, other;
		if (senderIsAlice) {
			if (s.getState() == AWAIT_RESPONSES) state = A_DECLINED;
			introduceeA = new Introducee(s.getIntroduceeA(), m.getMessageId());
			introduceeB = new Introducee(s.getIntroduceeB(), sent);
			sender = introduceeA.author;
			other = introduceeB.author;
		} else {
			if (s.getState() == AWAIT_RESPONSES) state = B_DECLINED;
			introduceeA = new Introducee(s.getIntroduceeA(), sent);
			introduceeB = new Introducee(s.getIntroduceeB(), m.getMessageId());
			sender = introduceeB.author;
			other = introduceeA.author;
		}
		broadcastIntroductionResponseReceivedEvent(txn, s, sender.getId(),
				other, m, false);

		return new IntroducerSession(s.getSessionId(), state,
				s.getRequestTimestamp(), introduceeA, introduceeB);
	}

	private IntroducerSession onRemoteDeclineWhenDeclined(Transaction txn,
			IntroducerSession s, DeclineMessage m) throws DbException {
		if (m.getTimestamp() <= s.getRequestTimestamp())
			return abort(txn, s, m);
		if (isInvalidDependency(s, m.getGroupId(), m.getPreviousMessageId()))
			return abort(txn, s, m);
		boolean senderIsAlice = senderIsAlice(s, m);
		if (senderIsAlice && s.getState() != B_DECLINED)
			return abort(txn, s, m);
		else if (!senderIsAlice && s.getState() != A_DECLINED)
			return abort(txn, s, m);
		markMessageVisibleInUi(txn, m.getMessageId());
		conversationManager
				.trackMessage(txn, m.getGroupId(), m.getTimestamp(), false);
		receiveAutoDeleteTimer(txn, m);
		Introducee i = getOtherIntroducee(s, m.getGroupId());
		long localTimestamp = getTimestampForVisibleMessage(txn, s, i);
		Message sent = sendDeclineMessage(txn, i, localTimestamp, false, false);

		Introducee introduceeA, introduceeB;
		Author sender, other;
		if (senderIsAlice) {
			introduceeA = new Introducee(s.getIntroduceeA(), m.getMessageId());
			introduceeB = new Introducee(s.getIntroduceeB(), sent);
			sender = introduceeA.author;
			other = introduceeB.author;
		} else {
			introduceeA = new Introducee(s.getIntroduceeA(), sent);
			introduceeB = new Introducee(s.getIntroduceeB(), m.getMessageId());
			sender = introduceeB.author;
			other = introduceeA.author;
		}
		broadcastIntroductionResponseReceivedEvent(txn, s, sender.getId(),
				other, m, false);

		return new IntroducerSession(s.getSessionId(), START,
				s.getRequestTimestamp(), introduceeA, introduceeB);
	}

	private IntroducerSession onRemoteAuth(Transaction txn,
			IntroducerSession s, AuthMessage m) throws DbException {
		if (isInvalidDependency(s, m.getGroupId(), m.getPreviousMessageId()))
			return abort(txn, s, m);
		boolean senderIsAlice = senderIsAlice(s, m);
		if (s.getState() != AWAIT_AUTHS) {
			if (senderIsAlice && s.getState() != AWAIT_AUTH_A)
				return abort(txn, s, m);
			else if (!senderIsAlice && s.getState() != AWAIT_AUTH_B)
				return abort(txn, s, m);
		}
		Introducee i = getOtherIntroducee(s, m.getGroupId());
		long localTimestamp = getTimestampForInvisibleMessage(s, i);
		Message sent = sendAuthMessage(txn, i, localTimestamp, m.getMac(),
				m.getSignature());
		IntroducerState state = AWAIT_ACTIVATES;
		Introducee introduceeA, introduceeB;
		if (senderIsAlice) {
			if (s.getState() == AWAIT_AUTHS) state = AWAIT_AUTH_B;
			introduceeA = new Introducee(s.getIntroduceeA(), m.getMessageId());
			introduceeB = new Introducee(s.getIntroduceeB(), sent);
		} else {
			if (s.getState() == AWAIT_AUTHS) state = AWAIT_AUTH_A;
			introduceeA = new Introducee(s.getIntroduceeA(), sent);
			introduceeB = new Introducee(s.getIntroduceeB(), m.getMessageId());
		}
		return new IntroducerSession(s.getSessionId(), state,
				s.getRequestTimestamp(), introduceeA, introduceeB);
	}

	private IntroducerSession onRemoteActivate(Transaction txn,
			IntroducerSession s, ActivateMessage m) throws DbException {
		if (isInvalidDependency(s, m.getGroupId(), m.getPreviousMessageId()))
			return abort(txn, s, m);
		boolean senderIsAlice = senderIsAlice(s, m);
		if (s.getState() != AWAIT_ACTIVATES) {
			if (senderIsAlice && s.getState() != AWAIT_ACTIVATE_A)
				return abort(txn, s, m);
			else if (!senderIsAlice && s.getState() != AWAIT_ACTIVATE_B)
				return abort(txn, s, m);
		}
		Introducee i = getOtherIntroducee(s, m.getGroupId());
		long localTimestamp = getTimestampForInvisibleMessage(s, i);
		Message sent = sendActivateMessage(txn, i, localTimestamp, m.getMac());
		IntroducerState state = START;
		Introducee introduceeA, introduceeB;
		if (senderIsAlice) {
			if (s.getState() == AWAIT_ACTIVATES) state = AWAIT_ACTIVATE_B;
			introduceeA = new Introducee(s.getIntroduceeA(), m.getMessageId());
			introduceeB = new Introducee(s.getIntroduceeB(), sent);
		} else {
			if (s.getState() == AWAIT_ACTIVATES) state = AWAIT_ACTIVATE_A;
			introduceeA = new Introducee(s.getIntroduceeA(), sent);
			introduceeB = new Introducee(s.getIntroduceeB(), m.getMessageId());
		}
		return new IntroducerSession(s.getSessionId(), state,
				s.getRequestTimestamp(), introduceeA, introduceeB);
	}

	private IntroducerSession onRemoteAbort(Transaction txn,
			IntroducerSession s, AbortMessage m) throws DbException {
		Introducee i = getOtherIntroducee(s, m.getGroupId());
		long localTimestamp = getTimestampForInvisibleMessage(s, i);
		Message sent = sendAbortMessage(txn, i, localTimestamp);
		txn.attach(new IntroductionAbortedEvent(s.getSessionId()));
		Introducee introduceeA, introduceeB;
		if (i.equals(s.getIntroduceeA())) {
			introduceeA = new Introducee(s.getIntroduceeA(), sent);
			introduceeB = new Introducee(s.getIntroduceeB(), m.getMessageId());
		} else if (i.equals(s.getIntroduceeB())) {
			introduceeA = new Introducee(s.getIntroduceeA(), m.getMessageId());
			introduceeB = new Introducee(s.getIntroduceeB(), sent);
		} else throw new AssertionError();
		return new IntroducerSession(s.getSessionId(), START,
				s.getRequestTimestamp(), introduceeA, introduceeB);
	}

	private IntroducerSession abort(Transaction txn, IntroducerSession s,
			Introducee remainingIntroducee) throws DbException {
		txn.attach(new IntroductionAbortedEvent(s.getSessionId()));
		long localTimestamp =
				getTimestampForInvisibleMessage(s, remainingIntroducee);
		Message sent =
				sendAbortMessage(txn, remainingIntroducee, localTimestamp);
		Introducee introduceeA = s.getIntroduceeA();
		Introducee introduceeB = s.getIntroduceeB();
		if (remainingIntroducee.author.equals(introduceeA.author)) {
			introduceeA = new Introducee(introduceeA, sent);
			introduceeB = new Introducee(s.getSessionId(), introduceeB.groupId,
					introduceeB.author);
		} else if (remainingIntroducee.author.equals(introduceeB.author)) {
			introduceeA = new Introducee(s.getSessionId(), introduceeA.groupId,
					introduceeA.author);
			introduceeB = new Introducee(introduceeB, sent);
		} else {
			throw new DbException();
		}
		return new IntroducerSession(s.getSessionId(), START,
				s.getRequestTimestamp(), introduceeA, introduceeB);
	}

	private IntroducerSession abort(Transaction txn, IntroducerSession s,
			AbstractIntroductionMessage lastRemoteMessage) throws DbException {
		txn.attach(new IntroductionAbortedEvent(s.getSessionId()));
		Introducee introduceeA = s.getIntroduceeA();
		Introducee introduceeB = s.getIntroduceeB();
		if (senderIsAlice(s, lastRemoteMessage)) {
			introduceeA = new Introducee(introduceeA,
					lastRemoteMessage.getMessageId());
		} else {
			introduceeB = new Introducee(introduceeB,
					lastRemoteMessage.getMessageId());
		}
		long timestampA = getTimestampForInvisibleMessage(s, introduceeA);
		Message sentA = sendAbortMessage(txn, introduceeA, timestampA);
		long timestampB = getTimestampForInvisibleMessage(s, introduceeB);
		Message sentB = sendAbortMessage(txn, introduceeB, timestampB);
		introduceeA = new Introducee(introduceeA, sentA);
		introduceeB = new Introducee(introduceeB, sentB);
		return new IntroducerSession(s.getSessionId(), START,
				s.getRequestTimestamp(), introduceeA, introduceeB);
	}

	private Introducee getIntroducee(IntroducerSession s, GroupId g) {
		if (s.getIntroduceeA().groupId.equals(g)) return s.getIntroduceeA();
		else if (s.getIntroduceeB().groupId.equals(g))
			return s.getIntroduceeB();
		else throw new AssertionError();
	}

	private Introducee getOtherIntroducee(IntroducerSession s, GroupId g) {
		if (s.getIntroduceeA().groupId.equals(g)) return s.getIntroduceeB();
		else if (s.getIntroduceeB().groupId.equals(g))
			return s.getIntroduceeA();
		else throw new AssertionError();
	}

	private boolean isInvalidDependency(IntroducerSession session,
			GroupId contactGroupId, @Nullable MessageId dependency) {
		MessageId expected =
				getIntroducee(session, contactGroupId).lastRemoteMessageId;
		return isInvalidDependency(expected, dependency);
	}

	private long getTimestampForVisibleMessage(Transaction txn,
			IntroducerSession s, PeerSession p) throws DbException {
		long conversationTimestamp =
				getTimestampForOutgoingMessage(txn, p.getContactGroupId());
		return max(conversationTimestamp, getSessionTimestamp(s, p) + 1);
	}

	private long getTimestampForInvisibleMessage(IntroducerSession s,
			PeerSession p) {
		return max(clock.currentTimeMillis(), getSessionTimestamp(s, p) + 1);
	}

	private long getSessionTimestamp(IntroducerSession s, PeerSession p) {
		return max(p.getLocalTimestamp(), s.getRequestTimestamp());
	}
}
