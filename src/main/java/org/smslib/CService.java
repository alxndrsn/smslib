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

package org.smslib;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.TooManyListenersException;

import serial.*;


import org.apache.log4j.Logger;
import org.smslib.sms.SmsMessageEncoding;
import org.smslib.util.GsmAlphabet;
import org.smslib.util.HexUtils;
import org.smslib.util.TpduUtils;

/**
 * This is the main SMSLib service class.
 */
public class CService {
	/** Dummy synchronization object. */
	private final Object _SYNC_ = new Object();

	/** Holds values representing the modem protocol used. */
	public enum Protocol {
		/** PDU protocol. */
		PDU,
		/** TEXT protocol. */
		TEXT;
	}

	/**
	 * Holds values representing receive mode.
	 * 
	 * @see CService#setReceiveMode(int)
	 * @see CService#getReceiveMode()
	 */
	public static class ReceiveMode {
		/** Synchronous reading. */
		public static final int SYNC = 0;
		/** Asynchronous reading - CMTI indications. */
		public static final int ASYNC_CNMI = 1;
		/** Asynchronous reading - polling. */
		public static final int ASYNC_POLL = 2;
	}

	private static final int DISCONNECT_TIMEOUT = 10 * 1000;

	private int keepAliveInterval = 30 * 1000;

	private int asyncPollInterval = 10 * 1000;

	private MessageClass asyncRecvClass = MessageClass.ALL;

	private int retriesNoResponse = 5;

	private int delayNoResponse = 5000;

	/** number of times to retry an AT command before giving up when the response is a CMS error */
	private int retriesCmsErrors = 5;

	private int delayCmsErrors = 5000;

	private final Logger log;

	private static final String VALUE_NOT_REPORTED = "* N/A *";
	/** {@link TimeZone} for UTC.  This is used to standardise times. */
	private static final TimeZone TIMEZONE_UTC = TimeZone.getTimeZone("GMT");
	/** The SMSC number for this device.  If set <code>null</code>, a blank value will be used.  This usually allows a device to determine
	 * the SMSC number for itself. */
	private String smscNumber;
	/** The PIN number to set for this device, or <code>null</code> if none has been supplied. */
	private String simPin;
	/** The 2nd PIN for the SIM.  This usually unlocks advanced SIM features. */
	private String simPin2;
	/** if <code>true</code>, {@link #connect()} will throw an exception if asked for SIM PIN2 and {@link #simPin2} is null. */
	boolean throwExceptionOnMissingPin2;

	private int receiveMode;

	private Protocol protocol;

	private AbstractATHandler atHandler;

	private CNewMsgMonitor newMsgMonitor;

	public CSerialDriver serialDriver;

	private volatile boolean connected;

	private final CDeviceInfo deviceInfo;

	private CKeepAliveThread keepAliveThread;

	private CReceiveThread receiveThread;

	private ISmsMessageListener messageHandler;

	private ICallListener callHandler;

	private int outMpRefNo;

	/** List of incomplete multipart message parts. */
	private final LinkedList<LinkedList<CIncomingMessage>> mpMsgList = new LinkedList<LinkedList<CIncomingMessage>>();

	/** Constructor used for Unit Tests. */
	CService(AbstractATHandler atHanndler) {
		log = Logger.getLogger(CService.class);
		this.atHandler = atHanndler;
		this.deviceInfo = new CDeviceInfo();
	}
	
	/**
	 * CService constructor.
	 * 
	 * @param port
	 *            The comm port to use (i.e. COM1, /dev/ttyS1 etc).
	 * @param baud
	 *            The baud rate. 57600 is a good number to start with.
	 * @param gsmDeviceManufacturer
	 *            The manufacturer of the modem (i.e. Wavecom, Nokia, Siemens, etc).
	 * @param gsmDeviceModel
	 *            The model (i.e. M1306B, 6310i, etc).
	 * @param catHandlerAlias TODO
	 */
	public CService(String port, int baud, String gsmDeviceManufacturer, String gsmDeviceModel, String catHandlerAlias) {
		smscNumber = "";

		serialDriver = new CSerialDriver(port, baud, this);
		deviceInfo = new CDeviceInfo();
		newMsgMonitor = new CNewMsgMonitor();

		log = Logger.getLogger(CService.class);
		log.info("Using port: " + port + " @ " + baud + " baud.");
		
		try {
			atHandler = AbstractATHandler.load(serialDriver, log, this, gsmDeviceManufacturer, gsmDeviceModel, catHandlerAlias);
			log.info("Using " + atHandler.getClass().getName());
		} catch (Exception ex) {
			log.fatal("CANNOT INITIALIZE HANDLER (" + ex.getMessage() + ")");
			ex.printStackTrace();
		}

		protocol = atHandler.getProtocol();
		
		receiveMode = ReceiveMode.SYNC;

		outMpRefNo = Math.abs(new Random().nextInt(65536));
	}

	/**
	 * Return the status of the connection.
	 * <p>
	 * <strong>Warning</strong>: The method return the "theoretical" status of the connection, without testing the actual connection at the time of the call.
	 * 
	 * @return True if the GSM device is connected.
	 * @see CService#connect()
	 * @see CService#disconnect()
	 */
	public boolean isConnected() {
		return connected;
	}

	/**
	 * Returns the DeviceInfo class.
	 * 
	 * @see CDeviceInfo
	 */
	public CDeviceInfo getDeviceInfo() {
		return deviceInfo;
	}

	/**
	 * Sets the SMSC number. Needed in rare cases - normally, you should <strong>not</strong> set the SMSC number yourself and let the GSM device get it from its SIM.
	 * 
	 * @param smscNumber
	 *            The SMSC number (international format).
	 * @see CService#getSmscNumber()
	 */
	public void setSmscNumber(String smscNumber) {
		this.smscNumber = smscNumber;
	}

	/**
	 * Returns the SMSC number previously set with setSmscNumber() call.
	 * 
	 * @return The SMSC number.
	 * @see CService#setSmscNumber(String)
	 */
	public String getSmscNumber() {
		return smscNumber;
	}

	/**
	 * Sets the SIM PIN.
	 * 
	 * @param simPin
	 *            The SIM pin code.
	 * @see CService#getSimPin()
	 */
	public void setSimPin(String simPin) 	{
		this.simPin = simPin;
	}

	/**
	 * Sets the SIM Pin 2. Some GSM modems may require this to unlock full functionality.
	 * 
	 * @param simPin
	 *            The SIM PIN #2 code.
	 * @see CService#getSimPin2()
	 */
	public void setSimPin2(String simPin) {
		this.simPin2 = simPin;
	}

	/**
	 * Returns the SIM PIN previously set with setSimPin().
	 * 
	 * @return The SIM PIN code.
	 * @see CService#setSimPin(String)
	 */
	public String getSimPin() {
		return simPin;
	}

	/**
	 * Returns the SIM PIN #2 previously set with setSimPin2().
	 * 
	 * @return The SIM PIN #2 code.
	 * @see CService#setSimPin2(String)
	 */
	public String getSimPin2() {
		return simPin2;
	}

	/**
	 * Sets the message handler for ASYNC mode. The handler is called automatically from SMSLib when a message is received. This handler is valid only for Asynchronous operation mode - in other modes, it is not used.
	 * 
	 * @param messageHandler
	 *            The message handler routine - must comply with ISmsMessageListener interface.
	 * @see CService#getMessageHandler()
	 */
	public void setMessageHandler(ISmsMessageListener messageHandler) {
		this.messageHandler = messageHandler;
	}

