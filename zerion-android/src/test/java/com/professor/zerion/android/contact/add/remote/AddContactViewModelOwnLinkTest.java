package com.professor.zerion.android.contact.add.remote;

import android.app.Application;

import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.ContactType;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AddContactViewModelOwnLinkTest {

	@org.junit.Rule
	public final androidx.arch.core.executor.testing.InstantTaskExecutorRule
			instantTasks =
			new androidx.arch.core.executor.testing.InstantTaskExecutorRule();

	private static final String PEER =
			"zerion://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	@Test
	public void aContactLinkIsBoundToTheOwnLinkTheUserLastShared()
			throws Exception {
		Application app = ApplicationProvider.getApplicationContext();
		ContactManager contactManager = mock(ContactManager.class);
		LifecycleManager lifecycle = mock(LifecycleManager.class);
		TransactionManager db = mock(TransactionManager.class);
		AndroidExecutor androidExecutor = mock(AndroidExecutor.class);
		OwnLinkMemory memory = new OwnLinkMemory(
				new OwnLinkMemoryTest.MemPrefs());

		when(contactManager.getHandshakeLink(ContactType.ZERION))
				.thenReturn("zerion://first");
		AddContactViewModel firstVisit = new AddContactViewModel(app,
				contactManager, Runnable::run, lifecycle, db, androidExecutor,
				memory);
		firstVisit.onCreate();
		firstVisit.onOwnLinkShared();

		when(contactManager.getHandshakeLink(ContactType.ZERION))
				.thenReturn("zerion://second");
		AddContactViewModel secondVisit = new AddContactViewModel(app,
				contactManager, Runnable::run, lifecycle, db, androidExecutor,
				memory);
		secondVisit.onCreate();
		secondVisit.setRemoteHandshakeLink(PEER);
		secondVisit.addContact("Peer");

		ArgumentCaptor<String> bound = ArgumentCaptor.forClass(String.class);
		verify(contactManager).addPendingContact(eq(PEER), eq("Peer"),
				bound.capture(), anyBoolean(), anyBoolean());
		assertEquals("zerion://first", bound.getValue());
	}
}
