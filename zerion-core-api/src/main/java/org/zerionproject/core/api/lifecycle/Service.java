package org.zerionproject.core.api.lifecycle;

import org.zerionproject.core.api.system.Wakeful;

public interface Service {

	@Wakeful
	void startService() throws ServiceException;

	@Wakeful
	void stopService() throws ServiceException;
}
