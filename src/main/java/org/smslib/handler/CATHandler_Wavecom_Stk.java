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
import org.smslib.SMSLibDeviceException;
import org.smslib.stk.StkConfirmationPrompt;
import org.smslib.stk.StkConfirmationPromptResponse;
import org.smslib.stk.StkMenu;
import org.smslib.stk.StkMenuItem;
import org.smslib.stk.StkParseException;
import org.smslib.stk.StkRequest;
import org.smslib.stk.StkResponse;
import org.smslib.stk.StkValuePrompt;

public class CATHandler_Wavecom_Stk extends CATHandler_Wavecom {
	private static final int DELAY_STGR = 500;
	private static final String VALUE_PROMPT_REGEX = "\\s*\\+STGI: (\\d+,){5}\".*\"(\\s+OK\\s*)?";
	private static final Pattern CONFIRMATION_PROMPT_REGEX = Pattern.compile("\\s*\\+STGI: \\d+,\".*\",\\d+\\s+OK\\s*", Pattern.DOTALL);
	public String regexNumberComma = "([\\d])+(,)+";
	public String regexNumber = "([\\d])+";
	
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
				String s = serialSendReceive("AT+CMEE=1");
		 		String t = serialSendReceive("AT+STSF=1");
		 		
		 		// FIXME why would the PIN need to be set up here if the connection has already been established previously?
				String pinResponse = getPinResponse();
				if(isWaitingForPin(pinResponse)) {
					enterPin(srv.getSimPin());
				}
				return null;
			}
		});
	}
	
	@Override
	public void stkInit2() throws SMSLibDeviceException, IOException {
		srv.doSynchronized(new SynchronizedWorkflow<Object>() {
			public Object run() throws IOException {
				String vlue = "5FFFFFFF7F"; // TODO document what this value is
				String pinResponse = serialSendReceive("AT+CPIN?");
				if(isWaitingForPin(pinResponse)) {
					serialSendReceive("AT+CPIN="+srv.getSimPin());
				}
				serialSendReceive("AT+STSF=0");
		 		serialSendReceive("AT+STSF=1");
		 		serialSendReceive("AT+STSF=2,\""+vlue+"\",200,1");
		 		serialSendReceive("AT+STSF=2,\""+vlue+"\",200,0");
		 		serialSendReceive("AT+CFUN=1");
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
			String menuId = getMenuId(serialDriver.getLastClearedBuffer());
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
						stkStartNewSession();
						String initResponse = serialSendReceive("AT+STGI=0");
						if (notOk(initResponse)) {
							return StkResponse.ERROR;
						} else {
							String bufferedResponse = serialDriver.getLastClearedBuffer(); 
							while(bufferedResponse.contains("+STIN")) { // FIXME how exactly does this ever break out of the loop?
								try {
									stkRequest(StkRequest.GET_ROOT_MENU);
								} catch (SMSLibDeviceException e) {
									log.warn(e);
									e.printStackTrace(); // FIXME handle this properly
								}
							}
							return parseMenu(initResponse, "0");
						}	
					} else if(request instanceof StkMenuItem) {
						return doMenuRequest((StkMenuItem) request);
					} else if(request.equals(StkValuePrompt.REQUEST)) {
						return handleValuePromptRequest(request, variables);
					} else /*if(request instanceof StkConfirmationPrompt)*/ {
						// 1[confirm],1[dunno, but always there],1[optional it seems]
						String stgrResponse = serialSendReceive("AT+STGR=1,1,1");
						if(stgrResponse.contains("OK")) {
							System.out.println("Buffer content: " + serialDriver.getLastClearedBuffer());
							String stgiResponse = serialSendReceive("AT+STGI=" + extractNumber(serialDriver.getLastClearedBuffer()));
							System.out.println("Buffer content: " + serialDriver.getLastClearedBuffer());
							if(stgiResponse.contains("OK")) {
								stgiResponse = serialSendReceive("AT+STGI=" + extractNumber(serialDriver.getLastClearedBuffer()));
								if(stgiResponse.contains("OK")) {
									if(stgiResponse.contains("Not sent")) {
										return StkConfirmationPromptResponse.ERROR; // FIXME despite appearances, this is not an instance of StkConfirmationPromptResponse.  Should it be?  Otherwise referencing it in this way is misleading. 
										//return StkConfirmationPromptResponse.createError(stgrResponse);
									} else {
										return StkConfirmationPromptResponse.OK;
									}
								}
							} else {
								return StkConfirmationPromptResponse.createError(stgrResponse);
							}
						}
						return StkConfirmationPromptResponse.createError(stgrResponse);
					} /*else return null;	*/
				} catch(StkParseException ex) {
					throw new SMSLibDeviceException(ex);
				}
			}
		});
 	}

	private StkResponse handleValuePromptRequest(StkRequest request, String... variables) throws IOException, StkParseException {
		// 3[mode=input],1[not sure],1[this seems to be optional]
		String response = serialSendReceive("AT+STGR=3,1,1");
		// Suffix variable with "EOF"/"End of file"/"Ctrl-z"
		serialDriver.send(variables[0] + (char)0x1A);
		sleepWithoutInterruption(DELAY_STGR);
		String menuId = getMenuId(serialDriver.getLastClearedBuffer());
		String next = serialSendReceive("AT+STGI=" + menuId);
		return parseStkResponse(next, menuId);
	}
	
	private StkResponse parseStkResponse(String serialResponse, String menuId) {
		if(isValuePrompt(serialResponse)) {
			return new StkValuePrompt();
		} else if(isConfirmationPrompt(serialResponse)) {
			return new StkConfirmationPrompt();
		} else {
			return parseMenu(serialResponse, menuId);
		}
	}
	
	static boolean isConfirmationPrompt(String serialResponse) {
		return CONFIRMATION_PROMPT_REGEX.matcher(serialResponse).matches();
	}

	static boolean isValuePrompt(String serialResponse) {
		return serialResponse.matches(VALUE_PROMPT_REGEX);
	}

	private StkResponse doMenuRequest(StkMenuItem request) throws IOException, SMSLibDeviceException, StkParseException {
		String initialResponse = serialSendReceive("AT+STGR=" + request.getMenuId() + ",1," + request.getMenuItemId());
		if(initialResponse.contains("OK")) {
			String newMenuId = extractNumber(serialDriver.getLastClearedBuffer());
			String secondaryResponse = serialSendReceive("AT+STGI=" + newMenuId);
			return parseStkResponse(secondaryResponse, newMenuId);
		} else throw new SMSLibDeviceException("Unexpected response to AT+STGR: " + initialResponse);
	}

	/** Extract the 1st set of digits from the supplied string. */
	private static String extractNumber(String s) throws StkParseException {
		Matcher m = Pattern.compile("\\d+").matcher(s);
		if(m.find()) {
			return m.group();
		} else {
			throw new StkParseException();
		}
	}

	private StkResponse parseMenu(String serialSendReceive, String menuId) {
		String title = parseMenuTitle(serialSendReceive);
		List<StkMenuItem> menuItems = parseMenuItems(serialSendReceive, menuId);
		return new StkMenu(title, menuItems.toArray());
	}
	
	 private String parseMenuTitle(String serialSendReceive) {
		  Matcher matcher = Pattern.compile("\\+STGI: ((([\\d])+,)+)?(\\\"[\\w -.]+\\\")?").matcher(serialSendReceive);
		  //Test to find out if the string is an inputRequirement
		  String[] splitSerialSendReceive = serialSendReceive.split("\\+STGI");
		  if (splitSerialSendReceive.length > 2){
		   if (matcher.find()){
		    String uncleanTitle = matcher.group();
		    uncleanTitle = uncleanTitle.replace("+STGI: ", "");
		    uncleanTitle = uncleanTitle.replace("\"", "");
		    uncleanTitle = uncleanTitle.replaceAll(regexNumberComma, "");
		    return uncleanTitle;
		   } else {
		    //TODO
		    return "ERROR TITLE";
		   }
		  }
		   else {
		         return "Item"; 
		  }
	}

	private List<StkMenuItem> parseMenuItems(String serialSendReceive, String menuId) {
		ArrayList<StkMenuItem> items = new ArrayList<StkMenuItem>();
		String uncleanTitle = "";
		String menuItemId = "";

		Matcher matcher = Pattern.compile("\\+STGI: ((([\\d])+,)(([\\d])+,)+)+\\\"([\\w](.)*)+\\\"").matcher(serialSendReceive);
		while (matcher.find()) {
			uncleanTitle = matcher.group();
			// retrieve menuItemId
			Matcher matcherMenuItemId = Pattern.compile(regexNumber).matcher(uncleanTitle);
			if (matcherMenuItemId.find() && !matcherMenuItemId.group().equals("0")){
				menuItemId = matcherMenuItemId.group();
			}
			// clean the title
			uncleanTitle = uncleanTitle.replace("+STGI: ", "");
			uncleanTitle = uncleanTitle.replace("\"", "");
			uncleanTitle = uncleanTitle.replaceAll(regexNumberComma, "");

			items.add(new StkMenuItem(uncleanTitle, menuId, menuItemId));
		}
		return items;
	}

	private String getMenuId(String response) throws StkParseException {
		Matcher matcher = Pattern.compile("\\d+").matcher(response);
		if (matcher.find()) {
			return matcher.group();
		} else {
			throw new StkParseException();
		}
	}

	private boolean notOk(String initResponse) {
		return initResponse.contains("ERROR");
	}
}
