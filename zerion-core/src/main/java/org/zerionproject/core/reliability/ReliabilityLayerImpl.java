package org.zerionproject.core.reliability;

import org.zerionproject.core.api.reliability.ReliabilityLayer;
import org.zerionproject.core.api.reliability.WriteHandler;
import org.zerionproject.core.api.system.Clock;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
@MethodsNotNullByDefault
@ParametersNotNullByDefault
class ReliabilityLayerImpl implements ReliabilityLayer, WriteHandler {

	private static final int TICK_INTERVAL = 500;
	private final Executor executor;
	private final Clock clock;
	private final WriteHandler writeHandler;
	private final BlockingQueue<byte[]> writes;

	private volatile Receiver receiver = null;
	private volatile SlipDecoder decoder = null;
	private volatile ReceiverInputStream inputStream = null;
	private volatile SenderOutputStream outputStream = null;
	private volatile boolean running = false;

	ReliabilityLayerImpl(Executor executor, Clock clock,
			WriteHandler writeHandler) {
		this.executor = executor;
		this.clock = clock;
		this.writeHandler = writeHandler;
		writes = new LinkedBlockingQueue<>();
	}

	@Override
	public void start() {
		SlipEncoder encoder = new SlipEncoder(this);
		Sender sender = new Sender(clock, encoder);
		receiver = new Receiver(clock, sender);
		decoder = new SlipDecoder(receiver, Data.MAX_LENGTH);
		inputStream = new ReceiverInputStream(receiver);
		outputStream = new SenderOutputStream(sender);
		running = true;
		executor.execute(() -> {
			long now = clock.currentTimeMillis();
			long next = now + TICK_INTERVAL;
			try {
				while (running) {
					byte[] b = null;
					while (now < next && b == null) {
						b = writes.poll(next - now, MILLISECONDS);
						if (!running) return;
						now = clock.currentTimeMillis();
					}
					if (b == null) {
						sender.tick();
						while (next <= now) next += TICK_INTERVAL;
					} else {
						if (b.length == 0) return;
						writeHandler.handleWrite(b);
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				running = false;
			} catch (IOException e) {
				running = false;
			}
		});
	}

	@Override
	public void stop() {
		running = false;
		receiver.invalidate();
		writes.add(new byte[0]);
	}

	@Override
	public InputStream getInputStream() {
		return inputStream;
	}

	@Override
	public OutputStream getOutputStream() {
		return outputStream;
	}
	@Override
	public void handleRead(byte[] b) throws IOException {
		if (running) decoder.handleRead(b);
	}
	@Override
	public void handleWrite(byte[] b) {
		if (running && b.length > 0) writes.add(b);
	}
}
