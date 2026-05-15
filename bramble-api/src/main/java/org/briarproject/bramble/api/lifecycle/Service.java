package org.briarproject.bramble.api.lifecycle;

import org.briarproject.bramble.api.system.Wakeful;

public interface Service {

	@Wakeful
	void startService() throws ServiceException;

	@Wakeful
	void stopService() throws ServiceException;
}
