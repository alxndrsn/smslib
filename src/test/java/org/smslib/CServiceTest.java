/**
 * 
 */
package org.smslib;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedList;

import org.smslib.handler.ATHandler;
import org.smslib.util.TpduUtils;

import net.frontlinesms.junit.BaseTestCase;

import static org.mockito.Mockito.*;

/**
 * Tests for {@link CService}.
 * @author Alex
 */
public class CServiceTest extends BaseTestCase {
	
//> CONSTANTS
	
//> INSTANCE VARIABLES
	/** The instance of {@link CService} under test. */
	CService cService;
	ATHandler mockAtHandler;

//> TEST SETUP
	@Override
	protected void setUp() throws Exception {
		super.setUp();

		this.mockAtHandler = mock(ATHandler.class);
		this.cService = new CService(mockAtHandler);
	}
	
//> TEST METHODS
	/**
	 * Test parsing of AT+CGMI responses in the {@link CService} class.
	 * @throws Exception 
	 */
	public void testGetManufacturer() throws Exception {
		// error responses
		testGetManufacturer("* N/A *", "");
		testGetManufacturer("* N/A *", "\nAT+CBC\r\r\n+CME ERROR: SIM PIN required\r\n");
		testGetManufacturer("* N/A *", "\nERROR\r");
		
		// well formed responses
		testGetManufacturer("SonyEricsson", "SonyEricsson\r\rOK\r");
		testGetManufacturer("WAVECOMWIRELESSCPU", "\r\n WAVECOM WIRELESS CPU\r\n\r\nOK\r");

		// badly formed responses
		testGetManufacturer("* N/A *", "\n*!\r");
		testGetManufacturer("BOOT", "\r\n^BOOT:9767194,0,0,0,20huawei\r\n\r\nOK\r");
	}
	
	/**
	 * Test parsing of AT+CGMI responses in the {@link CService} class.
	 * @param atResponse The response of the serial driver to the "get manufacturer" AT command.
	 * @param expectedManufacturer The Manufacturer string that we expect the ATHandler to return.
	 * @throws IOException if there was a problem communicating with the device. 
	 */
	private void testGetManufacturer(String expectedManufacturer, String atResponse) throws Exception {
		when(mockAtHandler.getManufacturer()).thenReturn(atResponse);
		
		String actualManufacturer = cService.getManufacturer();
		assertEquals(expectedManufacturer, actualManufacturer);
	}
	
	public void testGetBattery() throws Exception {
		// error responses
		testGetBattery(0, "");
		testGetBattery(0, "\nAT+CBC\r\r\n+CME ERROR: SIM PIN required\r\n");

		// well formed responses
		testGetBattery(37, "+CBC: 1,37");
		testGetBattery(100, "+CBC: 0,100");
		testGetBattery(0, "\r\n+CBC: 0,0\r\n\r\nOK\r");
		
		// badly formed responses
		testGetBattery(0, "+CBC: 123,");
		testGetBattery(0, "+CBC: ,123");
		testGetBattery(0, "+CBC: little,elephant");
	}
	
	private void testGetBattery(int expectedValue, String atResponse) throws Exception {
		when(mockAtHandler.getBatteryLevel()).thenReturn(atResponse);
		int actualValue = cService.getBatteryLevel();
		assertEquals("Battery level interpreted incorrectly: " + atResponse, expectedValue, actualValue);
	}
	
	public void testGetSignalLevel() throws Exception {
		// error responses
		testGetSignalLevel(0, "");
		testGetSignalLevel(0, "\nAT+CBC\r\r\n+CME ERROR: SIM PIN required\r\n");
		
		// well formed responses
		testGetSignalLevel(58, "+CSQ: 18,99");
		testGetSignalLevel(90, "+CSQ: 28,99");
		testGetSignalLevel(70, "\r\n+CSQ: 22,0\r\n\r\nOK\r");
		
		// badly formed responses
		testGetSignalLevel(319, "+CSQ: ,99");
		testGetSignalLevel(58, "+CSQ: 18,");
		testGetSignalLevel(0, "+CSQ: sock,shoe");
	}
	
	private void testGetSignalLevel(int expectedValue, String atResponse) throws Exception {
		when(mockAtHandler.getSignalLevel()).thenReturn(atResponse);
		int actualValue = cService.getSignalLevel();
		assertEquals(expectedValue, actualValue);
	}
	
