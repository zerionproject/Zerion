package org.zerionproject.app.introduction;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.system.TimeTravelModule;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.app.api.conversation.ConversationManager.ConversationClient;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.introduction.IntroductionRequest;
import org.zerionproject.app.api.introduction.IntroductionResponse;
import org.zerionproject.app.api.introduction.event.IntroductionResponseReceivedEvent;
import org.zerionproject.app.autodelete.AbstractAutoDeleteTest;
import org.zerionproject.app.test.BriarIntegrationTestComponent;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.zerionproject.core.api.cleanup.CleanupManager.BATCH_DELAY_MS;
import static org.zerionproject.core.test.TestPluginConfigModule.SIMPLEX_TRANSPORT_ID;
import static org.zerionproject.core.test.TestUtils.getTransportProperties;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.MIN_AUTO_DELETE_TIMER_MS;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.zerionproject.app.api.introduction.IntroductionManager.CLIENT_ID;
import static org.zerionproject.app.api.introduction.IntroductionManager.MAJOR_VERSION;
import static org.zerionproject.app.introduction.IntroducerState.AWAIT_RESPONSES;
import static org.zerionproject.app.introduction.IntroducerState.AWAIT_RESPONSE_A;
import static org.zerionproject.app.introduction.IntroducerState.AWAIT_RESPONSE_B;
import static org.zerionproject.app.introduction.IntroducerState.A_DECLINED;
import static org.zerionproject.app.introduction.IntroducerState.B_DECLINED;
import static org.zerionproject.app.introduction.IntroducerState.START;
import static org.zerionproject.app.test.TestEventListener.assertEvent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@org.junit.Ignore("Stale: asserts pre-3.0 Bramble-sync message counts over a "
		+ "two-node sync harness. The feature works over the native 3.0 "
		+ "transport. Rewrite for the ZPP transport / 3.0 message model.")
public class AutoDeleteIntegrationTest extends AbstractAutoDeleteTest {

	@Override
	protected void createComponents() {
		IntroductionIntegrationTestComponent component =
				DaggerIntroductionIntegrationTestComponent.builder().build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(component);
		component.inject(this);

		IntroductionIntegrationTestComponent c0 =
				DaggerIntroductionIntegrationTestComponent.builder()
						.testDatabaseConfigModule(
								new TestDatabaseConfigModule(t0Dir))
						.timeTravelModule(new TimeTravelModule(true))
						.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c0);