	/**
	 * Returns the message handler (if any).
	 * 
	 * @return The message handler.
	 * @see CService#setMessageHandler(ISmsMessageListener)
	 */
	public ISmsMessageListener getMessageHandler() {
		return messageHandler;
	}

	/**
	 * Sets the call handler. Works in ALL modes. The handler is called automatically once SMSLib receives an incoming call.
	 * 
	 * @param callHandler
	 *            The call handler routine - must comply with ICallListener interface.
	 * @see CService#getCallHandler()
	 */
	public void setCallHandler(ICallListener callHandler) {
		this.callHandler = callHandler;
	}

	/**
	 * Returns the call handler (if any).
	 * 
	 * @return The call handler.
	 * @see CService#setCallHandler(ICallListener)
	 */
	public ICallListener getCallHandler() {
		return callHandler;
	}

	/**
	 * Sets the Async Poll Interval (in seconds) - is every how many seconds will SMSLib poll the GSM modem for new messages.
	 * 
	 * @param secs
	 *            The interval in seconds.
	 * @see CService#getAsyncPollInterval()
	 * @see CService#setAsyncRecvClass(int)
	 * @see CService#getAsyncRecvClass()
	 */
	public void setAsyncPollInterval(int secs) {
		this.asyncPollInterval = secs * 1000;
	}

	/**
	 * Returns the Async Poll Interval, in seconds.
	 * 
	 * @return The Poll Interval in seconds.
	 * @see CService#setAsyncPollInterval(int)
	 * @see CService#setAsyncRecvClass(int)
	 * @see CService#getAsyncRecvClass()
	 */
	public int getAsyncPollInterval() {
		return (asyncPollInterval / 1000);
	}

	public void setAsyncRecvClass(MessageClass msgClass) {
		asyncRecvClass = msgClass;
	}

	public MessageClass getAsyncRecvClass() {
		return asyncRecvClass;
	}

	/**
	 * Sets the Keep-Alive Interval - every how many seconds the Keep-Alive thread will run and send a dummy OK command to the GSM modem. This is used to keep the serial port alive and prevent it from timing out. The interval is, by default, set to 30 seconds which should be enough for all modems.
	 * 
	 * @param secs
	 *            The Keep-Alive Interval in seconds.
	 * @see CService#getKeepAliveInterval()
	 */
	public void setKeepAliveInterval(int secs) {
		this.keepAliveInterval = secs * 1000;
	}

	/**
	 * Returns the Keep-Alive Interval, in seconds.
	 * 
	 * @return The Keep-Alive Interval in seconds.
	 * @see CService#setKeepAliveInterval(int)
	 */
	public int getKeepAliveInterval() {
		return keepAliveInterval / 1000;
	}

	/**
	 * Sets the number of retries that SMSLib performs during dispatch of a message, if it fails to get a response from the modem within the timeout period.
	 * <p>
	 * After the number of retries complete and the message is not sent, SMSLib treats it as undeliverable.
	 * <p>
	 * The default values should be ok in most cases.
	 * 
	 * @param retries
	 *            The number of retries.
	 * @see CService#getRetriesNoResponse()
	 * @see CService#setDelayNoResponse(int)
	 * @see CService#getDelayNoResponse()
	 */
	public void setRetriesNoResponse(int retries) {
		this.retriesNoResponse = retries;
	}

	/**
	 * Returns the current number of retries.
	 * 
	 * @return The number of retries.
	 * @see CService#setRetriesNoResponse(int)
	 */
	public int getRetriesNoResponse() {
		return retriesNoResponse;
	}

	/**
	 * Sets the delay between consecutive attemps for dispatching a message.
	 * 
	 * @param delay
	 *            The delay in millisecs.
	 * @see CService#getDelayNoResponse()
	 * @see CService#setRetriesNoResponse(int)
	 * @see CService#getRetriesNoResponse()
	 */
	public void setDelayNoResponse(int delay) {
		this.delayNoResponse = delay * 1000;
	}

	/**
	 * Gets the delay between consecutive attemps for dispatching a message.
	 * 
	 * @return delay The delay in millisecs.
	 * @see CService#getDelayNoResponse()
	 * @see CService#setRetriesNoResponse(int)
	 * @see CService#getRetriesNoResponse()
	 */
	public int getDelayNoResponse() {
		return delayNoResponse;
	}

	public void setRetriesCmsErrors(int retries) {
		this.retriesCmsErrors = retries;
	}

	/** This method only appears to be used in {@link AbstractATHandler#sendMessage(int, String, String, String)}
	 * @return number of times to retry an AT command before giving up when the response is a CMS error */
	public int getRetriesCmsErrors() {
		return retriesCmsErrors;
	}

	public void setDelayCmsErrors(int delay) {
		this.delayCmsErrors = delay * 1000;
	}

	public int getDelayCmsErrors() {
		return delayCmsErrors;
	}

	/**
	 * Sets the receive mode.
	 * 
	 * @param receiveMode
	 *            The receive mode.
	 * @see CService.ReceiveMode
	 */
	public void setReceiveMode(int receiveMode) throws IOException {
		synchronized (_SYNC_) {
			this.receiveMode = receiveMode;
			if (connected) {
				if (receiveMode == ReceiveMode.ASYNC_CNMI) {
					if (!atHandler.enableIndications()) {
						log.warn("Could not enable CMTI indications, continuing without them...");
					}
				} else {
					if (!atHandler.disableIndications()) {
						log.warn("Could not disable CMTI indications, continuing without them...");
					}
				}
			}
		}
	}

	/**
	 * Returns the Receive Mode.
	 * 
	 * @return The Receive Mode.
	 * @see CService.ReceiveMode
	 */
	public int getReceiveMode() {
		return receiveMode;
	}

	/**
	 * Sets the protocol to be used.
	 * <p>The default protocol is PDU. If you want to change it, you must call this method after constructing the
	 * CService object and before connecting. Otherwise, you will get an exception.
	 * @param protocol The protocol to be used.
	 */
	public void setProtocol(CService.Protocol protocol) {
		if (isConnected()) throw new RuntimeException("Cannot change protocol while connected!");
		else this.protocol = protocol;
	}

	/**
	 * Returns the message protocol in use by this.
	 * @return The protocol use.
	 */
	public CService.Protocol getProtocol() {
		return protocol;
	}

	/**
	 * Sets the storage locations to be read by SMSLib.
	 * <p>
	 * Normally, SMSLib tries to read the available storage locations reported by the modem itself. Sometimes, the modem does not report all storage locations, so you can use this method to define yours, without messing with main SMSLib code.
	 * 
	 * @param loc	The storage locations (i.e. a string similar to "SMME", which is specific to each modem)
	 */
	public void setStorageLocations(String loc) {
		atHandler.setStorageLocations(loc);
	}

