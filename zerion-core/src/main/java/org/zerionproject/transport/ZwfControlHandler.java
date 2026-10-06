package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.function.Supplier;

import javax.annotation.Nullable;

@NotNullByDefault
public interface ZwfControlHandler {

	interface Sender {

		void send(byte[] payload);

		void sendWhenDue(Supplier<byte[]> builder);
	}

	void start(Sender sender);

	void onRecord(byte[] payload);

	void onPeerStreamAuthenticated(long epoch);

	void close();

	static Sender eager(java.util.function.Consumer<byte[]> sink) {
		return new Sender() {
			@Override
			public void send(byte[] payload) {
				sink.accept(payload);
			}

			@Override
			public void sendWhenDue(Supplier<byte[]> builder) {
				@Nullable byte[] payload = builder.get();
				if (payload != null) sink.accept(payload);
			}
		};
	}
}