	public void testGetMsisdn() throws Exception {
		// error responses
		testGetMsisdn("* N/A *", "");
		testGetMsisdn("* N/A *", "\nAT+CBC\r\r\n+CME ERROR: SIM PIN required\r\n");
		
		// well formed responses
		testGetMsisdn("15555555555", "\n+CNUM: Owner Name,15555555555,129\r\n");
		testGetMsisdn("0123456789", "\n+CNUM: ,\"0123456789\",122\r\nOK\r");
		testGetMsisdn("2035551212", "+CNUM: ,\"2035551212\",129");
		testGetMsisdn("8885551212", "\n+CNUM: \"Voice\",\"8885551212\",129\r\nOK\n");
		testGetMsisdn("254704593111", "\r\n+CNUM: \"flsms test no\",\"254704593111\",161\r\n\r\nOK\r");
		
		// badly formed responses
		testGetMsisdn("* N/A *", "\n+CNUM\r\n");
	}
	
	private void testGetMsisdn(String expected, String atResponse) throws Exception {
		when(mockAtHandler.getMsisdn()).thenReturn(atResponse);
		String actual = cService.getMsisdn();
		assertEquals(expected, actual);
	}
	
	public void testGetImsi() throws Exception {
		// error responses
		testGetImsi("* N/A *", "");
		testGetImsi("* N/A *", "\nAT+CBC\r\r\n+CME ERROR: SIM PIN required\r\n");
		
		// well formed responses
		testGetImsi("123412341234111", "\r\n123412341234111\r\n\r\nOK\r");
		
		// badly formed responses
		testGetImsi("* N/A *", "blah blah blah");
	}
	
	private void testGetImsi(String expected, String atResponse) throws Exception {
		when(mockAtHandler.getImsi()).thenReturn(atResponse);
		String actual = cService.getImsi();
		assertEquals(expected, actual);
	}
	
	public void testModel() throws Exception {
		// error responses
		testGetModel("* N/A *", "");
		testGetModel("* N/A *", "\nAT+CBC\r\r\n+CME ERROR: SIM PIN required\r\n");
		testGetModel("* N/A *", "\nERROR\r");
		
		// well formed responses
		testGetModel("V635", "\"GSM900\",\"GSM1800\",\"GSM1900\",\"GSM850\",\"MODEL=V635\"");
		testGetModel("L6", "\"GSM900\",\"GSM1800\",\"GSM1900\",\"MODEL=L6\"");
		testGetModel("LSeries", "\"L Series\"");
		testGetModel("DWM-156", "DWM-156\r\nOK");
		testGetModel("MTK2", "MTK2");
		testGetModel("MULTIBAND900E1800", "\r\n MULTIBAND  900E  1800 \r\n\r\nOK\r");
		
		// badly formed responses
		// seems a bit tricky to come up with an example of this...
	}
	
	private void testGetModel(String expected, String atResponse) throws Exception {
		when(mockAtHandler.getModel()).thenReturn(atResponse);
		String actual = cService.getModel();
		assertEquals(expected, actual);
	}
	
	public void testGetSwVersion() throws Exception {
		// error responses
		testGetSwVersion("* N/A *", "");
		testGetSwVersion("* N/A *", "\nAT+CBC\r\r\n+CME ERROR: SIM PIN required\r\n");
		
		// well formed responses
		testGetSwVersion("R7.42.0.201003050914.GL6110 2131816 030510 09:14", "\r\nR7.42.0.201003050914.GL6110 2131816 030510 09:14\r\n\r\nOK\r");
		
		// badly formed responses
		// seems a bit tricky to come up with an example of this...
	}
	
	private void testGetSwVersion(String expected, String atResponse) throws Exception {
		when(mockAtHandler.getSwVersion()).thenReturn(atResponse);
		String actual = cService.getSwVersion();
		assertEquals(expected, actual);
	}
	
	public void testGetSerialNo() throws Exception {
		// error responses
		testGetSerialNo("* N/A *", "");
		testGetSerialNo("* N/A *", "\nAT+CBC\r\r\n+CME ERROR: SIM PIN required\r\n");
		
		// well formed responses
		testGetSerialNo("123412341234123", "\r\n123412341234123\r\n\r\nOK\r");
		
		// badly formed responses
		testGetSerialNo("* N/A *", "blah blah blah");
	}
	
