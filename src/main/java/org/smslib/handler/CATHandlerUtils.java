package org.smslib.handler;

import java.lang.reflect.InvocationTargetException;

import org.apache.log4j.Logger;
import org.smslib.CSerialDriver;
import org.smslib.CService;

public class CATHandlerUtils {
	/**
	 * Attempt to load a particular AT Handler.
	 * @param serialDriver
	 * @param log
	 * @param srv
	 * @param handlerClassName
	 * @return A new instance of the required handler.
	 * @throws ClassNotFoundException
	 * @throws SecurityException
	 * @throws NoSuchMethodException
	 * @throws IllegalArgumentException
	 * @throws InstantiationException
	 * @throws InvocationTargetException
	 * @throws IllegalAccessException
	 */
	@SuppressWarnings("unchecked")
	private static ATHandler load(CSerialDriver serialDriver, Logger log, CService srv, String handlerClassName) throws ClassNotFoundException, SecurityException, NoSuchMethodException, IllegalArgumentException, InstantiationException, InvocationTargetException, IllegalAccessException {
		log.info("Attempting to load handler: " + handlerClassName);
		
		Class<ATHandler> handlerClass = (Class<ATHandler>) Class.forName(handlerClassName);

		java.lang.reflect.Constructor<ATHandler> handlerConstructor = handlerClass.getConstructor(new Class[] { CSerialDriver.class, Logger.class, CService.class });
		ATHandler atHandlerInstance = handlerConstructor.newInstance(new Object[]{serialDriver, log, srv});
		
		log.info("Successfully loaded handler: " + atHandlerInstance.getClass().getName());
		
		return atHandlerInstance;
	}
	
	/**
	 * 
	 * @param serialDriver
	 * @param log
	 * @param srv
	 * @param gsmDeviceManufacturer
	 * @param gsmDeviceModel
	 * @param catHandlerAlias
	 * @return
	 */
	public static ATHandler load(CSerialDriver serialDriver, Logger log, CService srv, String gsmDeviceManufacturer, String gsmDeviceModel, String catHandlerAlias) {
		log.trace("ENTRY");
		final String BASE_HANDLER = org.smslib.handler.CATHandler.class.getName();

		if (catHandlerAlias != null && !catHandlerAlias.equals("")) {
			// suggested cat handler from method param
			String requestedHandlerName = BASE_HANDLER + "_" + catHandlerAlias;
			try {
				return load(serialDriver, log, srv, requestedHandlerName);
			} catch(Exception ex) {
				log.info("Could not load requested handler '" + requestedHandlerName + "'; will try more generic version.", ex);
			}
		}

		if (gsmDeviceManufacturer != null && !gsmDeviceManufacturer.equals("")) {
			String manufacturerHandlerName = BASE_HANDLER + "_" + gsmDeviceManufacturer;
			
			if (gsmDeviceModel != null && !gsmDeviceModel.equals("")) {
				String modelHandlerName = manufacturerHandlerName + "_" + gsmDeviceModel;
				try {
					return load(serialDriver, log, srv, modelHandlerName);
				} catch(Exception ex) {
					log.info("Could not load requested handler '" + modelHandlerName + "'; will try more generic version.", ex);
				}
			}

			try {
				return load(serialDriver, log, srv, manufacturerHandlerName);
			} catch(Exception ex) {
				log.info("Could not load requested handler '" + manufacturerHandlerName + "'; will try more generic version.", ex);
			}
		}
		
		return new CATHandler(serialDriver, log, srv);
	}

	public static ATHandler caseInsensitiveLoad(CSerialDriver serialDriver, Logger log, CService srv, String gsmDeviceManufacturer, String gsmDeviceModel, String catHandlerAlias) {
		final String BASE_HANDLER = "cathandler";
		String lowerCaseCompositeName;
		String handlerClassName;
		if(catHandlerAlias != null && catHandlerAlias.length() > 0) {
			lowerCaseCompositeName = (BASE_HANDLER + "_" + catHandlerAlias).toLowerCase();
			handlerClassName = getHandlerClassName(lowerCaseCompositeName);
			if(handlerClassName != null) {
				try {
					return load(serialDriver, log, srv, handlerClassName);
				} catch(Exception ex) {
					log.info("Could not load requested handler '" + handlerClassName + "'; will try more generic version.", ex);
				}
			}
		}

		if(gsmDeviceManufacturer != null && gsmDeviceManufacturer.length() > 0) {
			if(gsmDeviceModel != null && gsmDeviceModel.length() > 0) {
				// TODO check for double match
				lowerCaseCompositeName = (BASE_HANDLER + "_" + gsmDeviceManufacturer + "_" + gsmDeviceModel).toLowerCase();
				handlerClassName = getHandlerClassName(lowerCaseCompositeName);
				if(handlerClassName != null) {
					try {
						return load(serialDriver, log, srv, handlerClassName);
					} catch(Exception ex) {
						log.info("Could not load requested handler '" + handlerClassName + "'; will try more generic version.", ex);
					}
				}
			}

			lowerCaseCompositeName = (BASE_HANDLER + "_" + gsmDeviceManufacturer).toLowerCase();
			handlerClassName = getHandlerClassName(lowerCaseCompositeName);
			if(handlerClassName != null) {
				try {
					return load(serialDriver, log, srv, handlerClassName);
				} catch(Exception ex) {
					log.info("Could not load requested handler '" + handlerClassName + "'; will try more generic version.", ex);
				}
			}
		}

		return new CATHandler(serialDriver, log, srv);
	}

	/**
	 * Attempt to get a fully-qualified handler class name with the correct case
	 * from the lower-case version of the simple name of that class.
	 * @param lowerCaseName the name in lowercase
	 * @return the name in correct case, or <code>null</code> if there was no match.
	 */
	private static String getHandlerClassName(String lowerCaseName) {
		Class[] handlers = getHandlers();
		for(int i=0; i<handlers.length; ++i) {
			Class<?> c = handlers[i];
			String className = handlers[i].getName();
			if(c.getSimpleName().toLowerCase().equals(lowerCaseName)) {
				return c.getName();
			}
		}
		return null;
	}

	
	/** List of all AT handler classes */
	@SuppressWarnings("rawtypes")
	private static final Class[] HANDLERS = { // TODO this could be replaced with a Java4-safe serviceLoader implementation.
		CATHandler.class,
		CATHandler_Huawei.class,
		CATHandler_Motorola_RAZRV3x.class,
		CATHandler_Nokia_S40_3ed.class,
		CATHandler_Samsung.class,
		CATHandler_Siemens_M55.class,
		CATHandler_Siemens_MC75.class,
		CATHandler_Siemens_S55.class,
		CATHandler_Siemens_TC35.class,
		CATHandler_Simcom_SIMCOM_SIM300.class,
		CATHandler_SonyEricsson_GT48.class,
		CATHandler_SonyEricsson_W550i.class,
		CATHandler_SonyEricsson.class,
		CATHandler_Symbian_PiAccess.class,
		CATHandler_Wavecom_M1306B.class,
		CATHandler_Wavecom_Stk.class,
		CATHandler_Wavecom.class,
	};

	/**
	 * Gets a list containing all available AT Handlers.
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ATHandler> Class<T>[] getHandlers() {
		return HANDLERS;
	}
}

