package org.briarproject.briar.privategroup;

import org.briarproject.bramble.api.FeatureFlags;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.data.MetadataEncoder;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.sync.validation.ValidationManager;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.privategroup.GroupMessageFactory;
import org.briarproject.briar.api.privategroup.PrivateGroupFactory;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationFactory;
import org.briarproject.briar.api.privategroup.senderkeys.CapabilityManager;
import org.briarproject.briar.api.privategroup.senderkeys.EpochRotationManager;
import org.briarproject.briar.api.privategroup.senderkeys.GroupMessageCrypto;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyDistributionFactory;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyDistributor;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyManager;
import org.briarproject.briar.privategroup.senderkeys.CapabilityManagerImpl;
import org.briarproject.briar.privategroup.senderkeys.EpochRotationManagerImpl;
import org.briarproject.briar.privategroup.senderkeys.GroupMessageCryptoImpl;
import org.briarproject.briar.privategroup.senderkeys.SenderKeyDistributionFactoryImpl;
import org.briarproject.briar.privategroup.senderkeys.SenderKeyDistributorImpl;
import org.briarproject.briar.privategroup.senderkeys.SenderKeyManagerImpl;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static org.briarproject.briar.api.privategroup.PrivateGroupManager.CLIENT_ID;
import static org.briarproject.briar.api.privategroup.PrivateGroupManager.MAJOR_VERSION;

@Module
public class PrivateGroupModule {

	public static class EagerSingletons {
		@Inject
		GroupMessageValidator groupMessageValidator;
		@Inject
		PrivateGroupManager groupManager;
	}

	@Provides
	@Singleton
	PrivateGroupManager provideGroupManager(
			PrivateGroupManagerImpl groupManager,
			ValidationManager validationManager,
			FeatureFlags featureFlags) {
		if (!featureFlags.shouldEnablePrivateGroupsInCore()) {
			return groupManager;
		}
		validationManager.registerIncomingMessageHook(CLIENT_ID, MAJOR_VERSION,
				groupManager);
		return groupManager;
	}

	@Provides
	PrivateGroupFactory providePrivateGroupFactory(
			PrivateGroupFactoryImpl privateGroupFactory) {
		return privateGroupFactory;
	}

	@Provides
	GroupMessageFactory provideGroupMessageFactory(
			GroupMessageFactoryImpl groupMessageFactory) {
		return groupMessageFactory;
	}

	@Provides
	@Singleton
	SenderKeyManager provideSenderKeyManager(
			CryptoComponent crypto,
			DatabaseComponent db,
			IdentityManager identityManager,
			Clock clock) {
		return new SenderKeyManagerImpl(crypto, db, identityManager, clock);
	}

	@Provides
	@Singleton
	SenderKeyDistributionFactory provideSenderKeyDistributionFactory(
			ClientHelper clientHelper,
			CryptoComponent crypto,
			Clock clock) {
		return new SenderKeyDistributionFactoryImpl(clientHelper, crypto, clock);
	}

	@Provides
	@Singleton
	SenderKeyDistributor provideSenderKeyDistributor(
			ClientHelper clientHelper,
			ContactManager contactManager,
			CryptoComponent crypto,
			IdentityManager identityManager,
			MessagingManager messagingManager,
			SenderKeyDistributionFactory distributionFactory,
			SenderKeyManager senderKeyManager) {
		return new SenderKeyDistributorImpl(
				clientHelper,
				contactManager,
				crypto,
				identityManager,
				messagingManager,
				distributionFactory,
				senderKeyManager);
	}

	@Provides
	@Singleton
	GroupMessageCrypto provideGroupMessageCrypto(
			CryptoComponent crypto,
			SenderKeyManager senderKeyManager) {
		return new GroupMessageCryptoImpl(crypto, senderKeyManager);
	}

	@Provides
	@Singleton
	EpochRotationManager provideEpochRotationManager(
			CryptoComponent crypto,
			Clock clock,
			SenderKeyManager senderKeyManager) {
		return new EpochRotationManagerImpl(crypto, clock, senderKeyManager);
	}

	@Provides
	@Singleton
	CapabilityManager provideCapabilityManager(
			Clock clock,
			ContactManager contactManager,
			IdentityManager identityManager,
			SenderKeyManager senderKeyManager,
			SenderKeyDistributor senderKeyDistributor) {
		return new CapabilityManagerImpl(
				clock,
				contactManager,
				identityManager,
				senderKeyManager,
				senderKeyDistributor);
	}

	@Provides
	@Singleton
	GroupMessageValidator provideGroupMessageValidator(
			PrivateGroupFactory privateGroupFactory,
			ClientHelper clientHelper, MetadataEncoder metadataEncoder,
			Clock clock, GroupInvitationFactory groupInvitationFactory,
			CryptoComponent crypto,
			ValidationManager validationManager, FeatureFlags featureFlags) {
		GroupMessageValidator validator = new GroupMessageValidator(
				privateGroupFactory, clientHelper, metadataEncoder, clock,
				groupInvitationFactory, crypto);
		if (featureFlags.shouldEnablePrivateGroupsInCore()) {
			validationManager.registerMessageValidator(CLIENT_ID, MAJOR_VERSION,
					validator);
		}
		return validator;
	}

}