	private void testGetSerialNo(String expected, String atResponse) throws Exception {
		when(mockAtHandler.getSerialNo()).thenReturn(atResponse);
		String actual = cService.getSerialNo();
		assertEquals(expected, actual);
	}
	
	public void testIsError() {
		String[] errors = {
				"", // this is what CSerialDriver.getResponse() returns when it can't cope with things.  In the cases where isError() is used, this counts as an error.
				"\rCME ERROR: 29\r",
				"\n\r\n+CME ERROR: 11\r",
				"\nAT+CBC\r\r\n+CME ERROR: SIM PIN required\r\n",
				"\nERROR\r",
				"\r\n+CME ERROR: 3\r",
		};
		for(String errorResponse: errors) {
			assertTrue(CService.isError(errorResponse));
		}
		
		String[] notErrors = {
				" ",
				"somerandomtext",
				" OK\r",
				"\r\nOK\r",
				"\n\r\nOK\r",
				"\nAT\r\r\nOK\r",
				"+CMGS:123\rOK",
				"+CMGS:123\rOK\r",
				"+CMGF: (0,1)\r\rOK\r",
				" +CMGF: (0,1)\r\rOK\r",
				"+CMGS: 12\r\rOK\r",
				"+CIND: (\"Voice Mail\",(0,1)),(\"service\",(0,1)),(\"call\",(0,1)),(\"Roam\",(0-2)),(\"signal\",(0-5)),(\"callsetup\",(0-3)),(\"smsfull\",(0,1))\"\rOK\r",
				"+MBAN: Copyright 2000-2004 Motorola, Inc.\rOK\r",
				"+CPMS: 2,28,2,28,2,28\r\rOK\r",
				"\nAT^CURC=0\r\r\nOK\r",
				"\nAT+CPIN?\r\r\n+CPIN: SIM PIN\r",
				"\nAT+CPIN?\r\r\n+CPIN: READY\r",
				"\nAT+CLIP=1\r\r\nOK\r",
				"\r\n+CPMS: 25,25,25,25,25,25\r\n\r\nOK\r",
				" \r\nOK\r\n\r\n+STIN: 6\r",
				"\r\n+CBC: 2,0\r\n\r\nOK\r",
				"\n\r\nOK\r\n",
				" \r\nOK\r\n\r\n+STIN: 3\r",
				"\r\n+CMGL: 25,0,,130\r\n0791527422050000240AD04D68711A0400001121102142752180426BEDF67CDA6039D0F0ED36A7E5ED32D9055ACED136980B0603CDCB6E3A88FE060599456CD0492C4A414127B1289D3E9DA095AC46BBC164B5DCED168B81DE6E50EC1593BD623150980E8AC974321A08DA0439CB7750B3052D4E832071981D768FCBA0F41CB49CA36737568C2673C160\r\n\r\nOK\r",
				"\r\n+STGI: 0,0,0\r\n+STGI: 1,2,\"Search SIM Contacts\",0\r\n+STGI: 2,2,\"Enter phone no.\",0\r\n\r\nOK\r",
				"\r\nOK\r\n\r\n+STIN: 6\r",
				"\r\n+STGI: 0,1,0,20,0,\"Enter phone no.\"\r\n\r\nOK\r",
				"\r\n+CREG: 0,1\r\n\r\nOK\r",
				"\r\n+CPMS: \"SM\",3,25,\"SM\",3,25,\"SM\",3,25\r\n\r\nOK\r",
				"\r\n639029400593656\r\n\r\nOK\r",
				"\r\n+STGI: 0,0,0,\"M-PESA\"\r\n+STGI: 1,7,\"Send money\",0\r\n+STGI: 2,7,\"Withdraw cash\",0\r\n+STGI: 3,7,\"Buy airtime\",0\r\n+STGI: 4,7,\"Pay Bill\",0\r\n+STGI: 5,7,\"Buy Goods\",0\r\n+STGI: 6,7,\"ATM Withdrawal\",0\r\n+STGI: 7,7,\"My account\",0\r\n\r\nOK\r",
				" \r\nOK\r\n\r\n+STIN: 1\r",
				"\n",
				"\r\n+STGI: 0,0,4,4,0,\"Enter PIN\"\r\n\r\nOK\r",
				"\r\n+CSQ: 22,0\r\n\r\nOK\r",
				"\r\nE160\r\n\r\nOK\r",
				"\r\n+CMGL: 25,0,,120\r\n0791527422050000240AD04D68711A0400001121102103622174C6709A5D26BB40CD16B4380D82C661B7FB4D0781E0E13C683947C7602E180C447F8396456736E89C828C4F2968597466832E90F12D07B5DFF23228ED36BFE5ED303DFD7683C661361BF49683A6CD29685C9FD3DFEDB21C342FCBEDE971790E7ABB41B219CD05\r\n\r\nOK\r",
				"\r\n MULTIBAND  900E  1800 \r\n\r\nOK\r",
				"\r\n+STGI: \"Sending...\"\r\n\r\nOK\r\n\r\n+STIN: 1\r",
				"\n\r\n+STIN: 99\r\n",
				"\r\n WAVECOM WIRELESS CPU\r\n\r\nOK\r",
				"\r\nR7.42.0.201003050914.GL6110 2131816 030510 09:14\r\n\r\nOK\r",
				"\r\n+CBC: 0,0\r\n\r\nOK\r",
				"\r\n+CNUM: \"flsms test no\",\"254704111111\",161\r\n\r\nOK\r",
				"\r\n+CPMS: 3,3,24,25,24,25\r\n\r\nOK\r",
				"\r\n+CGATT: 1\r\n\r\nOK\r",
				"\r\n+STIN: 99\r\n\r\nOK\r",
				"\r\nOK\r\n\r\nOK\r\n\r\nOK\r\n\r\nOK\r\n",
				"\r\n+STGI: 1,\"Send money to +254702111111\nKsh60\",1\r\n\r\nOK\r",
				"\r\n+STGI: 0,1,0,8,0,\"Enter amount\"\r\n\r\nOK\r",
				"\r\nhuawei\r\n\r\nOK\r",
				"\r\n+STGI: 0,0,0\r\n+STGI: 1,2,\"Search SIM Contacts\",0\r\n+STGI: 2,2,\"Enter account no.\",0\r\n\r\nOK\r",
				"\r\n012345678909876\r\n\r\nOK\r",
				"\r\n+STGI: \"Safaricom\"\r\n+STGI: 1,2,\"Safaricom+\",0,0\r\n+STGI: 128,2,\"M-PESA\",0,21\r\n\r\nOK\r",
				"\r\n+CPMS: 3,3,25,25,25,25\r\n\r\nOK\r",
				"\r\n+CPMS: 24,25,24,25,24,25\r\n\r\nOK\r",
				"\r\n+STGI: 1,1,0,20,0,\"Enter account no.\"\r\n\r\nOK\r",
				"\r\n+STGI: 0,1,0,20,0,\"Enter business no.\"\r\n\r\nOK\r",
				"\r\n",
				"\r\nOK\r\n\r\n+STIN: 3\r",
				"\r\n987654321234123\r\n\r\nOK\r",
				"\r\nOK\r\n\r\n+STIN: 99\r",
				"\r\n+CPIN: READY\r",
				"\r\n+STGI: 0,0,0\r\n+STGI: 1,2,\"Search SIM Contacts\",0\r\n+STGI: 2,2,\"Enter business no.\",0\r\n\r\nOK\r",
				"\r\n11.608.02.00.94\r\n\r\nOK\r",
				"\r\n+STGI: 1,\"Pay Bill 111111 Account 111111\nKsh10\",1\r\n\r\nOK\r",
				"\r\n012345678901234\r\n\r\nOK\r",
				"\r\nOK\r",
				"\r\n+CSQ: 14,99\r\n\r\nOK\r",
				"\n\r\nOK\r",
				"\r\nOK\r\n\r\n+STIN: 9\r",
				"\r\n+CGATT: 0\r\n\r\nOK\r",
				"\r\n+STGI: 1,\"Sent\nWait for M-PESA to reply\",0\r\n\r\nOK\r",
		};
		for(String notErrorResponse: notErrors) {
			assertFalse("Wrongly interpreted as error: <" + notErrorResponse + ">", CService.isError(notErrorResponse));
		}
	}
	