	/**
	 * Connects to the GSM modem.
	 * <p>
	 * The connect() function should be called before any operations. Its purpose is to open the serial link, check for modem existence, initialize modem, start background threads and prepare for subsequent operations.
	 * @throws SMSLibDeviceException If there was a problem initiating the connection to the device
	 * @throws IOException 
	 * @throws TooManyListenersException 
	 * @throws PortInUseException 
	 * @throws NoSuchPortException 
	 * @throws DebugException 
	 * @throws UnsupportedCommOperationException 
	 * 
	 * @see #disconnect()
	 */
	// TODO this now throws a lot more exceptions!  The JAVADOC needs updating to reflect this.  Also, it would be wise
	// to check that throwing this many different exceptions from one method is actually reasonable behaviour -  It seems
	// logical, but also looks ridiculous.
	public void connect() throws SMSLibDeviceException, IOException, TooManyListenersException, PortInUseException, NoSuchPortException, DebugException, UnsupportedCommOperationException {
		synchronized (_SYNC_) {
			if (isConnected()) throw new AlreadyConnectedException();
			else try {
				serialDriver.open();
				connected = true;
				atHandler.sync();
				serialDriver.emptyBuffer();
				atHandler.reset();
				serialDriver.setNewMsgMonitor(newMsgMonitor);
				if (atHandler.isAlive()) {
					
					// check if the device is asking for PIN or PUK
					String pinResponse = atHandler.getPinResponse();
					if(atHandler.isWaitingForPin(pinResponse)) {
						if (getSimPin() == null) throw new NoPinException();
						if (!atHandler.enterPin(getSimPin())) throw new InvalidPinException();
						
						pinResponse = atHandler.getPinResponse();
					}

					// If we're still waiting for the PIN, we made need the 2nd PIN number
					if(atHandler.isWaitingForPin2(pinResponse)) {
						String pin2 = getSimPin2();
						if (pin2 == null) {
							if(throwExceptionOnMissingPin2) {
								throw new NoPin2Exception();
							}
						} else {
							if (!atHandler.enterPin(pin2)) throw new InvalidPin2Exception();
						}

						pinResponse = atHandler.getPinResponse();
					}
					
					if(atHandler.isWaitingForPuk(pinResponse)) {
						// TODO is there a more suitable exception?  If not, one should be created, as we ultimately want to catch this and allow the user to enter the PUK
						// FIXME or is it PUK2?
						throw new SMSLibDeviceException("PUK Required!");
					}
					
					atHandler.init();
					atHandler.echoOff();
					waitForNetworkRegistration();
					atHandler.setVerboseErrors();
					if (atHandler.storageLocations.length() == 0) atHandler.getStorageLocations();
					log.info("MEM: Storage Locations Found: " + atHandler.storageLocations);
					switch (protocol) {
						case PDU:
							log.info("PROT: Using PDU protocol.");
							if (!atHandler.setPduMode()) throw new NoPduSupportException();
							break;
						case TEXT:
							log.info("PROT: Using TEXT protocol.");
							if (!atHandler.setTextMode()) throw new NoTextSupportException();
							break;
						default: throw new UnrecognizedHandlerProtocolException(protocol);
					}
					setReceiveMode(receiveMode);
					refreshDeviceInfo();

					receiveThread = new CReceiveThread();
					receiveThread.start();
					keepAliveThread = new CKeepAliveThread();
					keepAliveThread.start();
				} else {
					throw new NotConnectedException("GSM device is not responding.");
				}
			} catch(SMSLibDeviceException ex) {
				try { disconnect(); } catch (Exception _) {}
				throw ex;
			} catch(IOException ex) {
				try { disconnect(); } catch (Exception _) {}
				throw ex;
			} catch (NoSuchPortException ex) {
				try { disconnect(); } catch (Exception _) {}
				throw ex;
			} catch (PortInUseException ex) {
				try { disconnect(); } catch (Exception _) {}
				throw ex;
			} catch (UnsupportedCommOperationException ex) {
				try { disconnect(); } catch (Exception _) {}
				throw ex;
			} catch (TooManyListenersException ex) {
				try { disconnect(); } catch (Exception _) {}
				throw ex;
			} catch (DebugException ex) {
				try { disconnect(); } catch (Exception _) {}
				throw ex;
			}
		}
	}

	/**
	 * Disconnects from the GSM modem.
	 * <p>
	 * This should be the last function called. Closes serial connection, shuts down background threads and performs clean-up.
	 * <p>
	 * <strong>Notes</strong>
	 * <ul>
	 * <li>Do not connect and disconnect continuously - at least if you can avoid it. It takes time and resources. Connect once and stay connected.</li>
	 * </ul>
	 * 
	 * @see CService#connect()
	 * 
	 * AGA Modifications (18/04/2008) Modified method so that it will always try to disconnect, even if
	 *   connected is set false, and if receiveThread,keepAliveThread,serialDriver are null, it will just
	 *   ignore them.
	 */
	public void disconnect() throws NotConnectedException {
		if(!connected) log.warn("Not connected.  Will attempt disconnection anyway.");
		if(receiveThread == null) log.warn("ReceiveThread == null");
		if(keepAliveThread == null) log.warn("KeepAliveThread == null");
		if(serialDriver == null) log.warn("SerialDriver == null");

		final int wait = 100;
		int timeout = DISCONNECT_TIMEOUT;
		if(receiveThread != null) receiveThread.killMe();
		if(keepAliveThread != null) keepAliveThread.killMe();
		while (timeout > 0 &&
				((receiveThread!=null && !receiveThread.isStopped()) ||
						(keepAliveThread!=null && !keepAliveThread.isStopped()))) {
			timeout -= sleep_ignoreInterrupts(wait);
		}

		if(serialDriver!=null) serialDriver.killMe();
		
		// If the receive thread did not die before the timeout above, we should
		// send it a "join" to kill it off.
		if(receiveThread!=null && !receiveThread.isStopped()) {
			receiveThread.interrupt();
			try { receiveThread.join(); } catch(InterruptedException ex) {}
		}

		
		// If the keepAlive thread did not die before the timeout above, we
		// should send it a "join" to kill it off.
		if(keepAliveThread!=null && !keepAliveThread.isStopped()) {
			keepAliveThread.interrupt();
			// We no longer join() on the keepAliveThread, as this has a tendency to block
			// indefinitely if the device has been disconnected unexpectedly.
			//try { keepAliveThread.join(); } catch(InterruptedException ex) {}
		}

		receiveThread = null;
		keepAliveThread = null;
		if(serialDriver != null) serialDriver.close();
		connected = false;
	}

	/**
	 * Reads all SMS messages from the GSM modem.
	 * 
	 * @param messageList
	 *            The list to be populated with messages.
	 * @param messageClass
	 *            The message class of the messages to read.
	 * @throws SMSLibDeviceException 
	 * @see CService#readMessages(LinkedList, int, int)
	 * @see CIncomingMessage
	 * @see CService.MessageClass
	 * @see CService#sendMessage(COutgoingMessage)
	 */
	public void readMessages(LinkedList<CIncomingMessage> messageList, MessageClass messageClass) throws IOException, SMSLibDeviceException {
		switch (protocol) {
			case PDU:
				readMessages_PDU(messageList, messageClass);
				break;
			case TEXT:
				readMessages_TEXT(messageList, messageClass);
				break;
		}
	}
	
	/**
	 * Gets the next line from this {@link BufferedReader} which contains information.  This
	 * either comes in the form of an End-of-Buffer, signified by <code>null</code>, or a line
	 * who's trimmed form has a positive length.
	 * @param reader Buffered reader from which to read lines.
	 * @return A string of length 1 or more, or <code>null</code>.
	 * @throws IOException 
	 */
	static String getNextUsefulLine(BufferedReader reader) throws IOException {
		String line;
		while((line = reader.readLine()) != null
				&& (line = line.trim()).length() == 0) {
			/* Keep reading until we find a line containing info, or get to the end of the reader. */
		}
		return line;
	}
	
