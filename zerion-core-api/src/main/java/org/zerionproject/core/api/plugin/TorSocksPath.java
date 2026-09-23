package org.zerionproject.core.api.plugin;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import javax.inject.Qualifier;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * The filesystem path of the Unix domain socket on which Tor listens for
 * SOCKS connections. It lies inside the app's private directory, so only
 * this app's own process can reach the listener.
 */
@Qualifier
@Target({FIELD, METHOD, PARAMETER})
@Retention(RUNTIME)
public @interface TorSocksPath {
}
