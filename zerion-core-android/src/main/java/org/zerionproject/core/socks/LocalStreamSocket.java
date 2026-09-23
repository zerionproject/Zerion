package org.zerionproject.core.socks;

import android.net.LocalSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

import javax.annotation.Nullable;

/**
 * Presents a connected Unix domain socket as a {@link Socket} so that the
 * SOCKS client and everything layered on it (including TLS) can use it like
 * a TCP stream. Only the stream, timeout and shutdown operations are real;
 * TCP-specific options are accepted and ignored.
 */
final class LocalStreamSocket extends Socket {

	private final LocalSocket local;
	private boolean closed = false;
	private boolean inputShutdown = false;
	private boolean outputShutdown = false;

	LocalStreamSocket(LocalSocket local) {
		this.local = local;
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return local.getInputStream();
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		return local.getOutputStream();
	}

	@Override
	public void setSoTimeout(int timeout) throws SocketException {
		try {
			local.setSoTimeout(timeout);
		} catch (IOException e) {
			SocketException se = new SocketException(e.getMessage());
			se.initCause(e);
			throw se;
		}
	}

	@Override
	public int getSoTimeout() throws SocketException {
		try {
			return local.getSoTimeout();
		} catch (IOException e) {
			SocketException se = new SocketException(e.getMessage());
			se.initCause(e);
			throw se;
		}
	}

	@Override
	public void setTcpNoDelay(boolean on) {
	}

	@Override
	public void setKeepAlive(boolean on) {
	}

	@Override
	public synchronized void shutdownInput() throws IOException {
		inputShutdown = true;
		local.shutdownInput();
	}

	@Override
	public synchronized void shutdownOutput() throws IOException {
		outputShutdown = true;
		local.shutdownOutput();
	}

	@Override
	public synchronized boolean isInputShutdown() {
		return inputShutdown;
	}

	@Override
	public synchronized boolean isOutputShutdown() {
		return outputShutdown;
	}

	@Override
	public boolean isConnected() {
		return local.isConnected();
	}

	@Override
	public boolean isBound() {
		return local.isBound();
	}

	@Override
	public synchronized boolean isClosed() {
		return closed;
	}

	@Override
	@Nullable
	public SocketAddress getRemoteSocketAddress() {
		return null;
	}

	@Override
	public synchronized void close() throws IOException {
		if (closed) return;
		closed = true;
		local.close();
		super.close();
	}

	@Override
	public String toString() {
		return "LocalStreamSocket";
	}
}
