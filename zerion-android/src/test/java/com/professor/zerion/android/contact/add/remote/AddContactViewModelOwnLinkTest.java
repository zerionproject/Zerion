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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
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

	private static final String OWN =
			"zerion://bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	private static final String[] SCHEMES = {"Zerion", "ZERION", "zErIoN"};

	private static AddContactViewModel viewModel(Application app,
			ContactManager contactManager) throws Exception {
		when(contactManager.getHandshakeLink(ContactType.ZERION))
				.thenReturn(OWN);
		AddContactViewModel vm = new AddContactViewModel(app,
				contactManager, Runnable::run, mock(LifecycleManager.class),
				mock(TransactionManager.class), mock(AndroidExecutor.class),
				new OwnLinkMemory(new OwnLinkMemoryTest.MemPrefs()));
		vm.onCreate();
		return vm;
	}

	@Test
	public void aPeerLinkWithAnyCaseOfTheSchemeIsKeptWithTheLowercaseScheme()
			throws Exception {
		Application app = ApplicationProvider.getApplicationContext();
		ContactManager contactManager = mock(ContactManager.class);
		AddContactViewModel vm = viewModel(app, contactManager);
		for (String scheme : SCHEMES) {
			String typed = scheme + PEER.substring("zerion".length());
			assertTrue(scheme, vm.isValidRemoteContactLink(typed));
			vm.setRemoteHandshakeLink(typed);
			assertEquals(scheme, PEER, vm.getRemoteHandshakeLink());
			assertEquals(scheme, PEER, vm.getLastRemoteHandshakeLink());
		}
		vm.addContact("Peer");
		verify(contactManager).addPendingContact(eq(PEER), eq("Peer"),
				any(), anyBoolean(), anyBoolean());
	}

	@Test
	public void theOwnLinkWithAnyCaseOfTheSchemeIsRecognised()
			throws Exception {
		Application app = ApplicationProvider.getApplicationContext();
		AddContactViewModel vm = viewModel(app, mock(ContactManager.class));
		assertTrue(vm.isOwnLink(OWN));
		for (String scheme : SCHEMES) {
			assertTrue(scheme, vm.isOwnLink(
					scheme + OWN.substring("zerion".length())));
		}
		assertFalse(vm.isOwnLink(PEER));
	}

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