	/** Tests for {@link CService#getMemIndex(String)}. */
	public void testGetMemoryIndex() {
		testGetMemoryIndex(1, "+CMGL: 1,1,,142");
		testGetMemoryIndex(2, "+CMGL: 2,0,,26");
		testGetMemoryIndex(1, "+CMGL: 1,1,,152");
		testGetMemoryIndex(10, "+CMGL: 10,1,,159");
	}
	
	/** Test {@link CService#getMemIndex(String)} with specific values. */
	private void testGetMemoryIndex(int expectedMemIndex, String atResponseLine) {
		assertEquals("Incorrect memory index read from line: " + atResponseLine, expectedMemIndex, CService.getMemIndex(atResponseLine));
	}
	
	/** Tests for {@link CService#getNextUsefulLine(BufferedReader)} */
	public void testGetNextUsefulLine() throws IOException {
		testGetNextUsefulLine("+CMGL: 1,1,,142\n0791449737019037040BD04F79D87D2E030000902091610474008CC834C82C7FB7414F79D87D2EBB40D437E85CA683F2EFBA1C447CB3E1E8B41B242FDFC372F21CE42EE3E9A0F6DB4D47B340EAFA9C0EA2BFE1AD3A1C24CE83023118E82D07B5DFF232485C36BFE565908CF682C95EB09C0B947483E8E832881D9ED3413318881CCECF41F977FD642F83E86F38BC4C06D5E1A000CC05\n+CMGL: 2,0,,26\n0791447728008000040C9144978851560500009030215153950008D972180DBA97D3\nOK");
		testGetNextUsefulLine("\n+CMGL: 1,1,,152\n0791449737709499440C9144877327605700008060904185124098060804B0930301B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562\n\n+CMGL: 2,1,,152\n0791449737709499440C9144877327605700008060905100934098060804A4680301B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562\n\n+CMGL: 3,1,,152\n0791449737709499440C91448773276057000080609051401240980608046DE80301B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562\n\n+CMGL: 4,1,,152\n0791449737709499440C9144877327605700008060905160844098060804A16A0301B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562\n\n+CMGL: 5,1,,152\n0791449737709499440C914487732760570000806090514153409806080412290301B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562\n\n+CMGL: 6,1,,152\n0791449737709499440C9144877327605700008060905112354098060804D7DB0301B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562B1582C168BC562\n\n+CMGL: 7,1,,159\n0791447728008000440C91449788515605000090203171028200A0050003150202EFE5B49C0C32CBDF6D10BA2C2F835AA0F41C94A683E67532B9EC66E74175B7BC1C2687C5EC320B24CE83C2EE3C688C0EBBC7E55F08842CCBCBA739888E2E83C26472990C12BFDDF539E86D06D1D16510FDFE06B5CBF379F85C0689DF7537392CCFBB5C2ED0F4DD2EDFD16579D9E57281AEE8B2BC0C4ACF4169FA0F94048DC3EE131D342F97DB\n\n+CMGL: 8,1,,159\n0791448720003023400C914467420873770004806011111380408C0B0504000000000003B90302808182838485868788898A8B8C8D8E8F909192939495969798999A9B9C9D9E9FA0A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0C1C2C3C4C5C6C7C8C9CACBCCCDCECFD0D1D2D3D4D5D6D7D8D9DADBDCDDDEDFE0E1E2E3E4E5E6E7E8E9EAEBECEDEEEFF0F1F2F3F4F5F6F7F8F9FAFBFCFDFEFF\n\n+CMGL: 9,1,,75\n0791448720003023440C91446742087377000480601111138040380B0504000000000003B90303000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F202122232425262728292A2B\n\n+CMGL: 10,1,,159\n0791449737709499440C91448773276057000080708031005340A005000347020189EFBA985D06B5CBF379F85C7681826E7A985D06B5CBF379F81C0691DF7531BB4C06B5CBF379F81C0685DDF430BB0C0ABBE9617619447FA7D9A0B09B0C9ABBEBE375590E7AB3C9A0F0B9CE9E83DA6C1B1DCD0699DF6CFA1BD466B7E96E503B4FBFD7F5F57B5D5FBFD7F5F57B5D5FBFD7F5F57B5D5FBFD7F5F57B5D5FBFD7F5F57B5D5FBFD7F5\n\n+CMGL: 11,1,,47\n0791449737709499440C914487732760570000807051117230401F06080400230202ED73FBDC3EB7CFED33FC0EBFC3EFF07BFC0EBFC301\n\n+CMGL: 12,1,,25\n079144973770939906660C91449788515605903021514363009030215143040000\n\n+CMGL: 13,1,,25\n079144973770939906670C91449788515605903021514335009030215153100000\n\n+CMGL: 14,1,,25\n079144973770939906680C91449788515605903021515381009030215153620000\n+CMGL: 15,1,,146\n0791449737709499440C914487732760570004806011711065407F0C0504000000000804222A0301150001020A012C000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F202122232425262728292A2B2C2D2E2F303132333435363738393A3B3C3D3E3F404142434445464748494A4B4C4D4E4F505152535455565758595A5B5C5D5E5F606162636465666768696A\n\n+CMGL: 16,1,,25\n079144973770939906690C91449788515605903021515393009030215153440000\n\n+CMGL: 17,0,,29\n0791447728008000040C914497885156050000903021516301000B53F6FB0E82A3DFEE7208\n\n\nOK");
	}
	
