package org.zerionproject.core.api.system;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import javax.inject.Qualifier;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Qualifier
@Target(METHOD)
@Retention(RUNTIME)
public @interface Wakeful {
}