	/**
	 * Attempts to create a new message from a PDU, memoryLocation and memoryIndex.  If the message
	 * can be created, it is added to the supplied list of messages.
	 * @param messageList List to append the new message object to.
	 * @param pdu The hex-encoded PDU to decode the message from.  This PDU should be as-received on a device.
	 * @param memoryLocation 
	 * @param memIndex The memory index of the PDU, read using {@link #getMemIndex(String)}.
	 * @throws MessageDecodeException 
	 * @throws NullPointerException if the supplied PDU is <code>null</code>.
	 */
	private void createMessage(LinkedList<CIncomingMessage> messageList, String pdu, int memoryLocation, int memIndex) throws MessageDecodeException {
		if (isIncomingMessage(pdu)) {
			log.info("PDU appears to be an incoming message.  Processing accordingly.");
			CIncomingMessage msg = new CIncomingMessage(pdu, memIndex, atHandler.storageLocations.substring((memoryLocation * 2), (memoryLocation * 2) + 2));
			// Check if this message is multipart.
			if (!msg.isMultipart()) {
				// This message has only one part, so add it to the list for immediate processing
				log.info("Single part message; adding to message list.");
				messageList.add(msg);
				deviceInfo.getStatistics().incTotalIn();
			} else {
				// This message has multiple parts, so add it to the list of concat messages
				log.info("Concatenated message part.  Adding to concat parts list.");
				addConcatenatedMessagePart(msg);
			}
		} else if (isStatusReportMessage(pdu)) {
			log.info("PDU appears to be a status report.  Processing accordingly.");
			messageList.add(new CStatusReportMessage(pdu, memIndex, atHandler.storageLocations.substring((memoryLocation * 2), (memoryLocation * 2) + 2)));
			deviceInfo.getStatistics().incTotalIn();
		} else {
			log.info("Unrecognized message type; ignoring.");
		}
	}

	private void readMessages_PDU(LinkedList<CIncomingMessage> messageList, MessageClass messageClass) throws IOException, SMSLibDeviceException
	{
		synchronized (_SYNC_) {
			if (!isConnected()) {
				throw new NotConnectedException();
			} else {
				atHandler.switchToCmdMode();
				log.debug("Checking storage locations: " + atHandler.storageLocations);
				for (int ml = 0; ml < (atHandler.storageLocations.length() / 2); ml++) {
					String memoryLocation = atHandler.storageLocations.substring((ml * 2), (ml * 2) + 2);
					if (atHandler.setMemoryLocation(memoryLocation)) {
						String response = atHandler.listMessages(messageClass);
						response = response.replaceAll("\\s+OK\\s+", "\nOK\n");
						BufferedReader reader = null;
						try {
							reader = new BufferedReader(new StringReader(response));
							String line;
							// Read until the end of the response.  This is indicated by either a
							// null line, or a line reading "OK".
							while ((line = getNextUsefulLine(reader)) != null
									&& !line.equalsIgnoreCase("OK")) {
								
								// The first line appears to contain the memory location of this message, so extract it
								int memIndex = getMemIndex(line);
								
								// The second line should contain the PDU
								String pdu = getNextUsefulLine(reader);
								if(pdu == null) {
									log.warn("Null PDU found.  Skipping.");
								} else {
									log.info("Read pdu: " + pdu);
									try {
										createMessage(messageList, pdu, ml, memIndex);
									} catch (Throwable t) {
										log.error("Problem processing PDU: " + pdu, t);
									}
								}
							}
						} finally {
							/// In ALL cases, we should close the reader.
							if(reader != null) reader.close();
						}
					}
				}
			}
		}
		// Check to see if we've completed any multipart messages.
		checkMpMsgList(messageList);
	}
	
	/**
	 * Adds a concatenated message part to the internal list of concatenated messages.  If
	 * other parts of this message already exist, we will add this message to those ones.
	 * If this part is deemed a duplicate, it will not replace the old part we have stored.
	 * @param messagePart message part loaded from a single PDU.  This message part MUST NOT be a single-part message.
	 */
	private void addConcatenatedMessagePart(CIncomingMessage messagePart) {
		log.debug("IN-DTLS: MI:" + messagePart.getMemIndex() + " REF:" + messagePart.getMpRefNo() + " MAX:" + messagePart.getMpMaxNo() + " SEQ:" + messagePart.getMpSeqNo());
		// Check to see if this message is part of a multipart message that we already have a
		// bit of.  If this is the case, we'll join it up with the other bits.  If it is a
		// duplicate then TODO we should probably delete it from the device.  If it is not related
		// to any other parts we already have, we just add it to our parts list.
		for (int k = 0; k < mpMsgList.size(); k++) {
			LinkedList<CIncomingMessage> tmpList = mpMsgList.get(k);
			CIncomingMessage listMsg =  tmpList.get(0);
			
			// We can tell if a message is part of another message by checking the reference number
			// and the sender number are the same.
			if (listMsg.getMpRefNo() == messagePart.getMpRefNo()
					&& listMsg.getOriginator().equals(messagePart.getOriginator())) {
				// We've found the message that this is a part of.  Check if it's
				// a duplicate.
				for (int l = 0; l < tmpList.size(); l++) {
					listMsg = tmpList.get(l);
					if (listMsg.getMpSeqNo() == messagePart.getMpSeqNo()) {
						// Our message part is a duplicate.  Log this fact, and quit the method.
						log.info("Duplicate message part; ignoring.");
						return;
					}
				}
				// This part was not a duplicate, so add it to the parts list, and stop processing.
				tmpList.add(messagePart);
				return;
			}
		}
		// We didn't find a message that this was a part of.  Create a new parts list,
		// including this part, and add it to the concat parts list.
		LinkedList<CIncomingMessage> tmpList = new LinkedList<CIncomingMessage>();
		tmpList.add(messagePart);
		mpMsgList.add(tmpList);
	}
	
	/**
	 * Gets the memory index of a message from the relevant line of message list response.
	 * TODO show an example line
	 * @param responseLine
	 * @return
	 */
	static final int getMemIndex(String responseLine) {
		int i = responseLine.indexOf(':');
		int j = responseLine.indexOf(',');
		return Integer.parseInt(responseLine.substring(i + 1, j).trim());
	}