	/** Test {@link CService#getNextUsefulLine(BufferedReader)} with specific data */
	public void testGetNextUsefulLine(String response) throws IOException {
		String[] responseParts = response.split("\n");
		BufferedReader reader = new BufferedReader(new StringReader(response));
		for(String responsePart : responseParts) {
			if(responsePart.length() == 0) {
				log.trace("Ignoring empty line.");
			} else {
				assertEquals("Incorrect line fetched.", responsePart, CService.getNextUsefulLine(reader));
			}
		}
	}
	
	public void testSendMessage_PDU() throws IOException, SMSLibDeviceException {
		// Here is the same message five times with different SMSC numbers attached
		testSendMessage_PDU("",              "0031000AA160480173770000FF06E3777DFCAE03");
		testSendMessage_PDU("07890123456",   "07A17098103254F631000AA160480173770000FF06E3777DFCAE03");
		testSendMessage_PDU("0789012345",    "06A1709810325431000AA160480173770000FF06E3777DFCAE03");
		testSendMessage_PDU("+447890123456", "079144870921436531000AA160480173770000FF06E3777DFCAE03");
		testSendMessage_PDU("+44789012345",  "07914487092143F531000AA160480173770000FF06E3777DFCAE03");
	}
	
	public void testSendMessage_PDU(String smscNumber, String... messagePdus) throws IOException, SMSLibDeviceException {
		COutgoingMessage message = mock(COutgoingMessage.class);
		when(message.generatePdus(eq(smscNumber), anyInt())).thenReturn(messagePdus);
		
		cService.setSmscNumber(smscNumber);
		cService.sendMessage_PDU(message);
		
		for(String messagePdu : messagePdus) {
			byte[] encodedSmscNumber = TpduUtils.encodeMsisdnAsAddressField(smscNumber, true);
			int smscLengthOctets = encodedSmscNumber.length;
			int pduLengthOctets = (messagePdu.length() / 2);
			verify(mockAtHandler).sendMessage(pduLengthOctets - smscLengthOctets, messagePdu, null, null);
		}
	}
	
