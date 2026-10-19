package com.professor.zerion.android.navdrawer;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.TorHelperTransportsPromptEvent;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.transport.TorBridgeConfigurator;

import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

@NotNullByDefault
public class HelperTransportsViewModel extends ViewModel
		implements EventListener {

	private final EventBus eventBus;
	private final SettingsManager settingsManager;
	private final Executor dbExecutor;
	private final TorBridgeConfigurator bridgeConfigurator;
	private final MutableLiveData<String> prompt = new MutableLiveData<>();

	@Inject
	HelperTransportsViewModel(EventBus eventBus,
			SettingsManager settingsManager,
			@DatabaseExecutor Executor dbExecutor,
			TorBridgeConfigurator bridgeConfigurator) {
		this.eventBus = eventBus;
		this.settingsManager = settingsManager;
		this.dbExecutor = dbExecutor;
		this.bridgeConfigurator = bridgeConfigurator;
		prompt.setValue(bridgeConfigurator.pendingHelperPrompt());
		eventBus.addListener(this);
	}

	LiveData<String> getPrompt() {
		return prompt;
	}

	@Override
	protected void onCleared() {
		eventBus.removeListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof TorHelperTransportsPromptEvent) {
			prompt.postValue(((TorHelperTransportsPromptEvent) e).getCountry());
		}
	}

	void answer(String country, boolean use) {
		prompt.setValue(null);
		Settings s = new Settings();
		s.putInt(TorConstants.PREF_TOR_HELPER_TRANSPORTS,
				use ? TorConstants.HELPER_TRANSPORTS_USE
						: TorConstants.HELPER_TRANSPORTS_OFF);
		s.put(TorConstants.PREF_TOR_HELPER_TRANSPORTS_COUNTRY, country);
		dbExecutor.execute(() -> {
			try {
				settingsManager.mergeSettings(s, TorConstants.ID.getString());
			} catch (DbException ignored) {
			}
			bridgeConfigurator.clearHelperPrompt();
		});
	}
}