	private void readMessages_TEXT(LinkedList<CIncomingMessage> messageList, MessageClass messageClass) throws IOException, SMSLibDeviceException
	{
		int i, j, memIndex;
		byte[] bytes;
		String response, line, msgText, originator, dateStr, refNo;
		BufferedReader reader;
		StringTokenizer tokens;
		CIncomingMessage msg;

		synchronized (_SYNC_) {
			if (isConnected()) {
				atHandler.switchToCmdMode();
				for (int ml = 0; ml < (atHandler.storageLocations.length() / 2); ml++) {
					if (atHandler.setMemoryLocation(atHandler.storageLocations.substring((ml * 2), (ml * 2) + 2))) {
						response = atHandler.listMessages(messageClass);
						response = response.replaceAll("\\s+OK\\s+", "\nOK");
						reader = new BufferedReader(new StringReader(response));
						for (;;) {
							line = reader.readLine().trim();
							if (line == null) break;
							line = line.trim();
							if (line.length() > 0) break;
						}
						while (true) {
							if (line == null) break;
							line = line.trim();
							if (line.length() <= 0 || line.equalsIgnoreCase("OK")) break;
							i = line.indexOf(':');
							j = line.indexOf(',');
							memIndex = Integer.parseInt(line.substring(i + 1, j).trim());
							tokens = new StringTokenizer(line, ",");
							tokens.nextToken();
							tokens.nextToken();
							if (Character.isDigit(tokens.nextToken().trim().charAt(0))) {
								Calendar cal1 = Calendar.getInstance(TIMEZONE_UTC);
								Calendar cal2 = Calendar.getInstance(TIMEZONE_UTC);
								
								line = line.replaceAll(",,", ", ,");
								tokens = new StringTokenizer(line, ",");
								tokens.nextToken();
								tokens.nextToken();
								tokens.nextToken();
								refNo = tokens.nextToken();
								tokens.nextToken();
								dateStr = tokens.nextToken().replaceAll("\"", "");
								cal1.set(Calendar.YEAR, 2000 + Integer.parseInt(dateStr.substring(0, 2)));
								cal1.set(Calendar.MONTH, Integer.parseInt(dateStr.substring(3, 5)) - 1);
								cal1.set(Calendar.DAY_OF_MONTH, Integer.parseInt(dateStr.substring(6, 8)));
								dateStr = tokens.nextToken().replaceAll("\"", "");
								cal1.set(Calendar.HOUR_OF_DAY, Integer.parseInt(dateStr.substring(0, 2)));
								cal1.set(Calendar.MINUTE, Integer.parseInt(dateStr.substring(3, 5)));
								cal1.set(Calendar.SECOND, Integer.parseInt(dateStr.substring(6, 8)));
								dateStr = tokens.nextToken().replaceAll("\"", "");
								cal2.set(Calendar.YEAR, 2000 + Integer.parseInt(dateStr.substring(0, 2)));
								cal2.set(Calendar.MONTH, Integer.parseInt(dateStr.substring(3, 5)) - 1);
								cal2.set(Calendar.DAY_OF_MONTH, Integer.parseInt(dateStr.substring(6, 8)));
								dateStr = tokens.nextToken().replaceAll("\"", "");
								cal2.set(Calendar.HOUR_OF_DAY, Integer.parseInt(dateStr.substring(0, 2)));
								cal2.set(Calendar.MINUTE, Integer.parseInt(dateStr.substring(3, 5)));
								cal2.set(Calendar.SECOND, Integer.parseInt(dateStr.substring(6, 8)));

								msg = new CStatusReportMessage(Integer.parseInt(refNo), memIndex, atHandler.storageLocations.substring((ml * 2), (ml * 2) + 2), cal1.getTimeInMillis(), cal2.getTimeInMillis());
								log.debug("IN-DTLS: MI:" + msg.getMemIndex());
								messageList.add(msg);
								deviceInfo.getStatistics().incTotalIn();
							} else {
								Calendar cal1 = Calendar.getInstance(TIMEZONE_UTC);
								
								line = line.replaceAll(",,", ", ,");
								tokens = new StringTokenizer(line, ",");
								tokens.nextToken();
								tokens.nextToken();
								originator = tokens.nextToken().replaceAll("\"", "");
								tokens.nextToken();
								dateStr = tokens.nextToken().replaceAll("\"", "");
								cal1.set(Calendar.YEAR, 2000 + Integer.parseInt(dateStr.substring(0, 2)));
								cal1.set(Calendar.MONTH, Integer.parseInt(dateStr.substring(3, 5)) - 1);
								cal1.set(Calendar.DAY_OF_MONTH, Integer.parseInt(dateStr.substring(6, 8)));
								dateStr = tokens.nextToken().replaceAll("\"", "");
								cal1.set(Calendar.HOUR_OF_DAY, Integer.parseInt(dateStr.substring(0, 2)));
								cal1.set(Calendar.MINUTE, Integer.parseInt(dateStr.substring(3, 5)));
								cal1.set(Calendar.SECOND, Integer.parseInt(dateStr.substring(6, 8)));
								msgText = reader.readLine().trim();
								
								// FIXME according to bjdw, "only need to convert if the modem is in hex mode, not gsm mode."  however, we only get here in TEXT mode (rather than PDU mode), so it's hard to say what's meant by that comment
								if(true) {
									bytes = new byte[msgText.length() / 2];
									j = 0;
									for (i = 0; i < msgText.length(); i += 2)
									{
										bytes[j] = Byte.parseByte(msgText.substring(i, i + 2), 16);
										j++;
									}
									msgText = GsmAlphabet.bytesToString(bytes);
									// TODO could replace this whole block with:
									//	msgText = GsmAlphabet.bytesToString(HexUtils.decode(msgText));
									// N.B. should confirm that is exactly what it's doing before replacing!
								}
								
								msg = new CIncomingMessage(cal1.getTimeInMillis(), originator, msgText, memIndex, atHandler.storageLocations.substring((ml * 2), (ml * 2) + 2));
								log.debug("IN-DTLS: MI:" + msg.getMemIndex());
								messageList.add(msg);
								deviceInfo.getStatistics().incTotalIn();
							}
							line = reader.readLine().trim();
							while (line.length() == 0)
								line = reader.readLine().trim();
						}
						reader.close();
					}
				}
			}
			else throw new NotConnectedException();
		}
	}

	/**
	 * Check if we have any completed messages in our list of multipart message parts.  If
	 * we <em>do</em> have any completed messages, we should rebuild them and add them to
	 * the supplied message list.
	 * @param messageList List to insert all received messages into.
	 */
	private void checkMpMsgList(LinkedList<CIncomingMessage> messageList) {
		CIncomingMessage mpMsg = null;
		log.debug("CheckMpMsgList(): MAINLIST: " + mpMsgList.size());
		for (int k = 0; k < mpMsgList.size(); k++) {
			LinkedList<CIncomingMessage> tmpList = mpMsgList.get(k);
			log.debug("CheckMpMsgList(): SUBLIST[" + k + "]: " + tmpList.size());
			CIncomingMessage listMsg =  tmpList.get(0);
			boolean found = false;
			if (listMsg.getMpMaxNo() == tmpList.size()) {
				found = true;
				for (int l = 0; l < tmpList.size(); l++) {
					for (int m = 0; m < tmpList.size(); m++) {
						listMsg =  tmpList.get(m);
						if (listMsg.getMpSeqNo() == (l + 1)) {
							if (listMsg.getMpSeqNo() == 1) {
								mpMsg = listMsg;
								mpMsg.addMpMemIndex(mpMsg.getMemIndex());
							} else {
								// TODO not quite sure what is happening here, but this doesn't seem the ideal way to
								// build multipart messages...
								// TODO Does this properly handle multipart messages whose parts are received in the wrong order?
								if (mpMsg != null)
								{
									if (mpMsg.getMessageEncoding() == SmsMessageEncoding.GSM_7BIT || mpMsg.getMessageEncoding() == SmsMessageEncoding.UCS2) {
										mpMsg.setText(mpMsg.getText() + listMsg.getText());
									} else {
										// FIXME this really doesn't look like it concatenates binary messages properly
										byte[] extraBinary = listMsg.getBinary();
										byte[] binary = mpMsg.getBinary();
										byte[] newContent = new byte[binary.length + extraBinary.length];
										System.arraycopy(binary, 0, newContent, 0, binary.length);
										System.arraycopy(extraBinary, 0, newContent, binary.length, extraBinary.length);
										binary = newContent;
										mpMsg.setBinary(newContent);
									}
									mpMsg.setMpSeqNo(listMsg.getMpSeqNo());
									mpMsg.addMpMemIndex(listMsg.getMemIndex());
									if (listMsg.getMpSeqNo() == listMsg.getMpMaxNo())
									{
										mpMsg.setMemIndex(-1);
										messageList.add(mpMsg);
										mpMsg = null;
									}
								}
							}
							break;
						}
					}
				}
			}
			if (found) {
				mpMsgList.remove(k);
				k--;
			}
		}
	}

