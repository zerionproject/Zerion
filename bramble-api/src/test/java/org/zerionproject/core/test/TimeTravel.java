package org.zerionproject.core.test;

public interface TimeTravel {

	void setCurrentTimeMillis(long now) throws InterruptedException;

	void addCurrentTimeMillis(long add) throws InterruptedException;
}
