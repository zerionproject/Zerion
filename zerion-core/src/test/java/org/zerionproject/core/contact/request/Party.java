package org.zerionproject.core.contact.request;

import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.IncomingRequest;
import org.zerionproject.core.api.contact.request.OutgoingRequest;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.data.BdfForTests;
import org.zerionproject.core.db.HyperSqlDatabaseForTests;
import org.zerionproject.core.test.TestMessageFactory;
import org.zerionproject.core.test.TestUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Party {

	final File dir;
	final CryptoComponent crypto;
	final SettableClock clock;
	final RecordingEventBus bus = new RecordingEventBus();
	final FakeIdentity identity;
	final DatabaseComponent db;
	final InviteLinkCodec codec;
	final ContactLinkStore links;
	final ContactRequestCrypto requestCrypto;
	final ContactRequestReceiver receiver;
	final ContactRequestSender sender;
	final InboxService inbox;
	final ContactRequestManagerImpl manager;
	final FakeTorNetwork.Adapter adapter;
	final TestScheduler scheduler = new TestScheduler();
	final ExecutorService io = Executors.newCachedThreadPool();

	Party(File root, String name, CryptoComponent crypto, SettableClock clock,
			FakeTorNetwork network) throws Exception {
		dir = new File(root, name);
		dir.mkdirs();
		this.crypto = crypto;
		this.clock = clock;
		identity = new FakeIdentity(crypto);
		db = HyperSqlDatabaseForTests.open(dir, TestUtils.getSecretKey(), bus,
				new TestMessageFactory());
		codec = new InviteLinkCodec(crypto);
		links = new ContactLinkStore(db, crypto, identity.manager, codec,
				clock);
		requestCrypto = new ContactRequestCrypto(crypto);
		adapter = network.new Adapter();
		receiver = new ContactRequestReceiver(db, links, requestCrypto,
				identity.manager, clock);
		sender = new ContactRequestSender(db, links, requestCrypto,
				BdfForTests.writers(), () -> adapter, identity.manager, clock,
				scheduler, r -> {
		});
		inbox = new InboxService(db, links, receiver, requestCrypto,
				BdfForTests.readers(), () -> adapter, clock, scheduler, io);
		manager = new ContactRequestManagerImpl(db, links, sender, receiver,
				null, identity.manager, clock, scheduler, io);
	}

	void close() throws Exception {
		scheduler.shutdown();
		io.shutdownNow();
		db.close();
	}

	List<ContactLink> links() throws Exception {
		return new ArrayList<>(db.transactionWithResult(true, links::getLinks));
	}

	List<IncomingRequest> incoming() throws Exception {
		return new ArrayList<>(db.transactionWithResult(true,
				db::getIncomingRequests));
	}

	OutgoingRequest outgoing(byte[] requestId) throws Exception {
		for (OutgoingRequest r : db.transactionWithResult(true,
				db::getOutgoingRequests)) {
			if (Arrays.equals(r.getRequestId(), requestId)) return r;
		}
		return null;
	}

	ContactLink zeroIdShared() throws Exception {
		ContactLink z = db.transactionWithResult(false,
				links::getOrCreateZeroId);
		return db.transactionWithResult(false,
				txn -> links.markShared(txn, z.getLinkId()));
	}

	String resetAndShare(FakeTorNetwork network) throws Exception {
		ContactLink fresh = db.transactionWithResult(false, links::resetZeroId);
		db.transaction(false, txn -> links.markShared(txn, fresh.getLinkId()));
		publish(network);
		return links.linkString(fresh);
	}

	void publish(FakeTorNetwork network) throws Exception {
		network.learn(links, links());
		inbox.refresh();
	}
}