	/* public void testReadMessages_PDU() throws IOException, SMSLibDeviceException {
		AbstractATHandler handler = mock(AbstractATHandler.class);
		CService service = new CService(handler);

		LinkedList<CIncomingMessage> messageList = new LinkedList<CIncomingMessage>();
		service.readMessages(messageList, CService.MessageClass.ALL);
		
		// TODO verify the message list contains the expected messages
	}*/
	
	public void testStatusReportProcessing() throws Exception {
		when(mockAtHandler.getStorageLocations()).thenReturn("AA");
		final String[] PDUS = {
				/* with SMSC */ "07A17098103254F606130C91527420121670110172111332E11101721113322100", // PDU with SMSC number
				/* no SMSC */   "06130C91527420121670110172111332E11101721113322100", // PDU without SMSC number
				/* no SMSC */   "06140C91527420121670110172111412E11101721114122100",
				/* no SMSC */   "06150C91527420121670110172114413E11101721144132100" };
		for(String pdu : PDUS) {
			System.out.println(pdu);
			LinkedList<CIncomingMessage> messageList = new LinkedList<CIncomingMessage>();
			cService.createMessage(messageList, pdu, 0, 0);
			assertEquals(1, messageList.size());
			assertTrue(messageList.get(0) instanceof CStatusReportMessage);
		}

	}
}