	/**
	 * Sends an SMS message from the GSM modem.
	 * <p>
	 * This method actually wraps the message in a list and calls #sendMessage(List) that does the job.
	 * 
	 * @param message
	 *            The message to be sent.
	 * @throws NotConnectedException
	 *             Either connect() is not called or modem has been disconnected.
	 * @see CService#sendMessage(LinkedList)
	 */

	public void sendMessage(COutgoingMessage message) throws Exception {
		switch (protocol) {
			case PDU:
				sendMessage_PDU(message);
				break;
			case TEXT:
				sendMessage_TEXT(message);
				break;
		}
	}

	/**
	 * Sends a list of messages from the GSM modem.
	 * <p>
	 * The function iterates through the supplied list of COutgoingMessage objects and tries to send them.
	 * <p>
	 * Upon succesful sending, each COutgoingMessage object should have its RefNo and DispatchDate fields set to specific values. Upon failure, the RefNo will be set to 0 and DispatchDate set to null.
	 * 
	 * @param messageList
	 *            A list of COutgoingMessage objects presenting messages that will be sent out.
	 * @throws NotConnectedException
	 *             Either connect() is not called or modem has been disconnected.
	 * @see CService#sendMessage(COutgoingMessage)
	 * @see COutgoingMessage
	 * @see CWapSIMessage
	 */
	// FIXME don't throw "Exception"
	public void sendMessages(List<COutgoingMessage> messageList) throws Exception {
		switch (protocol) {
			case PDU:
				sendMessages_PDU(messageList);
				break;
			case TEXT:
				sendMessages_TEXT(messageList);
				break;
		}
	}

	private void sendMessages_PDU(List<COutgoingMessage> messageList) throws IOException, UnrecognizedHandlerProtocolException, NoResponseException, NotConnectedException, DebugException {
		if (isConnected()) {
			keepGsmLinkOpen();
			for (COutgoingMessage message : messageList) sendMessage_PDU(message);
		} else throw new NotConnectedException();
	}
	
	/**
	 * Possibly keeps the link to the GSM device open???
	 */
	public void keepGsmLinkOpen() throws IOException {
		synchronized (_SYNC_) {
			this.atHandler.keepGsmLinkOpen();
		}
	}

	void sendMessage_PDU(COutgoingMessage message) throws IOException, NoResponseException, UnrecognizedHandlerProtocolException, NotConnectedException {
		for (String pdu : message.generatePdus(smscNumber, outMpRefNo)) {
			// We pass the length of the PDU, in octets, to the device.  This length does NOT
			// include the PDU's prefix containing the SMSC number, so this length must be
			// calculated and subtracted from the total.
			/** The length of the PDU in octets, not including the encoded SMSC Number. */
			int pduLength = pdu.length() >> 1;
			// If there is no SMSC Number set, we will not have appended one to the start of the message.
			if (smscNumber != null) {
				int smscNumberCharacterCount = smscNumber.length();
				if (smscNumberCharacterCount == 0) {
					// The SMSC is empty, so we only need to remove the space for it's length bit
					--pduLength;
				} else {
					// Ignore prefixed plus - this is accounted for in the encoding info
					if (smscNumber.charAt(0) == '+') --smscNumberCharacterCount;
					/** Number of octets encoded SMSC number occupies */
					int smscLen = (smscNumberCharacterCount + 1/* avoid rounding error */) >> 1;
					// 1 extra octet containing number encoding info
					++smscLen;
					// 1 extra octet containing smsc length
					++smscLen;
					// Subtract octet count of the SMSC Number from the pdu's octet count
					pduLength -= smscLen;
				}
			}
			
			// Get the reference number for the sent message.  For a message that is successfully sent, this will
			// be set by the SMSC; for a failed message, this will be some value less than zero.  For this reason,
			// we must update the reference number of the message object even if it is < 0
			int refNo;
			synchronized (_SYNC_) {
				refNo = atHandler.sendMessage(pduLength, pdu, null, null);
			}
			message.setRefNo(refNo);
			
			if (refNo >= 0) {
				message.setDispatchDate();
				deviceInfo.getStatistics().incTotalOut();
			} else if (refNo == -2) {
				disconnect();
				return;
			} else {
				break;
			}
		}
		// Increment the concat reference number for this service, so that the next message does
		// not have the same reference number as the previous one.
		outMpRefNo = (outMpRefNo + 1) & 0xFFFF;
	}

	private void sendMessages_TEXT(List<COutgoingMessage> messageList) throws Exception {
		if (isConnected()) {
			for (COutgoingMessage message : messageList) sendMessage_TEXT(message);
		} else throw new NotConnectedException();
	}

	/**
	 * 
	 * @param hexText A StringBuilder.  Should be empty, and will be empty on return.
	 * @param message
	 * @return
	 * @throws IOException
	 * @throws NoResponseException
	 * @throws UnrecognizedHandlerProtocolException
	 */
	private void sendMessage_TEXT(COutgoingMessage message) throws IOException, NoResponseException, UnrecognizedHandlerProtocolException {
		byte[] bytes = GsmAlphabet.stringToBytes(message.getText());
		String hexText = HexUtils.encode(bytes);
		int refNo;
		synchronized (_SYNC_) {
			refNo = atHandler.sendMessage(0, null, message.getRecipient(), hexText);
		}
		// The refNo should only be set here if the message was sent
		if (refNo >= 0) {
			message.setRefNo(refNo);
			message.setDispatchDate();
			deviceInfo.getStatistics().incTotalOut();
		}
	}

	/**
	 * Delete the message at a specific location in a specific memory of the device.
	 * @param memIndex The index into the message memory.
	 * @param memLocation The name of the memory.
	 * @throws NotConnectedException if the device is not connected
	 * @throws IOException if there is a problem communicating with the device
	 */
	protected void deleteMessage(int memIndex, String memLocation) throws NotConnectedException, IOException {
		synchronized (_SYNC_) {
			if (isConnected()) atHandler.deleteMessage(memIndex, memLocation);
			else throw new NotConnectedException();
		}
	}

	/**
	 * Deletes a message from the modem's memory.
	 * <p>
	 * <strong>Warning</strong>: Do not pass invalid CIncomingMessage objects to this call - You may corrupt your modem's storage!
	 * <p>
	 * Delete operations are irreversible.
	 * 
	 * @param message The CIncomingMessage object previously read with readMessages() call.
	 * @throws NotConnectedException Either connect() is not called or modem has been disconnected.
	 * @see CIncomingMessage
	 */
	public void deleteMessage(CIncomingMessage message) throws NotConnectedException, IOException {
		synchronized (_SYNC_) {
			int memIndex = message.getMemIndex();
			String memLocation = message.getMemLocation();
			// If this message has its memIndex set, we delete it directly.  Otherwise, it may be
			// a reference to a multipart message.  In this case, we must delete each part separately.
			if (memIndex >= 0) {
				deleteMessage(memIndex, memLocation);
			} else {
				Integer[] mpMemIndexes = message.getMpMemIndex();
				if (memIndex == -1 && mpMemIndexes.length > 0) {
					for(int mpMemIndex : mpMemIndexes) {
						deleteMessage(mpMemIndex, memLocation);
					}
				}
			}
		}
	}

