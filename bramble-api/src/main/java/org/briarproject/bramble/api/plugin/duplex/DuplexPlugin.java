package org.briarproject.bramble.api.plugin.duplex;

import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.keyagreement.KeyAgreementListener;
import org.briarproject.bramble.api.plugin.ConnectionHandler;
import org.briarproject.bramble.api.plugin.Plugin;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.rendezvous.KeyMaterialSource;
import org.briarproject.bramble.api.rendezvous.RendezvousEndpoint;
import org.briarproject.bramble.api.system.Wakeful;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;


@NotNullByDefault
public interface DuplexPlugin extends Plugin {

	
	@Wakeful
	@Nullable
	DuplexTransportConnection createConnection(TransportProperties p);

	
	boolean supportsKeyAgreement();

	
	@Nullable
	KeyAgreementListener createKeyAgreementListener(byte[] localCommitment);

	
	@Nullable
	DuplexTransportConnection createKeyAgreementConnection(
			byte[] remoteCommitment, BdfList descriptor);

	
	boolean supportsRendezvous();

	
	@Nullable
	RendezvousEndpoint createRendezvousEndpoint(KeyMaterialSource k,
			boolean alice, ConnectionHandler incoming);
}
