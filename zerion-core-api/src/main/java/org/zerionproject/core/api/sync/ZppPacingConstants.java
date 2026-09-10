package org.zerionproject.core.api.sync;

/**
 * Settings that control the Zerion Pull Protocol's constant-rate pacing.
 *
 * <p>The send side always emits fixed-size frames at a constant, jittered
 * cadence, but the cadence has two regimes: an active regime while application
 * records have flowed recently, and a slower idle regime once a connection has
 * carried nothing but cover for a while. On metered networks the idle regime
 * is slower still unless the user disables the reduction.
 */
public interface ZppPacingConstants {

	String SETTINGS_NAMESPACE = "zpp-pacing";

	String PREF_REDUCE_MOBILE_DATA = "reduceMobileData";

	boolean DEFAULT_REDUCE_MOBILE_DATA = true;
}