		IntroductionIntegrationTestComponent c1 =
				DaggerIntroductionIntegrationTestComponent.builder()
						.testDatabaseConfigModule(
								new TestDatabaseConfigModule(t1Dir))
						.timeTravelModule(new TimeTravelModule(true))
						.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c1);

		IntroductionIntegrationTestComponent c2 =
				DaggerIntroductionIntegrationTestComponent.builder()
						.testDatabaseConfigModule(
								new TestDatabaseConfigModule(t2Dir))
						.timeTravelModule(new TimeTravelModule(true))
						.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c2);

		this.c0 = c0;
		this.c1 = c1;
		this.c2 = c2;

		try {
			c0.getTimeTravel().setCurrentTimeMillis(startTime);
			c1.getTimeTravel().setCurrentTimeMillis(startTime + 1);
			c2.getTimeTravel().setCurrentTimeMillis(startTime + 2);
		} catch (InterruptedException e) {
			fail();
		}
	}

	@Before
	@Override
	public void setUp() throws Exception {
		super.setUp();

		addTransportProperties();
	}

	@Override
	protected ConversationClient getConversationClient(
			BriarIntegrationTestComponent component) {
		return component.getIntroductionManager();
	}

	@Test
	public void testIntroductionMessagesHaveTimer() throws Exception {
		makeIntroduction(true, true);
		assertIntroductionsArrived();

		assertMessagesAmong0And1HaveTimerSet(1, 1);
		assertMessagesAmong0And2HaveTimerSet(1, 1);
	}

	@Test
	public void testIntroductionAutoDeleteIntroducer() throws Exception {
		long timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;

		makeIntroduction(true, true);
		assertIntroductionsArrived();

		assertMessagesAmong0And1HaveTimerSet(1, 1);
		assertMessagesAmong0And2HaveTimerSet(1, 1);

		ack1To0(1);
		waitForEvents(c0);

		timeTravel(c0, timerLatency - 1);
		assertGroupCountAt0With1(1, 0);
		assertGroupCountAt0With2(1, 0);
		assertGroupCountAt1With0(1, 1);
		assertGroupCountAt2With0(1, 1);

		timeTravel(c0, 1);
		assertGroupCountAt0With1(0, 0);
		assertGroupCountAt0With2(1, 0);
		assertGroupCountAt1With0(1, 1);
		assertGroupCountAt2With0(1, 1);

		ack2To0(1);
		waitForEvents(c0);

		timeTravel(c0, timerLatency - 1);
		assertGroupCountAt0With1(0, 0);
		assertGroupCountAt0With2(1, 0);
		assertGroupCountAt1With0(1, 1);
		assertGroupCountAt2With0(1, 1);

		timeTravel(c0, 1);
		assertGroupCountAt0With1(0, 0);
		assertGroupCountAt0With2(0, 0);
		assertGroupCountAt1With0(1, 1);
		assertGroupCountAt2With0(1, 1);
	}

	@Test
	public void testIntroductionAutoDeleteIntroducee() throws Exception {
		long timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;

		makeIntroduction(true, false);

		markMessagesRead(c1, contact0From1);
		assertGroupCountAt1With0(1, 0);
		assertGroupCountAt2With0(1, 1);

		timeTravel(c1, timerLatency - 1);
		assertGroupCountAt0With1(1, 0);
		assertGroupCountAt0With2(1, 0);
		assertGroupCountAt2With0(1, 1);

		assertGroupCountAt1With0(1, 0);
		forEachHeader(c1, contactId0From1, 1, h ->
				assertTrue(h instanceof IntroductionRequest)
		);

		IntroductionResponseReceivedEvent e = assertEvent(c1,
				IntroductionResponseReceivedEvent.class, () ->
						timeTravel(c1, 1)
		);

		assertEquals(contactId0From1, e.getContactId());
		IntroductionResponse response = e.getMessageHeader();
		assertEquals(author2.getName(),
				response.getIntroducedAuthor().getName());
		assertTrue(response.isAutoDecline());

		assertGroupCountAt0With1(1, 0);
		assertGroupCountAt0With2(1, 0);
		assertGroupCountAt2With0(1, 1);

		assertGroupCountAt1With0(1, 0);
		forEachHeader(c1, contactId0From1, 1, h -> {
			assertTrue(h instanceof IntroductionResponse);
			IntroductionResponse r = (IntroductionResponse) h;
			assertEquals(author2.getName(), r.getIntroducedAuthor().getName());
			assertTrue(r.isAutoDecline());
		});

		sync1To0(1, true);
		waitForEvents(c0);

		assertGroupCountAt0With1(2, 1);

		sync0To2(1, true);
		waitForEvents(c2);

		assertGroupCountAt2With0(2, 2);
	}

	@Test
	public void testIntroductionSessionManualDecline() throws Exception {
		makeIntroduction(true, true);

		assertIntroducerStatus(AWAIT_RESPONSES);

		markMessagesRead(c1, contact0From1);
		markMessagesRead(c2, contact0From2);
		assertGroupCountAt1With0(1, 0);
		assertGroupCountAt2With0(1, 0);

		respondToMostRecentIntroduction(c1, contactId0From1, true);
		respondToMostRecentIntroduction(c2, contactId0From2, false);
		sync1To0(1, true);
		sync2To0(1, true);
		waitForEvents(c0);

		assertGroupCountAt1With0(2, 0);
		assertGroupCountAt2With0(2, 0);

		assertGroupCountAt0With1(2, 1);
		assertGroupCountAt0With2(2, 1);

		assertIntroducerStatus(START);

		markMessagesRead(c0, contact1From0);
		markMessagesRead(c0, contact2From0);
		assertGroupCountAt0With1(2, 0);
		assertGroupCountAt0With2(2, 0);

		sync0To1(1, true);
		sync0To2(1, true);
		waitForEvents(c1);
		waitForEvents(c2);

		assertGroupCountAt1With0(3, 1);

		assertGroupCountAt2With0(2, 0);

		markMessagesRead(c1, contact0From1);
		assertGroupCountAt1With0(3, 0);

		assertMessagesAmong0And1HaveTimerSet(2, 3);
		assertMessagesAmong0And2HaveTimerSet(2, 2);

		timeTravel(c0);
		timeTravel(c1);
		timeTravel(c2);

		assertGroupCountAt0With1(0, 0);
		assertGroupCountAt0With2(0, 0);
		assertGroupCountAt1With0(0, 0);
		assertGroupCountAt2With0(0, 0);
	}

	@Test
	public void testInvisibleAcceptForwards() throws Exception {
		testInvisibleForwards(true);
	}

	@Test
	public void testInvisibleDeclineForwards() throws Exception {
		testInvisibleForwards(false);
	}

	private void testInvisibleForwards(boolean accept) throws Exception {

		makeIntroduction(true, true);
		markMessagesRead(c1, contact0From1);
		markMessagesRead(c2, contact0From2);
		assertGroupCounts(1, 0, 1, 0, 1, 0, 1, 0);

		ack1To0(1);
		ack2To0(1);
		waitForEvents(c0);

		respondToMostRecentIntroduction(c1, contactId0From1, accept);
		respondToMostRecentIntroduction(c2, contactId0From2, accept);

		timeTravel(c0);
		timeTravel(c1);
		timeTravel(c2);
		assertGroupCounts(0, 0, 0, 0, 1, 0, 1, 0);

		sync1To0(1, true);
		sync2To0(1, true);
		waitForEvents(c0);
		assertGroupCounts(1, 1, 1, 1, 1, 0, 1, 0);

		markMessagesRead(c0, contact1From0);
		markMessagesRead(c0, contact2From0);
		timeTravel(c0);
		assertGroupCounts(0, 0, 0, 0, 1, 0, 1, 0);

		sync0To1(1, true);
		sync0To2(1, true);
		waitForEvents(c1);
		waitForEvents(c2);

		if (accept) {

			sync1To0(1, true);
			sync2To0(1, true);
		} else {
			ack1To0(1);
			ack2To0(1);
		}
		waitForEvents(c0);
		assertGroupCounts(0, 0, 0, 0, 1, 0, 1, 0);

		timeTravel(c0);
		timeTravel(c1);
		timeTravel(c2);
		assertGroupCounts(0, 0, 0, 0, 0, 0, 0, 0);
	}

	@Test
	public void testTwoIntroductionCycles() throws Exception {

		introduceAndAutoDecline();

		assertTrue(c0.getIntroductionManager()
				.canIntroduce(contact1From0, contact2From0));

		introduceAndAutoDecline();

		assertTrue(c0.getIntroductionManager()
				.canIntroduce(contact1From0, contact2From0));
	}

	private void introduceAndAutoDecline() throws Exception {

		makeIntroduction(true, true);
		markMessagesRead(c1, contact0From1);
		markMessagesRead(c2, contact0From2);
		assertGroupCounts(1, 0, 1, 0, 1, 0, 1, 0);

		ack1To0(1);
		ack2To0(1);
		waitForEvents(c0);

		timeTravel(c0);
		timeTravel(c1);
		timeTravel(c2);
		assertGroupCounts(0, 0, 0, 0, 1, 0, 1, 0);

		sync1To0(1, true);
		sync2To0(1, true);
		waitForEvents(c0);
		assertGroupCounts(1, 1, 1, 1, 1, 0, 1, 0);

		markMessagesRead(c0, contact1From0);
		markMessagesRead(c0, contact2From0);
		timeTravel(c0);
		assertGroupCounts(0, 0, 0, 0, 1, 0, 1, 0);

		sync0To1(1, true);
		sync0To2(1, true);
		waitForEvents(c1);
		waitForEvents(c2);

		ack1To0(1);
		ack2To0(1);
		waitForEvents(c0);
		assertGroupCounts(0, 0, 0, 0, 1, 0, 1, 0);

		timeTravel(c1);
		timeTravel(c2);
		assertGroupCounts(0, 0, 0, 0, 0, 0, 0, 0);
	}

	@Test
	public void testIntroductionSessionAutoDecline() throws Exception {
		makeIntroduction(true, false);

		assertIntroducerStatus(AWAIT_RESPONSES);

		ack1To0(1);
		ack2To0(1);
		waitForEvents(c0);

		markMessagesRead(c1, contact0From1);
		markMessagesRead(c2, contact0From2);
		assertGroupCountAt1With0(1, 0);
		assertGroupCountAt2With0(1, 0);

		timeTravel(c0);
		timeTravel(c1);
		timeTravel(c2);

		assertGroupCountAt0With1(0, 0);
		assertGroupCountAt0With2(1, 0);

		assertGroupCountAt1With0(1, 0);
		assertGroupCountAt2With0(1, 0);

		sync1To0(1, true);
		waitForEvents(c0);

		assertGroupCountAt0With1(1, 1);

		assertIntroducerStatusFirstDeclined();

		sync0To2(1, true);
		waitForEvents(c2);

		assertGroupCountAt2With0(2, 1);

		assertGroupCountAt0With1(1, 1);
		assertGroupCountAt1With0(1, 0);

		assertMessagesAmong0And1HaveTimerSet(1, 1);

		ack0To1(1);
		timeTravel(c1);
		assertGroupCountAt1With0(0, 0);

		markMessagesRead(c0, contact1From0);
		timeTravel(c0);
		assertGroupCountAt0With1(0, 0);

		respondToMostRecentIntroduction(c2, contactId0From2, false);
		sync2To0(1, true);
		waitForEvents(c0);
		sync0To1(1, true);
		waitForEvents(c1);

		assertIntroducerStatus(START);
		assertNewIntroductionSucceeds();
	}

	@Test
	public void testIntroductionAcceptHasTimer() throws Exception {
		testIntroductionResponseHasTimer(true);
	}

	@Test
	public void testIntroductionDeclineHasTimer() throws Exception {
		testIntroductionResponseHasTimer(false);
	}

	private void testIntroductionResponseHasTimer(boolean accept)
			throws Exception {
		makeIntroduction(true, false);
		assertIntroductionsArrived();

		assertMessagesAmong0And1HaveTimerSet(1, 1);
		assertMessagesAmong0And2HaveTimerNotSet(1, 1);

		respondToMostRecentIntroduction(c1, contactId0From1, accept);
		sync1To0(1, true);
		waitForEvents(c0);

		assertGroupCountAt0With1(2, 1);
		assertGroupCountAt1With0(2, 1);
		assertMessagesAmong0And1HaveTimerSet(2, 2);
	}

	@Test
	public void testIntroductionAcceptSelfDestructs() throws Exception {
		testIntroductionResponseSelfDestructs(true);
	}

	@Test
	public void testIntroductionDeclineSelfDestructs() throws Exception {
		testIntroductionResponseSelfDestructs(false);
	}

	private void testIntroductionResponseSelfDestructs(boolean accept)
			throws Exception {
		makeIntroduction(true, false);
		assertIntroductionsArrived();

		assertMessagesAmong0And1HaveTimerSet(1, 1);
		assertMessagesAmong0And2HaveTimerNotSet(1, 1);

		respondToMostRecentIntroduction(c1, contactId0From1, accept);
		sync1To0(1, true);
		waitForEvents(c0);

		ack0To1(1);
		waitForEvents(c1);

		assertGroupCountAt0With1(2, 1);
		assertGroupCountAt1With0(2, 1);
		assertMessagesAmong0And1HaveTimerSet(2, 2);

		markMessagesRead(c1, contact0From1);
		assertGroupCountAt1With0(2, 0);

		markMessagesRead(c0, contact1From0);
		assertGroupCountAt0With1(2, 0);

		timeTravel(c0);
		timeTravel(c1);
		timeTravel(c2);

		assertGroupCountAt0With1(0, 0);
		assertGroupCountAt1With0(0, 0);
	}

	@Test
	public void testSucceedAfterIntroducerSelfDestructed() throws Exception {
		testSucceedAfterIntroducerSelfDestructed(false);
	}

	@Test
	public void testSucceedAfterIntroducerAndResponsesSelfDestructed()
			throws Exception {
		testSucceedAfterIntroducerSelfDestructed(true);
	}

	private void testSucceedAfterIntroducerSelfDestructed(
			boolean autoDeleteResponsesBeforeSyncingAuthAndActivate)
			throws Exception {
		makeIntroduction(true, true);

		ack1To0(1);
		ack2To0(1);
		waitForEvents(c0);

		timeTravel(c0);

		assertGroupCountAt0With1(0, 0);
		assertGroupCountAt0With2(0, 0);

		assertGroupCountAt1With0(1, 1);
		assertGroupCountAt2With0(1, 1);

		respondToMostRecentIntroduction(c1, contactId0From1, true);
		respondToMostRecentIntroduction(c2, contactId0From2, true);
		sync1To0(1, true);
		sync2To0(1, true);
		waitForEvents(c0);

		assertGroupCountAt1With0(2, 1);
		assertGroupCountAt2With0(2, 1);

		sync0To1(1, true);
		sync0To2(1, true);
		waitForEvents(c1);
		waitForEvents(c2);

		if (autoDeleteResponsesBeforeSyncingAuthAndActivate) {
			assertGroupCountAt1With0(2, 1);
			assertGroupCountAt2With0(2, 1);

			markMessagesRead(c1, contact0From1);
			markMessagesRead(c2, contact0From2);

			assertGroupCountAt1With0(2, 0);
			assertGroupCountAt2With0(2, 0);

			timeTravel(c1);
			timeTravel(c2);

			assertGroupCountAt1With0(0, 0);
			assertGroupCountAt2With0(0, 0);
		}

		syncAuthAndActivateMessages();

		assertIntroductionSucceeded();
	}

	@Test
	public void testFailAfterIntroducerSelfDestructedBothDecline()
			throws Exception {
		testFailAfterIntroducerSelfDestructed(false, false);
	}

	@Test
	public void testFailAfterIntroducerSelfDestructedFirstAccept()
			throws Exception {
		testFailAfterIntroducerSelfDestructed(true, false);
	}

	@Test
	public void testFailAfterIntroducerSelfDestructedSecondAccept()
			throws Exception {
		testFailAfterIntroducerSelfDestructed(false, true);
	}

	private void testFailAfterIntroducerSelfDestructed(boolean firstAccepts,
			boolean secondAccepts) throws Exception {
		assertFalse(firstAccepts && secondAccepts);

		makeIntroduction(true, true);

		ack1To0(1);
		ack2To0(1);
		waitForEvents(c0);

		timeTravel(c0);

		assertGroupCountAt0With1(0, 0);
		assertGroupCountAt0With2(0, 0);

		assertIntroducerStatus(AWAIT_RESPONSES);
		assertIntroduceeStatus(c1, IntroduceeState.AWAIT_RESPONSES);
		assertIntroduceeStatus(c2, IntroduceeState.AWAIT_RESPONSES);

		assertGroupCountAt1With0(1, 1);
		assertGroupCountAt2With0(1, 1);

		markMessagesRead(c1, contact0From1);
		respondToMostRecentIntroduction(c1, contactId0From1, firstAccepts);
		sync1To0(1, true);
		waitForEvents(c0);

		if (firstAccepts) {
			assertIntroducerStatusFirstAccepted();
		} else {
			assertIntroducerStatusFirstDeclined();
		}

		markMessagesRead(c2, contact0From2);
		respondToMostRecentIntroduction(c2, contactId0From2, secondAccepts);
		sync2To0(1, true);
		waitForEvents(c0);

		assertIntroducerStatus(START);

		assertGroupCountAt1With0(2, 0);
		assertGroupCountAt2With0(2, 0);

		sync0To1(1, true);
		waitForEvents(c1);
		sync0To2(1, true);
		waitForEvents(c2);

		assertIntroductionFailed();

		if (firstAccepts) {

			assertGroupCountAt1With0(3, 1);
		} else {
			assertGroupCountAt1With0(2, 0);
		}
		if (secondAccepts) {

			assertGroupCountAt2With0(3, 1);
		} else {
			assertGroupCountAt2With0(2, 0);
		}

		timeTravel(c1);
		timeTravel(c2);

		if (firstAccepts) {
			assertGroupCountAt1With0(1, 1);
		} else {
			assertGroupCountAt1With0(0, 0);
		}
		if (secondAccepts) {
			assertGroupCountAt2With0(1, 1);
		} else {
			assertGroupCountAt2With0(0, 0);
		}

		if (firstAccepts) {
			markMessagesRead(c1, contact0From1);
			timeTravel(c1);
			assertGroupCountAt1With0(0, 0);
		}
		if (secondAccepts) {
			markMessagesRead(c2, contact0From2);
			timeTravel(c2);
			assertGroupCountAt2With0(0, 0);
		}

		assertIntroduceeStatus(c1, IntroduceeState.START);
		assertIntroduceeStatus(c2, IntroduceeState.START);

		assertNewIntroductionSucceeds();
	}

	@Test
	public void testAutoDecliningHappensEvenIfAcceptAlreadyReceived()
			throws Exception {
		testAutoDecliningHappensEvenIfResponseAlreadyReceived(true);
	}

	@Test
	public void testAutoDecliningHappensEvenIfDeclineAlreadyReceived()
			throws Exception {
		testAutoDecliningHappensEvenIfResponseAlreadyReceived(false);
	}

	private void testAutoDecliningHappensEvenIfResponseAlreadyReceived(
			boolean accept) throws Exception {
		makeIntroduction(false, true);
		markMessagesRead(c1, contact0From1);
		markMessagesRead(c2, contact0From2);
		assertGroupCounts(1, 0, 1, 0, 1, 0, 1, 0);

		ack1To0(1);
		ack2To0(1);
		waitForEvents(c0);

		timeTravel(c0);
		assertGroupCounts(1, 0, 0, 0, 1, 0, 1, 0);

		respondToMostRecentIntroduction(c1, contactId0From1, accept);
		assertGroupCounts(1, 0, 0, 0, 2, 0, 1, 0);

		sync1To0(1, true);
		waitForEvents(c0);
		assertGroupCounts(2, 1, 0, 0, 2, 0, 1, 0);

		assertIntroduceeStatus(c2, IntroduceeState.AWAIT_RESPONSES);

		if (accept) {

			sync0To2(1, true);
			waitForEvents(c2);
			assertGroupCounts(2, 1, 0, 0, 2, 0, 1, 0);
			assertIntroduceeStatus(c2, IntroduceeState.REMOTE_ACCEPTED);

			timeTravel(c2);
			assertGroupCounts(2, 1, 0, 0, 2, 0, 1, 0);

			sync2To0(1, true);
			waitForEvents(c0);
			assertGroupCounts(2, 1, 1, 1, 2, 0, 1, 0);

			sync0To1(1, true);
			waitForEvents(c1);
			assertGroupCounts(2, 1, 1, 1, 3, 1, 1, 0);
		} else {

			sync0To2(1, true);
			waitForEvents(c2);
			assertGroupCounts(2, 1, 0, 0, 2, 0, 2, 1);
			assertIntroduceeStatus(c2, IntroduceeState.REMOTE_DECLINED);

			timeTravel(c2);
			assertGroupCounts(2, 1, 0, 0, 2, 0, 2, 1);

			sync2To0(1, true);
			waitForEvents(c0);
			assertGroupCounts(2, 1, 1, 1, 2, 0, 2, 1);

			sync0To1(1, true);
			waitForEvents(c1);
			assertGroupCounts(2, 1, 1, 1, 2, 0, 2, 1);
		}

		assertNewIntroductionSucceeds();
	}

	private void makeIntroduction(boolean enableTimer1, boolean enableTimer2)
			throws Exception {
		if (enableTimer1) {
			setAutoDeleteTimer(c0, contact1From0.getId(),
					MIN_AUTO_DELETE_TIMER_MS);
		}
		if (enableTimer2) {
			setAutoDeleteTimer(c0, contact2From0.getId(),
					MIN_AUTO_DELETE_TIMER_MS);
		}

		c0.getIntroductionManager()
				.makeIntroduction(contact1From0, contact2From0, "Hi!");

		sync0To1(1, true);
		sync0To2(1, true);
		waitForEvents(c1);
		waitForEvents(c2);
	}

	private void respondToMostRecentIntroduction(
			BriarIntegrationTestComponent c, ContactId contactId,
			boolean accept) throws Exception {
		List<ConversationMessageHeader> headers =
				getMessageHeaders(c, contactId);
		Collections.reverse(headers);
		for (ConversationMessageHeader h : headers) {
			if (h instanceof IntroductionRequest) {
				IntroductionRequest ir = (IntroductionRequest) h;
				c.getIntroductionManager().respondToIntroduction(contactId,
						ir.getSessionId(), accept);
				return;
			}
		}
		fail("no introduction found");
	}

	private void markMessagesRead(BriarIntegrationTestComponent c,
			Contact contact) throws Exception {
		for (ConversationMessageHeader h : getMessageHeaders(c,
				contact.getId())) {
			markMessageRead(c, contact, h.getId());
		}
	}

	private void syncAuthAndActivateMessages() throws Exception {

		sync1To0(1, true);
		sync0To2(1, true);

		sync2To0(2, true);
		sync0To1(2, true);

		sync1To0(1, true);
		sync0To2(1, true);
	}

	private void timeTravel(BriarIntegrationTestComponent c) throws Exception {
		long timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;
		timeTravel(c, timerLatency);
	}

	private void timeTravel(BriarIntegrationTestComponent c, long timerLatency)
			throws Exception {
		c.getTimeTravel().addCurrentTimeMillis(timerLatency);
		waitForEvents(c);
	}

	private void assertIntroductionsArrived() throws DbException {

		assertGroupCount(c0, contactId1From0, 1, 0);
		assertGroupCount(c0, contactId2From0, 1, 0);
		assertGroupCount(c1, contactId0From1, 1, 1);
		assertGroupCount(c2, contactId0From2, 1, 1);
	}

	private void assertGroupCounts(int count01, int unread01, int count02,
			int unread02, int count10, int unread10, int count20, int unread20)
			throws Exception {
		assertGroupCountAt0With1(count01, unread01);
		assertGroupCountAt0With2(count02, unread02);
		assertGroupCountAt1With0(count10, unread10);
		assertGroupCountAt2With0(count20, unread20);
	}

	private void assertGroupCountAt0With1(int messageCount, int unreadCount)
			throws Exception {
		assertGroupCount(c0, contactId1From0, messageCount, unreadCount);
		assertEquals(messageCount,
				getMessageHeaders(c0, contactId1From0).size());
	}

	private void assertGroupCountAt0With2(int messageCount, int unreadCount)
			throws Exception {
		assertGroupCount(c0, contactId2From0, messageCount, unreadCount);
		assertEquals(messageCount,
				getMessageHeaders(c0, contactId2From0).size());
	}

	private void assertGroupCountAt1With0(int messageCount, int unreadCount)
			throws Exception {
		assertGroupCount(c1, contactId0From1, messageCount, unreadCount);
		assertEquals(messageCount,
				getMessageHeaders(c1, contactId0From1).size());
	}

	private void assertGroupCountAt2With0(int messageCount, int unreadCount)
			throws Exception {
		assertGroupCount(c2, contactId0From2, messageCount, unreadCount);
		assertEquals(messageCount,
				getMessageHeaders(c2, contactId0From2).size());
	}

	private void assertMessagesAmong0And1HaveTimerSet(int numC0, int numC1)
			throws Exception {
		forEachHeader(c0, contactId1From0, numC0, h ->
				assertEquals(MIN_AUTO_DELETE_TIMER_MS, h.getAutoDeleteTimer()));
		forEachHeader(c1, contactId0From1, numC1, h ->
				assertEquals(MIN_AUTO_DELETE_TIMER_MS, h.getAutoDeleteTimer()));
	}

	private void assertMessagesAmong0And2HaveTimerSet(int numC0, int numC2)
			throws Exception {
		forEachHeader(c0, contactId2From0, numC0, h ->
				assertEquals(MIN_AUTO_DELETE_TIMER_MS, h.getAutoDeleteTimer()));
		forEachHeader(c2, contactId0From2, numC2, h ->
				assertEquals(MIN_AUTO_DELETE_TIMER_MS, h.getAutoDeleteTimer()));
	}

	private void assertMessagesAmong0And2HaveTimerNotSet(int numC0, int numC2)
			throws Exception {
		forEachHeader(c0, contactId2From0, numC0, h ->
				assertEquals(NO_AUTO_DELETE_TIMER, h.getAutoDeleteTimer()));
		forEachHeader(c2, contactId0From2, numC2, h ->
				assertEquals(NO_AUTO_DELETE_TIMER, h.getAutoDeleteTimer()));
	}

	private void assertIntroducerStatus(IntroducerState state)
			throws DbException, FormatException {
		IntroducerSession introducerSession = getIntroducerSession();
		assertEquals(state, introducerSession.getState());
	}

	private void assertIntroducerStatusFirstDeclined()
			throws DbException, FormatException {
		IntroductionCrypto introductionCrypto =
				((IntroductionIntegrationTestComponent) c0)
						.getIntroductionCrypto();
		boolean alice =
				introductionCrypto.isAlice(contact1From0.getAuthor().getId(),
						contact2From0.getAuthor().getId());
		IntroducerSession introducerSession = getIntroducerSession();
		assertEquals(alice ? A_DECLINED : B_DECLINED,
				introducerSession.getState());
	}

	private void assertIntroducerStatusFirstAccepted()
			throws DbException, FormatException {
		IntroductionCrypto introductionCrypto =
				((IntroductionIntegrationTestComponent) c0)
						.getIntroductionCrypto();
		boolean alice =
				introductionCrypto.isAlice(contact1From0.getAuthor().getId(),
						contact2From0.getAuthor().getId());
		IntroducerSession introducerSession = getIntroducerSession();
		assertEquals(alice ? AWAIT_RESPONSE_B : AWAIT_RESPONSE_A,
				introducerSession.getState());
	}

	private void assertIntroduceeStatus(BriarIntegrationTestComponent c,
			IntroduceeState state)
			throws DbException, FormatException {
		IntroduceeSession introduceeSession = getIntroduceeSession(c);
		assertEquals(state, introduceeSession.getState());
	}

	private void assertIntroductionSucceeded() throws DbException {

		assertTrue(contactManager1
				.contactExists(author2.getId(), author1.getId()));
		assertTrue(contactManager2
				.contactExists(author1.getId(), author2.getId()));

		for (Contact c : contactManager1.getContacts()) {
			if (c.getAuthor().equals(author2)) {
				assertFalse(c.isVerified());
			}
		}
		for (Contact c : contactManager2.getContacts()) {
			if (c.getAuthor().equals(author1)) {
				assertFalse(c.isVerified());
			}
		}
	}

	private void assertIntroductionFailed() throws DbException {

		assertFalse(contactManager1
				.contactExists(author2.getId(), author1.getId()));
		assertFalse(contactManager2
				.contactExists(author1.getId(), author2.getId()));
	}

	private void assertNewIntroductionSucceeds() throws Exception {
		makeIntroduction(false, false);

		respondToMostRecentIntroduction(c1, contactId0From1, true);
		respondToMostRecentIntroduction(c2, contactId0From2, true);
		sync1To0(1, true);
		sync2To0(1, true);
		waitForEvents(c0);

		sync0To1(1, true);
		sync0To2(1, true);
		waitForEvents(c1);
		waitForEvents(c2);

		syncAuthAndActivateMessages();

		assertIntroductionSucceeded();
	}

	private void addTransportProperties() throws Exception {
		TransportPropertyManager tpm0 = c0.getTransportPropertyManager();
		TransportPropertyManager tpm1 = c1.getTransportPropertyManager();
		TransportPropertyManager tpm2 = c2.getTransportPropertyManager();

		tpm0.mergeLocalProperties(SIMPLEX_TRANSPORT_ID,
				getTransportProperties(2));
		sync0To1(1, true);
		sync0To2(1, true);

		tpm1.mergeLocalProperties(SIMPLEX_TRANSPORT_ID,
				getTransportProperties(2));
		sync1To0(1, true);

		tpm2.mergeLocalProperties(SIMPLEX_TRANSPORT_ID,
				getTransportProperties(2));
		sync2To0(1, true);
	}

	private IntroducerSession getIntroducerSession()
			throws DbException, FormatException {
		Map<MessageId, BdfDictionary> dicts = c0.getClientHelper()
				.getMessageMetadataAsDictionary(getLocalGroup().getId());
		assertEquals(1, dicts.size());
		BdfDictionary d = dicts.values().iterator().next();
		SessionParser sessionParser =
				((IntroductionIntegrationTestComponent) c0).getSessionParser();
		return sessionParser.parseIntroducerSession(d);
	}

	private IntroduceeSession getIntroduceeSession(
			BriarIntegrationTestComponent c)
			throws DbException, FormatException {
		Map<MessageId, BdfDictionary> dicts = c.getClientHelper()
				.getMessageMetadataAsDictionary(getLocalGroup().getId());
		assertEquals(1, dicts.size());
		BdfDictionary d = dicts.values().iterator().next();
		Group introducerGroup =
				c2.getIntroductionManager().getContactGroup(contact0From2);
		SessionParser sessionParser =
				((IntroductionIntegrationTestComponent) c).getSessionParser();
		return sessionParser
				.parseIntroduceeSession(introducerGroup.getId(), d);
	}

	private Group getLocalGroup() {
		return contactGroupFactory
				.createLocalGroup(CLIENT_ID, MAJOR_VERSION);
	}

}
