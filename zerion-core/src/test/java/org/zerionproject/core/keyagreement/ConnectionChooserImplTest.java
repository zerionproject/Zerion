package org.zerionproject.core.keyagreement;

import org.zerionproject.core.api.keyagreement.KeyAgreementConnection;
import org.zerionproject.core.api.plugin.TransportConnectionReader;
import org.zerionproject.core.api.plugin.TransportConnectionWriter;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Test;

import static org.zerionproject.core.test.TestUtils.getTransportId;
import static org.junit.Assert.assertSame;

public class ConnectionChooserImplTest extends BrambleMockTestCase {

	private final DuplexTransportConnection chosen =
			context.mock(DuplexTransportConnection.class, "chosen");
	private final DuplexTransportConnection unused =
			context.mock(DuplexTransportConnection.class, "unused");
	private final TransportConnectionReader reader =
			context.mock(TransportConnectionReader.class);
	private final TransportConnectionWriter writer =
			context.mock(TransportConnectionWriter.class);

	@Test
	public void stoppingClosesConnectionsThatWereNotChosen() throws Exception {
		ConnectionChooserImpl chooser =
				new ConnectionChooserImpl(new SystemClock(), Runnable::run);
		KeyAgreementConnection first =
				new KeyAgreementConnection(chosen, getTransportId());
		KeyAgreementConnection second =
				new KeyAgreementConnection(unused, getTransportId());
		chooser.submit(() -> first);
		chooser.submit(() -> second);
		assertSame(first, chooser.poll(1_000));

		context.checking(new Expectations() {{
			oneOf(unused).getReader();
			will(returnValue(reader));
			oneOf(reader).dispose(false, true);
			oneOf(unused).getWriter();
			will(returnValue(writer));
			oneOf(writer).dispose(false);
		}});

		chooser.stop();
	}
}
