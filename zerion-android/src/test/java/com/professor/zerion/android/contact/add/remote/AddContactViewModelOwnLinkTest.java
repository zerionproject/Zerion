package com.professor.zerion.android.contact.add.remote;

import android.app.Application;

import android.util.Base64;

import com.professor.zerion.R;
import com.professor.zerion.android.contact.request.ScannedCode;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.request.ContactRequestManager;
import org.zerionproject.core.api.contact.request.InviteLink;
import org.zerionproject.core.api.contact.request.OwnLinkException;
import org.zerionproject.core.api.keyagreement.Payload;
import org.zerionproject.core.api.keyagreement.PayloadParser;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.contact.ContactType;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

	private final ContactRequestManager requests =
			mock(ContactRequestManager.class);
	private final PayloadParser parser = mock(PayloadParser.class);
	private final PluginManager plugins = mock(PluginManager.class);

	private AddContactViewModel viewModel(Application app,
			ContactManager contactManager) throws Exception {
		when(contactManager.getHandshakeLink(ContactType.ZERION))
				.thenReturn(OWN);
		AddContactViewModel vm = new AddContactViewModel(app,
				contactManager, requests, parser, plugins, Runnable::run,
				mock(LifecycleManager.class), mock(TransactionManager.class),
				mock(AndroidExecutor.class));
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
			assertEquals(scheme, PEER, ScannedCode.extractLink(typed));
			vm.setRemoteHandshakeLink(typed);
			assertEquals(scheme, PEER, vm.getRemoteHandshakeLink());
			assertEquals(scheme, PEER, vm.getLastRemoteHandshakeLink());
		}
		vm.addContact("Peer");
		verify(contactManager).addPendingContactBoundToSharedLink(eq(PEER),
				eq("Peer"), eq(OWN), anyBoolean(), anyBoolean());
	}

	@Test
	public void aScannedOwnLinkIsRefusedByTheLinkParser() throws Exception {
		Application app = ApplicationProvider.getApplicationContext();
		AddContactViewModel vm = viewModel(app, mock(ContactManager.class));
		when(requests.parseLink(OWN)).thenThrow(new OwnLinkException());
		for (String scheme : SCHEMES) {
			AddContactViewModel.ScanRoute r = vm.route(
					scheme + OWN.substring("zerion".length()));
			assertEquals(scheme, AddContactViewModel.ScanRoute.Kind.REFUSED,
					r.getKind());
			assertEquals(R.string.send_invite_own_link, r.getMessage());
		}
	}

	@Test
	public void aScannedInviteLinkOpensSendInvite() throws Exception {
		Application app = ApplicationProvider.getApplicationContext();
		AddContactViewModel vm = viewModel(app, mock(ContactManager.class));
		when(requests.parseLink(PEER)).thenReturn(new InviteLink(1,
				new byte[65], "x", InviteLink.Flow.REQUEST));
		AddContactViewModel.ScanRoute r = vm.route(PEER);
		assertEquals(AddContactViewModel.ScanRoute.Kind.INVITE, r.getKind());
		assertEquals(PEER, r.getText());
	}

	@Test
	public void aScannedPairingLinkStaysInPerson() throws Exception {
		Application app = ApplicationProvider.getApplicationContext();
		AddContactViewModel vm = viewModel(app, mock(ContactManager.class));
		when(requests.parseLink(PEER)).thenReturn(new InviteLink(1,
				new byte[65], null, InviteLink.Flow.MUTUAL_V1));
		assertEquals(AddContactViewModel.ScanRoute.Kind.IN_PERSON,
				vm.route(PEER).getKind());
	}

	@Test
	public void aNearbyPairingCodeIsRoutedToPairNearby() throws Exception {
		Application app = ApplicationProvider.getApplicationContext();
		AddContactViewModel vm = viewModel(app, mock(ContactManager.class));
		byte[] raw = {0x05, 0x60, 0x01, 0x02};
		String code = Base64.encodeToString(raw, Base64.NO_WRAP);
		when(parser.parse(new String(raw, ISO_8859_1)))
				.thenReturn(mock(Payload.class));
		AddContactViewModel.ScanRoute r = vm.route(code);
		assertEquals(AddContactViewModel.ScanRoute.Kind.NEARBY, r.getKind());
		assertEquals(code, r.getText());
		verify(requests, never()).parseLink(any());
	}

	@Test
	public void scanQrShowsTheLinkCodeOnlyWhileTorIsConnected() {
		Plugin tor = mock(Plugin.class);
		when(plugins.getPlugin(TorConstants.ID)).thenReturn(tor);
		when(tor.getState()).thenReturn(Plugin.State.ACTIVE);
		assertTrue(AddContactViewModel.isTorConnected(plugins));
		when(plugins.isOfflineMode()).thenReturn(true);
		assertFalse(AddContactViewModel.isTorConnected(plugins));
		when(plugins.isOfflineMode()).thenReturn(false);
		when(tor.getState()).thenReturn(Plugin.State.ENABLING);
		assertFalse(AddContactViewModel.isTorConnected(plugins));
		when(plugins.getPlugin(TorConstants.ID)).thenReturn(null);
		assertFalse(AddContactViewModel.isTorConnected(plugins));
	}

	@Test
	public void aLinkScannedWithoutInternetIsRoutedLikeAnyScan()
			throws Exception {
		Application app = ApplicationProvider.getApplicationContext();
		AddContactViewModel vm = viewModel(app, mock(ContactManager.class));
		when(requests.parseLink(PEER)).thenReturn(new InviteLink(1,
				new byte[65], "x", InviteLink.Flow.REQUEST));
		java.util.List<AddContactViewModel.ScanRoute> seen =
				new java.util.ArrayList<>();
		vm.getOfflineScanRoute().observeEventForever(seen::add);
		vm.onLinkScannedWithoutInternet(PEER);
		assertEquals(1, seen.size());
		assertEquals(AddContactViewModel.ScanRoute.Kind.INVITE,
				seen.get(0).getKind());
	}

	@Test
	public void aCodeThatIsNeitherIsIgnored() throws Exception {
		Application app = ApplicationProvider.getApplicationContext();
		AddContactViewModel vm = viewModel(app, mock(ContactManager.class));
		when(parser.parse(any())).thenThrow(new FormatException());
		assertEquals(AddContactViewModel.ScanRoute.Kind.IGNORED,
				vm.route("https://example.org").getKind());
		assertEquals(AddContactViewModel.ScanRoute.Kind.IGNORED,
				vm.route("aGVsbG8=").getKind());
	}

	@Test
	public void theShownLinkIsBoundWithoutRecordingAPerAddShare()
			throws Exception {
		Application app = ApplicationProvider.getApplicationContext();
		ContactManager contactManager = mock(ContactManager.class);
		AddContactViewModel vm = viewModel(app, contactManager);
		vm.setRemoteHandshakeLink(PEER);
		vm.addContact("Peer");
		verify(contactManager).addPendingContactBoundToSharedLink(PEER,
				"Peer", OWN, false, false);
		verify(contactManager, never()).addPendingContact(any(), any(),
				any(), anyBoolean(), anyBoolean());
		verify(contactManager, never()).recordPerAddLinkShared(any());
	}

	@Test
	public void showingTheQrCodeRecordsNoPerAddShare() throws Exception {
		String src = new String(java.nio.file.Files.readAllBytes(
				java.nio.file.Paths.get("src/main/java/com/professor/zerion/"
						+ "android/contact/add/remote/QrExchangeFragment.java")),
				java.nio.charset.StandardCharsets.UTF_8);
		String vm = new String(java.nio.file.Files.readAllBytes(
				java.nio.file.Paths.get("src/main/java/com/professor/zerion/"
						+ "android/contact/add/remote/AddContactViewModel.java")),
				java.nio.charset.StandardCharsets.UTF_8);
		org.junit.Assert.assertFalse(src.contains("onOwnLinkShared"));
		org.junit.Assert.assertFalse(vm.contains("recordPerAddLinkShared"));
	}
}
