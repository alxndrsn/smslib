// SMSLib for Java
// An open-source API Library for sending and receiving SMS via a GSM modem.
// Copyright (C) 2002-2007, Thanasis Delenikas, Athens/GREECE
// Web Site: http://www.smslib.org
//
// SMSLib is distributed under the LGPL license.
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
// 
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
// Lesser General Public License for more details.
// 
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA

package org.smslib.handler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.smslib.CSerialDriver;
import org.smslib.CService;
import org.smslib.CUtils;
import org.smslib.SMSLibDeviceException;
import org.smslib.stk.StkConfirmationPrompt;
import org.smslib.stk.StkMenu;
import org.smslib.stk.StkMenuItem;
import org.smslib.stk.StkNotification;
import org.smslib.stk.StkParseException;
import org.smslib.stk.StkRequest;
import org.smslib.stk.StkResponse;
import org.smslib.stk.StkValuePrompt;

public class CATHandler_Wavecom_Stk extends CATHandler_Wavecom {
	private static final Pattern CONFIRMATION_PROMPT_REGEX = Pattern.compile("^\\s*\\+STGI: \\d+,\".*\",\\d+\\s+OK\\s*$", Pattern.DOTALL);
	private static final Pattern MENU_REGEX = Pattern.compile(".*\\s+\\+STGI: \\d+,\\d+,\".*\"(,\\d+)*\\s.*", Pattern.DOTALL);
	/** control-z/EOF character (hex: 0x1a) */
	private static final char CTRL_Z = 26;
	/** Arbitrarily-chosen timeout for waiting for STIN response.  Please modify
	 * and document if you have useful experience with how long this should be. */
	private static final long STIN_WAIT_TIMEOUT = 15*1000;
	
	public CATHandler_Wavecom_Stk(CSerialDriver serialDriver, Logger log, CService srv) {
		super(serialDriver, log, srv);
	}
	
	@Override
	public void initStorageLocations() throws IOException {
		storageLocations = "SMME";
	}
	
	@Override
	public boolean supportsStk() {
		return true;
	}
	
	@Override
	public void stkInit() throws SMSLibDeviceException, IOException {
		srv.doSynchronized(new SynchronizedWorkflow<Object>() {
			public Object run() throws IOException {
				serialSendReceive("AT+CMEE=1");
		 		serialSendReceive("AT+STSF=1");
		 		
		 		// FIXME why would the PIN need to be set up here if the connection has already been established previously?
				String pinResponse = getPinResponse();
				if(isWaitingForPin(pinResponse)) {
					enterPin(srv.getSimPin());
				}
				
				// Sometimes the first command issued to a Wavecom will fail.  If we issue
				// the command here, then hopefully all future commands *will* work :-)
				serialSendReceive("AT+STGR=99");
				
				return null;
			}
		});
	}
	
	/** Starts a new STK session if required. */
	public void stkStartNewSession() throws IOException, SMSLibDeviceException, StkParseException {
		String initResponse = serialSendReceive("AT+STGR=99");
		if (notOk(initResponse)) {
			// CME ERROR: 3 appears not to be problematic
			if(!initResponse.contains("+CME ERROR: 3")) {
				throw new SMSLibDeviceException("Unable to start new session: " + initResponse);
			}
		} else {
			String menuId = getStinResponseId();
			if(menuId.equals("99")) { // TODO what is meant to happen in this case??
			} else if(menuId.equals("6")) {
				initResponse = serialSendReceive("AT+STGR=99");
			}
		}
	}
	
	@Override
	public StkResponse stkRequest(final StkRequest request, final String... variables)
			throws SMSLibDeviceException, IOException {
		return srv.doSynchronized(new SynchronizedWorkflow<StkResponse>() {
			public StkResponse run() throws IOException, SMSLibDeviceException {
				try {
					if(request.equals(StkRequest.GET_ROOT_MENU)) {
						return doRootMenuRequest();
					} else if(request instanceof StkMenuItem) {
						return doMenuRequest((StkMenuItem) request);
					} else if(request.equals(StkValuePrompt.REQUEST)) {
						return doValuePromptRequest(request, variables);
					} else if(request.equals(StkConfirmationPrompt.REQUEST)) {
						return doConfirmationRequest();
					} else throw new IllegalStateException("Do not know how to make STK request: " + request);
				} catch(StkParseException ex) {
					throw new SMSLibDeviceException(ex);
				}
			}
		});
 	}

	private StkResponse doValuePromptRequest(StkRequest request, String... variables) throws IOException, StkParseException, SMSLibDeviceException {
		// 3[mode=input],1[not sure],1[this seems to be optional]
		serialSendReceive("AT+STGR=3,1,1");
		// Suffix variable with "EOF"/"End of file"/"Ctrl-z"
		serialDriver.send(variables[0] + CTRL_Z);
		String menuId = getStinResponseId();
		String next = serialSendReceive("AT+STGI=" + menuId);
		return parseStkResponse(next, menuId);
	}
	