	/**
	 * Reads (or refreshes) all GSM modem information (like manufacturer, signal level, etc).
	 * <p>
	 * This method is called automatically upon connection. Should you require fresh info, you should call it yourself when you need it.
	 * 
	 * @throws NotConnectedException
	 *             Either connect() is not called or modem has been disconnected.
	 * @see CDeviceInfo
	 */
	public void refreshDeviceInfo() throws IOException, NotConnectedException {
		synchronized (_SYNC_) {
			if (isConnected()) {
				// If unchangeable fields have not been set, set them now
				if (deviceInfo.manufacturer.length() == 0) deviceInfo.manufacturer = getManufacturer();
				if (deviceInfo.model.length() == 0) deviceInfo.model = getModel();
				if (deviceInfo.serialNo.length() == 0) deviceInfo.serialNo = getSerialNo();
				if (deviceInfo.imsi.length() == 0) deviceInfo.imsi = getImsi();
				if (deviceInfo.swVersion.length() == 0) deviceInfo.swVersion = getSwVersion();
				// These fields are ephemeral, so set them every time!
				deviceInfo.gprsStatus = getGprsStatus();
				deviceInfo.batteryLevel = getBatteryLevel();
				deviceInfo.signalLevel = getSignalLevel();
			} else throw new NotConnectedException();
		}
	}

	protected boolean isAlive() {
		boolean alive;

		if (!connected) alive = false;
		else try {
			alive = atHandler.isAlive();
		} catch (IOException ex) {
			// TODO should this exception be thrown?
			log.warn("Is alive threw IOException.  Returning false.", ex);
			alive = false;
		}
		return alive;
	}

	private boolean waitForNetworkRegistration() throws IOException, GsmNetworkRegistrationException, DebugException {
		while (true) {
			String response = atHandler.getNetworkRegistration();
			if (log.isDebugEnabled()) {
				log.debug("Response before convertion is [" + response + "]");
			}
			if (response.indexOf("ERROR") > 0) return false;
			response = response.replaceAll("\\s+OK\\s+", "");
			response = response.replaceAll("\\s+", "");
			response = response.replaceAll("\\+CREG:", "");
			if (log.isDebugEnabled()) {
				log.debug("Response after convertion is [" + response + "]");
			}
			StringTokenizer tokens = new StringTokenizer(response, ",");
			String firstToken = tokens.nextToken();
			if (log.isDebugEnabled()) {
				log.debug("First token [" + firstToken + "]");
			}

			int answer;
			try {
				answer = Integer.parseInt(tokens.nextToken());
			} catch (NumberFormatException _) {
				answer = -1;
			}
			if (log.isDebugEnabled()) {
				log.debug("Answer is [" + answer + "]");
			}
			switch (answer) {
				case 0:
					throw new GsmNetworkRegistrationException("GSM Network Auto-Registration disabled!");
				case 1:
					log.info("GSM: Registered to home network.");
					return true;
				case 2:
					log.warn("GSM: Not registered, searching for network...");
					break;
				case 3:
					log.error("GSM: Network registration denied!");
					throw new GsmNetworkRegistrationException("GSM Network Registration denied!");
				case 4:
					log.error("GSM: Unknown registration error!");
					throw new GsmNetworkRegistrationException("GSM Network Registration error!");
				case 5:
					log.info("GSM: Registered to foreign network (roaming).");
					return true;
				case -1:
					log.info("GSM: Invalid CREG response.");
					throw new DebugException("GSM: Invalid CREG response.");
			}
			sleep_ignoreInterrupts(1000);
		}
	}

	public String getManufacturer() throws IOException {
		String response = atHandler.getManufacturer();
		if (response.contains("ERROR")) return VALUE_NOT_REPORTED;
		response = response.replaceAll("\\s+OK\\s+", "");
		response = response.replaceAll("\\s+", "");
		response = response.replaceAll("\"", "");
		response = response.replaceAll(",", "");
		response = response.replaceAll(":", "");
		return response;
	}

	public String getModel() throws IOException {
		String response = atHandler.getModel();
		if (response.contains("ERROR")) return VALUE_NOT_REPORTED;
		response = response.replaceAll("\\s+OK\\s+", "");
		response = response.replaceAll("\\s+", "");
		response = response.replaceAll("\"", "");
		response = response.replaceAll(",", "");
		response = response.replaceAll(":", "");
		if (response.toUpperCase().contains("MODEL=")) {
			response = response.substring(response.indexOf("MODEL=") + "MODEL=".length());
		}
		return response;
	}

	public String getSerialNo() throws IOException {
		String response = atHandler.getSerialNo();
		if (response.matches("\\s*[\\p{ASCII}]*\\s+ERROR(?:: \\d+)?\\s+")) return VALUE_NOT_REPORTED;
		response = response.replaceAll("\\s+OK\\s+", "");
		response = response.replaceAll("\\s+", "");
		response = response.replaceAll("\\D", "");
		return response;
	}

	public String getImsi() throws IOException {
		 String response = atHandler.getImsi();
		 if(response.matches("\\s*[\\p{ASCII}]*\\s+ERROR(?:: \\d+)?\\s+")) return VALUE_NOT_REPORTED;
		 response = response.replaceAll("\\s+OK\\s+", "");
		 response = response.replaceAll("\\s+", "");
		 return response;
		
	}

	public String getSwVersion() throws IOException {
		String response = atHandler.getSwVersion();
		if (response.matches("\\s*[\\p{ASCII}]*\\s+ERROR(?:: \\d+)?\\s+")) return VALUE_NOT_REPORTED;
		response = response.replaceAll("\\s+OK\\s+", "");
		response = response.replaceAll("\\s+", "");
		return response;
	}

	public boolean getGprsStatus() throws IOException {
		return (atHandler.getGprsStatus().matches("\\s*[\\p{ASCII}]CGATT[\\p{ASCII}]*1\\s*OK\\s*"));
	}

	public int getBatteryLevel() throws IOException {
		String response = atHandler.getBatteryLevel();
		if (response.matches("\\s*[\\p{ASCII}]*\\s+ERROR(?:: \\d+)?\\s+")) return 0;
		response = response.replaceAll("\\s+OK\\s+", "");
		response = response.replaceAll("\\s+", "");
		StringTokenizer tokens = new StringTokenizer(response, ":,");
		tokens.nextToken();
		tokens.nextToken();
		return Integer.parseInt(tokens.nextToken());
	}

	public int getSignalLevel() throws IOException {
		String response = atHandler.getSignalLevel();
		if (response.matches("\\s*[\\p{ASCII}]*\\s+ERROR(?:: \\d+)?\\s+")) return 0;
		response = response.replaceAll("\\s+OK\\s+", "");
		response = response.replaceAll("\\s+", "");
		StringTokenizer tokens = new StringTokenizer(response, ":,");
		tokens.nextToken();
		return (Integer.parseInt(tokens.nextToken().trim()) * 100 / 31);
	}
	
