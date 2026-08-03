package org.zerionproject.core.api.plugin;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import javax.inject.Qualifier;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Qualifies the Tor SOCKS {@code SocketFactory} configured with the shorter
 * {@link TorConstants#FAST_CONNECT_TIMEOUT}, used only for burst re-dials after
 * a connection drops (distinct from the default factory used for first connects).
 */
@Qualifier
@Target({FIELD, METHOD, PARAMETER})
@Retention(RUNTIME)
public @interface FastConnectSocketFactory {
}
