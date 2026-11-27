package com.professor.zerion.android.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLContextSpi;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

public final class LegacyTlsDefault implements AutoCloseable {

	private final SSLContext previous;
	private final List<SSLSocket> sockets = new ArrayList<>();

	private LegacyTlsDefault() throws NoSuchAlgorithmException {
		previous = SSLContext.getDefault();
		SSLContext.setDefault(new Context(previous, new Factory(
				previous.getSocketFactory())));
	}

	public static LegacyTlsDefault install() throws NoSuchAlgorithmException {
		return new LegacyTlsDefault();
	}

	public List<SSLSocket> sockets() {
		synchronized (sockets) {
			return new ArrayList<>(sockets);
		}
	}

	public static boolean onlyModernProtocols(SSLSocket ssl) {
		List<String> enabled = Arrays.asList(ssl.getEnabledProtocols());
		if (enabled.isEmpty()) return false;
		for (String p : enabled) {
			if (!"TLSv1.3".equals(p) && !"TLSv1.2".equals(p)) return false;
		}
		return true;
	}

	@Override
	public void close() {
		SSLContext.setDefault(previous);
	}

	private Socket legacy(Socket s) {
		if (s instanceof SSLSocket) {
			SSLSocket ssl = (SSLSocket) s;
			ssl.setEnabledProtocols(ssl.getSupportedProtocols());
			synchronized (sockets) {
				sockets.add(ssl);
			}
		}
		return s;
	}

	private final class Factory extends SSLSocketFactory {

		private final SSLSocketFactory real;

		Factory(SSLSocketFactory real) {
			this.real = real;
		}

		@Override
		public String[] getDefaultCipherSuites() {
			return real.getDefaultCipherSuites();
		}

		@Override
		public String[] getSupportedCipherSuites() {
			return real.getSupportedCipherSuites();
		}

		@Override
		public Socket createSocket(Socket s, String host, int port,
				boolean autoClose) throws IOException {
			return legacy(real.createSocket(s, host, port, autoClose));
		}

		@Override
		public Socket createSocket(String host, int port) throws IOException {
			return legacy(real.createSocket(host, port));
		}

		@Override
		public Socket createSocket(String host, int port, InetAddress local,
				int localPort) throws IOException {
			return legacy(real.createSocket(host, port, local, localPort));
		}

		@Override
		public Socket createSocket(InetAddress host, int port)
				throws IOException {
			return legacy(real.createSocket(host, port));
		}

		@Override
		public Socket createSocket(InetAddress address, int port,
				InetAddress local, int localPort) throws IOException {
			return legacy(real.createSocket(address, port, local, localPort));
		}
	}

	private static final class Context extends SSLContext {

		Context(SSLContext real, SSLSocketFactory factory) {
			super(new Spi(real, factory), real.getProvider(),
					real.getProtocol());
		}
	}

	private static final class Spi extends SSLContextSpi {

		private final SSLContext real;
		private final SSLSocketFactory factory;

		Spi(SSLContext real, SSLSocketFactory factory) {
			this.real = real;
			this.factory = factory;
		}

		@Override
		protected void engineInit(KeyManager[] km, TrustManager[] tm,
				SecureRandom random) throws KeyManagementException {
		}

		@Override
		protected SSLSocketFactory engineGetSocketFactory() {
			return factory;
		}

		@Override
		protected SSLServerSocketFactory engineGetServerSocketFactory() {
			return real.getServerSocketFactory();
		}

		@Override
		protected SSLEngine engineCreateSSLEngine() {
			return real.createSSLEngine();
		}

		@Override
		protected SSLEngine engineCreateSSLEngine(String host, int port) {
			return real.createSSLEngine(host, port);
		}

		@Override
		protected SSLSessionContext engineGetServerSessionContext() {
			return real.getServerSessionContext();
		}

		@Override
		protected SSLSessionContext engineGetClientSessionContext() {
			return real.getClientSessionContext();
		}
	}
}
