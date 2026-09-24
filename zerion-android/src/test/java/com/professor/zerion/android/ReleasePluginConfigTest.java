package com.professor.zerion.android;

import android.app.Application;

import com.professor.zerion.BuildConfig;
import com.professor.zerion.android.contact.add.nearby.ble.BluetoothKeyAgreementPluginFactory;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.zerionproject.core.api.FeatureFlags;
import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.PluginConfig;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPluginFactory;
import org.zerionproject.transport.ZtpDuplexPluginFactory;
import org.zerionproject.transport.i2p.I2pDuplexPluginFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.Set;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The plugin configuration offers the I2P transport only when the feature
 * flag says so, and the flag follows the build type: a release build never
 * registers I2P, there are no simplex transports, and nothing polls.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ReleasePluginConfigTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final TransportId BLUETOOTH = new TransportId("bt");

	@Test
	public void i2pIsRegisteredOnlyWhenTheFlagAllowsIt() {
		assertEquals(new HashSet<>(java.util.Arrays.asList(TorConstants.ID,
				BLUETOOTH)), transports(flags(false)));
		assertEquals(new HashSet<>(java.util.Arrays.asList(TorConstants.ID,
				I2pConstants.ID, BLUETOOTH)), transports(flags(true)));
	}

	@Test
	public void theFlagFollowsTheBuildType() {
		Application app = RuntimeEnvironment.getApplication();
		FeatureFlags flags = new AppModule(app).provideFeatureFlags();
		assertEquals(BuildConfig.DEBUG, flags.shouldEnableI2p());
	}

	@Test
	public void nothingElseIsRegisteredAndNothingPolls() {
		PluginConfig config = config(flags(false));
		assertTrue(config.getSimplexFactories().isEmpty());
		assertFalse(config.shouldPoll());
		assertTrue(config.getTransportPreferences().isEmpty());
	}

	private static Set<TransportId> transports(FeatureFlags flags) {
		Set<TransportId> ids = new HashSet<>();
		for (DuplexPluginFactory f : config(flags).getDuplexFactories()) {
			ids.add(f.getId());
		}
		return ids;
	}

	private static PluginConfig config(FeatureFlags flags) {
		Application app = RuntimeEnvironment.getApplication();
		ZtpDuplexPluginFactory ztp = mock(ZtpDuplexPluginFactory.class);
		when(ztp.getId()).thenReturn(TorConstants.ID);
		I2pDuplexPluginFactory i2p = mock(I2pDuplexPluginFactory.class);
		when(i2p.getId()).thenReturn(I2pConstants.ID);
		BluetoothKeyAgreementPluginFactory bt =
				mock(BluetoothKeyAgreementPluginFactory.class);
		when(bt.getId()).thenReturn(BLUETOOTH);
		return new AppModule(app).providePluginConfig(ztp, i2p, bt, flags);
	}

	private static FeatureFlags flags(boolean i2p) {
		return new FeatureFlags() {
			@Override
			public boolean shouldEnableImageAttachments() {
				return true;
			}

			@Override
			public boolean shouldEnableProfilePictures() {
				return true;
			}

			@Override
			public boolean shouldEnableDisappearingMessages() {
				return true;
			}

			@Override
			public boolean shouldEnablePrivateGroupsInCore() {
				return false;
			}

			@Override
			public boolean shouldEnableI2p() {
				return i2p;
			}
		};
	}
}