	public String getMsisdn() throws IOException {
		String response = atHandler.getMsisdn();
		if (response.contains("ERROR")) return VALUE_NOT_REPORTED;
		response = response.replaceAll("\\s+OK\\s+", "");
		response = response.replaceAll("\\s+", "");
		response = response.replaceAll(":", "");
		int splitPoint = response.indexOf('"') + 1;
		if (splitPoint != 0) response = response.substring(splitPoint, response.indexOf('"', splitPoint));
		splitPoint = response.indexOf(',') + 1;
		if (splitPoint != 0) response = response.substring(splitPoint, response.indexOf(',', splitPoint));
		return response;
	}

	/**
	 * TODO should implement this properly using constants from {@link TpduUtils}
	 * @param pdu
	 * @return
	 */
	private boolean isIncomingMessage(String pdu) {
		int index, i;

		i = Integer.parseInt(pdu.substring(0, 2), 16);
		index = (i + 1) * 2;

		i = Integer.parseInt(pdu.substring(index, index + 2), 16);
		if ((i & 0x03) == 0) return true;
		else return false;
	}

	/**
	 * TODO should implement this properly using constants from {@link TpduUtils}
	 * @param pdu
	 * @return
	 */
	public static boolean isStatusReportMessage(String pdu) {
		int i = Integer.parseInt(pdu.substring(0, 2), 16);
		int index = (i + 1) * 2;

		i = Integer.parseInt(pdu.substring(index, index + 2), 16);
		return (i & TpduUtils.TP_MTI_MASK) == TpduUtils.TP_MTI_MT_STATUS_REPORT;
	}

	public boolean received(CIncomingMessage message) {
		return false;
	}

	private class CKeepAliveThread extends Thread {
		private volatile boolean stopFlag;

		private volatile boolean stopped;

		public CKeepAliveThread() {
			super(serialDriver.getPort() + " KeepAlive");
			
			stopFlag = false;
			stopped = false;
		}

		public void killMe() {
			stopFlag = true;
			// This is no longer synchronized on _SYNC_, as _SYNC_ is not released by the write method
			// if there is a problem writing to a device (e.g. the device is disconnected unexpectedly).
			this.interrupt();
		}

		public boolean isStopped() {
			return stopped;
		}

		public void run() {
			while (!stopFlag) {
				sleep_ignoreInterrupts(keepAliveInterval);
				if (stopFlag) break;
				synchronized (_SYNC_) {
					if (isConnected()) try {
						log.info("** Keep-Live **");
						atHandler.isAlive();
					} catch(IOException ex) {
						if (!stopFlag && log != null) log.warn("Unexpected exception", ex);
						// Now an exception's been thrown, we should stop this service.
						stopFlag = true;
						try { disconnect(); } catch (NotConnectedException e) { if(log!=null) log.info(e); }
					}
				}
			}
			log.debug("KeepAlive Thread is teminated!");
			stopped = true;
		}
	}

	private class CReceiveThread extends Thread {
		/** Indicates that the object's thread should stop when it can */
		private volatile boolean stopFlag;
		/** Indicates that this object's thread has finished running */
		private volatile boolean stopped;

		public CReceiveThread() {
			super(serialDriver.getPort() + " Receive");
			
			stopFlag = false;
			stopped = false;
		}

		public void killMe() {
			stopFlag = true;
			synchronized (newMsgMonitor) {
				newMsgMonitor.notify();
			}
		}

		public boolean isStopped() {
			return stopped;
		}

		public void run() {
			LinkedList<CIncomingMessage> messageList = new LinkedList<CIncomingMessage>();
			try {
				while (!stopFlag) {
					int state = newMsgMonitor.waitEvent(asyncPollInterval);
					if (stopFlag) break;
					if (isConnected() && (receiveMode == ReceiveMode.ASYNC_CNMI || receiveMode == ReceiveMode.ASYNC_POLL)) {
						try {
							if (state == CNewMsgMonitor.DATA && !atHandler.dataAvailable() && newMsgMonitor.getState() != CNewMsgMonitor.CMTI) continue;

							newMsgMonitor.reset();
							messageList.clear();
							readMessages(messageList, asyncRecvClass);
							for (int i = 0; i < messageList.size(); i++) {
								CIncomingMessage message =  messageList.get(i);
								ISmsMessageListener messageHandler = getMessageHandler();
								if (messageHandler == null) {
									if (received(message)) {
										deleteMessage(message);
									}
								} else {
									if (messageHandler.received(CService.this, message)) {
										deleteMessage(message);
									}
								}

							}
						} catch (Exception ex) {
							log.error("Unexpected exception on receive thread.", ex);
						}
					}
				}
			} finally {
				log.debug("Receive Thread is terminated!");
				stopped = true;
			}
		}
	}

	/** Holds values representing the message class of the message to be read from the GSM device. */
	public static enum MessageClass {
		/** Read all messages. */
		ALL(4, "ALL"),
		/** Read unread messages. After reading, all returned messages will be marked as read. */
		UNREAD(0, "REC UNREAD"),
		/** Read already-read messages. */
		READ(1, "REC READ");

	//> INSTANCE PROPERTIES
		/** text ID for this {@link MessageClass} when listing messages on a device in TEXT mode */
		private final String textModeId;
		/** integer ID for this {@link MessageClass} when listing messages on a device in PDU mode */
		private final int pduModeId;
		
	//> CONSTRUCTORS
		/**
		 * Create a new {@link MessageClass}
		 * @param pduModeId value for {@link #pduModeId}
		 * @param textModeId value for {@link #textModeId}
		 */
		MessageClass(int pduModeId, String textModeId) {
			this.pduModeId = pduModeId;
			this.textModeId = textModeId;
		}

		/** @return the text ID for this {@link MessageClass} when listing messages on a device in TEXT mode. */
		public String getTextId() {
			return this.textModeId;
		}
		/** @return the integer ID for this {@link MessageClass} when listing messages on a device in PDU mode. */
		public int getPduModeId() {
			return this.pduModeId;
		}
	}

	/**
	 * Make the thread sleep; ignore InterruptedExceptions.
	 * @param millis
	 * @return the number of milliseconds actually slept for
	 */
	public static long sleep_ignoreInterrupts(long millis) {
		long startTime = System.currentTimeMillis();
		try {
			Thread.sleep(millis);
		} catch(InterruptedException ex) {}
		return System.currentTimeMillis() - startTime;
	}
	
	/**
	 * Checks if this service supports SMS receipt.
	 * @return true if receiving SMSs is supported by this service.
	 */
	public boolean supportsReceive() {
		return atHandler.supportsReceive();
	}
	
	/**
	 * Checks if this service supports binary SMS sending.
	 * @return true if sending binary SMSs is supported by this service.
	 */
	public boolean supportsBinarySmsSending() {
		return atHandler.supportsBinarySmsSending();
	}
	
	/**
	 * Checks whether this service has support for sending SMS binary messages
	 * @return true if this service supports sending of SMS binary message.
	 */
	public boolean supportsUcs2SmsSending() {
		return this.atHandler.supportsUcs2SmsSending();
	}

	public String getAtHandlerName() {
		if (atHandler == null) return null;
		return atHandler.getClass().getSimpleName();
	}
}
