package com.professor.zerion.android.vault.ui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.professor.zerion.android.AndroidComponent;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.vault.wallet.xmr.XmrWalletManager;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Proves through the real dependency graph on the device that every UI
 * surface resolves the one application-scoped Monero manager: the component
 * hands out a single instance, and two ViewModel stores (one per hosting
 * activity) yield ViewModels backed by that same instance. Read-only: no
 * vault unlock, no wallet open, no app data touched.
 */
@RunWith(AndroidJUnit4.class)
public class XmrManagerSingletonDeviceTest {

	@Test
	public void everySurfaceResolvesTheSameManager() throws Exception {
		ZerionApplication app = (ZerionApplication)
				ApplicationProvider.getApplicationContext();
		AndroidComponent component = app.getApplicationComponent();
		XmrWalletManager direct = component.xmrWalletManager();
		assertNotNull(direct);
		assertSame("component is single-instance", direct,
				component.xmrWalletManager());

		ViewModelProvider.Factory factory = component.viewModelFactory();
		XmrViewModel[] surfaces = new XmrViewModel[2];
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			surfaces[0] = new ViewModelProvider(new ViewModelStore(), factory)
					.get(XmrViewModel.class);
			surfaces[1] = new ViewModelProvider(new ViewModelStore(), factory)
					.get(XmrViewModel.class);
		});
		assertSame("manager(NavDrawerActivity) === manager(VaultActivity)",
				surfaces[0].manager(), surfaces[1].manager());
		assertSame("ViewModels use the component's instance", direct,
				surfaces[0].manager());
	}
}