	StkResponse parseStkResponse(String serialResponse, String menuId) throws StkParseException {
		if(isMenu(serialResponse)) {
			return parseStkMenu(serialResponse, menuId);
		} else if(isValuePrompt(serialResponse)) {
			return new StkValuePrompt(getQuotedText(serialResponse));
		} else if(isConfirmationPrompt(serialResponse)) {
			return new StkConfirmationPrompt(getQuotedText(serialResponse));
		} else {
			return new StkNotification(getQuotedText(serialResponse));
		}
	}
	
	private String getQuotedText(String response) throws StkParseException {
		Matcher m = Pattern.compile("\"(.*)\"", Pattern.DOTALL).matcher(response);
		if(m.find()) return m.group(1);
		else throw new StkParseException(response);
	}
	
	private static boolean isMenu(String serialResponse) {
		return MENU_REGEX.matcher(serialResponse).matches();
	}
	
	private static boolean isConfirmationPrompt(String serialResponse) {
		return CONFIRMATION_PROMPT_REGEX.matcher(serialResponse).matches();
	}

	private static boolean isValuePrompt(String serialResponse) {
		return serialResponse.matches("\\s*\\+STGI: (\\d+,){5}\".*\"(\\s+OK)?\\s*");
	}
	
	private StkResponse doConfirmationRequest() throws IOException, SMSLibDeviceException, StkParseException {
		// 1[confirm],1[dunno, but always there],1[optional it seems]
		send("AT+STGR=1,1,1");
		String stgiResponse = send("AT+STGI=" + getStinResponseId());
		return parseStkResponse(stgiResponse, "?");
	}
	
	private String send(String request) throws IOException, SMSLibDeviceException {
		String response = serialSendReceive(request);
		if(!response.contains("OK")) {
			throw new SMSLibDeviceException("Unexpected response from device: " + response);
		} else {
			return response;
		}
	}
	
	private StkResponse doRootMenuRequest() throws StkParseException, IOException, SMSLibDeviceException {
		stkStartNewSession();
		
		for(int i=0; i<3; ++i) {
			try {
				return parseStkResponse(send("AT+STGI=0"), "0");
			} catch(StkParseException ex) {
				// and now, retry
				log.info("Retrying root menu fetch after exception.", ex);
			}
		}
		
		throw new SMSLibDeviceException("Unable to get root STK menu after multiple attempts.");
	}

	private StkResponse doMenuRequest(StkMenuItem request) throws IOException, SMSLibDeviceException, StkParseException {
		String initialResponse = serialSendReceive("AT+STGR=" + request.getMenuId() + ",1," + request.getId());
		if(initialResponse.contains("OK")) {
			String newMenuId = getStinResponseId();
			String secondaryResponse = serialSendReceive("AT+STGI=" + newMenuId);
			return parseStkResponse(secondaryResponse, newMenuId);
		} else throw new SMSLibDeviceException("Unexpected response to AT+STGR: " + initialResponse);
	}
	
	private String getStinResponseId() throws SMSLibDeviceException, IOException, StkParseException {
		String stinResponse = serialDriver.getLastClearedBuffer();
		long timeToDie = System.currentTimeMillis() + STIN_WAIT_TIMEOUT;
		while(!stinResponse.matches("\\s*\\p{ASCII}*\\s*\\+STIN: \\d+\\s*")) {
			if(stinResponse.contains("ERROR")) {
				throw new StkParseException(stinResponse);
			}
			if(System.currentTimeMillis() > timeToDie) {
				throw new SMSLibDeviceException("Timeout while waiting for STIN response.");
			}
			CUtils.sleep_ignoreInterrupts(200);
			stinResponse = serialDriver.readBuffer();
		}
		return extractNumber(stinResponse);
	}

	/** Extract the 1st set of digits from the supplied string. */
	private static String extractNumber(String s) throws StkParseException {
		Matcher m = Pattern.compile("\\d+").matcher(s);
		if(m.find()) return m.group();
		else throw new StkParseException(s);
	}

	StkMenu parseStkMenu(String response, String menuId) throws StkParseException {
		String title = parseStkMenuTitle(response);
		List<StkMenuItem> menuItems = parseMenuItems(response, menuId);
		return new StkMenu(title, menuItems.toArray());
	}
	
	String parseStkMenuTitle(String response) throws StkParseException {
		Matcher m = Pattern.compile("\"(.*)\"").matcher(response);
		if(m.find()) return m.group(1);
		else throw new StkParseException(response);
	}

	private List<StkMenuItem> parseMenuItems(String response, String menuId) {
		ArrayList<StkMenuItem> items = new ArrayList<StkMenuItem>();

		Matcher m = Pattern.compile("\\+STGI: (\\d+),\\d+,\"(.*)\"(:?,\\d+)?").matcher(response);
		while (m.find()) {
			String id = m.group(1);
			String title = m.group(2);
			items.add(new StkMenuItem(id, title, menuId));
		}
		return items;
	}

	private boolean notOk(String initResponse) {
		return initResponse.contains("ERROR");
	}
}
