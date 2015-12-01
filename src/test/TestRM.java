package test;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

import hotelserver.ServerGordon;
import sequencer.SequencedReceiver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import HotelServerInterface.ErrorAndLogMsg.ErrorCode;
import rm.ResourceManager;
import sequencer.SequencerCommon;
import message.GeneralMessage.PropertyName;
import message.GeneralMessage;
import message.GeneralMessage.MessageType;


public class TestRM {

	
	Object getPrivateField (Object obj, String field) 
			throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
        Field f = obj.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(obj);
	}
	
    void assertSeqReceiverRunning () throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
		//check the RM thread
    	for (ResourceManager rm : ResourceManager.activeRMs) {
    		SequencedReceiver sq = (SequencedReceiver) 
    				(getPrivateField(ResourceManager.activeRMs, "receiver"));
				
    		assertTrue( sq.isRunning());
    	}
	}
	
	int cntOfAppRunning_Gordon () throws IOException {
		Runtime rt = Runtime.getRuntime();
		String curPath = System.getProperty("user.dir");

		Process proc = rt.exec(curPath + "/appcntgordon.sh");

		BufferedReader stdInput = new BufferedReader(new 
		     InputStreamReader(proc.getInputStream()));

		// read the output from the command
		String s = stdInput.readLine();
		return Integer.valueOf(s);

	}
	

	//@Test
	public void testLaunchRestartApp () throws 
			Exception {
		
		ResourceManager activeRm = null;
		
		
		try {
			String [] args = {
					"hotelserver.ServerGordon", // app class
					"1",  // server ID
					"2000", // localport
					"localhost", // FE address
					"4000"  // FE port
			};
			
			ResourceManager.main(args);
			
			activeRm = ResourceManager.activeRMs.get(0);
			
			assertSeqReceiverRunning();
			
			assertEquals (3, cntOfAppRunning_Gordon ());
			
			activeRm.replaceApp(
						hotelserver.ServerGordon.class, -1);
			//if (th.isAlive()) 
			//	th.wait();
			
			assertSeqReceiverRunning();
			
			assertEquals (3, cntOfAppRunning_Gordon ());
			
			assertTrue (activeRm.stopApp());
	
			assertEquals (0, cntOfAppRunning_Gordon ());
		} catch (Exception e) {
			if (activeRm!=null) {
				activeRm.stopApp();
			}
			throw e;
		}
	}
	
	long seqID = 0;
	
	UDPEmulator udpSeq;
	
	String addrFE = "localhost-3333";
	
	// index from 1 to 3
	InetSocketAddress rmAddr[] = new InetSocketAddress [4];
	
	@Before
	public void init () {
		try {
			udpSeq = new UDPEmulator (4000);
		} catch (SocketException e) {
			e.printStackTrace();
		}
		/*rmAddr[0] = new InetSocketAddress ("localhost", 2000);
		rmAddr[1] = new InetSocketAddress ("localhost", 2001);
		rmAddr[2] = new InetSocketAddress ("localhost", 2002);*/
	}
	
	@After
	public void tearDown () {
		if (udpSeq!=null)
			udpSeq.close ();
		udpSeq = null;
	}
	
	@Test
	public void testBulkSync () {
		
	}	
	
	private void verifyReserve (String guest, String hotel, String room, String inDate, String outDate) 
			throws IOException {
		String request = 
				 "SEQ:" + seqID + "\tFEAddr:localhost-3333\n" 
				+ MessageType.RESERVE + "\n"
				+ PropertyName.GUESTID + ":" + guest +"\n"
				+ PropertyName.HOTEL + ":" + hotel + "\n"
				+ PropertyName.ROOMTYPE + ":" + room + "\n"
				+ PropertyName.CHECKINDATE + ":" + inDate + "\n"
				+ PropertyName.CHECKOUTDATE + ":" + outDate + "\n";
		
		udpSeq.sendPacket(rmAddr[1], request);
		udpSeq.sendPacket(rmAddr[2], request);
		udpSeq.sendPacket(rmAddr[3], request);

		for (int i=1; i<=3; i++) {
			// receive three times
			String ret = udpSeq.receivePacket();
			
			assertNotNull (ret);
			
			assertEquals (seqID, SequencerCommon.getSeqNum(ret));
			//assertEquals ("localhost-3333", SequencedReceiver.getFEAddr(ret));
			
			GeneralMessage msg = GeneralMessage.decode(SequencerCommon.getMessageBody(ret));
			
			assertEquals ( MessageType.RESPOND, msg.getMessageType() );
			assertEquals ( String.valueOf(seqID), msg.getValue(PropertyName.RESID));
		}
	}
	
	//@Test
	public void testReservation () throws IOException {
				
		String [] args = {
				"hotelserver.ServerGordon", // app class
				"1",  // server ID
				"2000", // localport
				"localhost", // FE address
				"4000"  // FE port
		};
		
		// start three replications
		ResourceManager.main(args);
		
		args[1] = "2";
		args[2] = "2001";
		ResourceManager.main(args);
		
		args[1] = "3";
		args[2] = "2002";
		ResourceManager.main(args);
		
		// wait for some time
		try {
			Thread.sleep(3000);
		} catch (InterruptedException e) {

		}
		
		verifyReserve ("1234","H1","SINGLE","20151205","20151210");
		
		List <ResourceManager> tmpList = new ArrayList<ResourceManager> ();
		tmpList.addAll(ResourceManager.activeRMs);
		
		for (ResourceManager rm : tmpList) 
			assertTrue (rm.stopApp());
						
	}
	
	private void seqSendRMCtrolMsg (GeneralMessage msg) throws IOException {
		String request = 
				 "SEQ:" + seqID + "\tFEAddr:" + addrFE + "\n" 
				+ msg.encode();
		
		int rmCnt = 0;
		
		for (int i=1; i<=3; i++) 
			if (rmAddr[i]!=null) {
				udpSeq.sendPacket(rmAddr[i], request);
				rmCnt ++;
			}


		for (int i=0; i<rmCnt; i++) {
			// receive three times
			String ret = udpSeq.receivePacket();
			
			assertNotNull (ret);
			
			assertEquals (seqID, SequencerCommon.getSeqNum(ret));
			//assertEquals (addrFE, SequencedReceiver.getFEAddr(ret));
			
			GeneralMessage rsp = GeneralMessage.decode(SequencerCommon.getMessageBody(ret));
			
			assertEquals ( MessageType.RESPOND, rsp.getMessageType() );
			
		}
		
		seqID ++;
	}
	
	// Sequencer shall receive a control message from server
	// examine header, and return the body
	// if fromSrv = 0, from any
	private GeneralMessage seqReceiveCtrlMsg (int fromSrv) throws IOException {
		String s = udpSeq.receivePacket();
		assertNotNull (s);
		//assertEquals (0, SequencerCommon.getSeqNum(s));
		//assertEquals ("0-0", SequencerCommon.getFEAddr(s));
		assertEquals ("RMCTRL", SequencerCommon.getMessageType(s));
		
		if (fromSrv > 0) {
			
			InetSocketAddress addr = (InetSocketAddress) udpSeq.getLastReceivePacketAddress();
			
			assertEquals (rmAddr[fromSrv], addr);
			//if (fromSrv != SequencerCommon.getHeaderServerID(s) )
			//	fromSrv = fromSrv;

			//assertEquals (fromSrv, SequencerCommon.getHeaderServerID(s));
		}
		
		GeneralMessage ctrMsg = GeneralMessage.decode(SequencerCommon.getMessageBody(s));
		
		return ctrMsg;

	}
	
	private void runResourceMain (final String[] args) {
		new Thread () {
			@Override
			public void run () {
				ResourceManager.main(args);
			}
		}.start();
	}
	
	@Test
	public void testAppRestartDueToError () throws IOException, InterruptedException {
		
		
		String [] args1 = {
				"hotelserver.ServerGordon", // app class
				"1",  // server ID
				"2000", // localport
				"localhost", // FE address
				"4000"  // FE port
		};
		
		// start three replications
		runResourceMain(args1);
		verifyServerAdd (1);

		
		String [] args2 = {
				"hotelserver.ServerGordon", // app class
				"2",  // server ID
				"2001", // localport
				"localhost", // FE address
				"4000"  // FE port
		};
		runResourceMain(args2);
		verifyServerAdd (2);

		
		String [] args3 = {
				"hotelserver.ServerGordon", // app class
				"3",  // server ID
				"2002", // localport
				"localhost", // FE address
				"4000"  // FE port
		};
		runResourceMain(args3);
		verifyServerAdd (3);

		
		// wait for some time
		try {
			Thread.sleep(3000);
		} catch (InterruptedException e) {

		}

		
		// now we reserve one first
		verifyReserve ("1234","H1","DOUBLE","20151205","20151210");
		
		GeneralMessage suspectRpt = new GeneralMessage (MessageType.REPORT_SUSPECTED_RESPOND);
		suspectRpt.setValue(PropertyName.SERVERID, "1");
		seqSendRMCtrolMsg (suspectRpt);
		seqSendRMCtrolMsg (suspectRpt);

		verifyServerRestart (1, 1);

		Thread.sleep(2000);
		
		// now we can reserve again
		verifyReserve ("1234","H3","SINGLE","20151205","20151210");
		
		
		List <ResourceManager> tmpList = new ArrayList<ResourceManager> ();
		tmpList.addAll(ResourceManager.activeRMs);
		
		for (ResourceManager rm : tmpList) 
			assertTrue (rm.stopApp());
		
	}
	
	private void verifyServerAdd (int server) throws IOException {
		// receive add server
		GeneralMessage addSrv = seqReceiveCtrlMsg (0); // from any server
		assertEquals (MessageType.ADD_SERVER, addSrv.getMessageType());
		
		int addServer = Integer.valueOf(addSrv.getValue(PropertyName.SERVERID));
		
		System.out.println ("Verified receiving ADD_SERVER");

		if (server > 0) 
			assertEquals (server, addServer);
		
		
		// set the address
		rmAddr [addServer]= (InetSocketAddress) udpSeq.getLastReceivePacketAddress();
		
		// broadcast back add server
		seqSendRMCtrolMsg (addSrv);
		
		System.out.println ("Verified broacsted ADD_SERVER");

	}
	
	private void verifyServerRestart (int serverControl, int serverRestart) throws IOException {
		// receive remove server
		GeneralMessage rmvSrv = seqReceiveCtrlMsg (serverControl); // suppose to receive the message from controlling server
		
		assertEquals (MessageType.RMV_SERVER, rmvSrv.getMessageType());
		
		// verify the server to be restart
		assertEquals (String.valueOf(serverRestart), rmvSrv.getValue(PropertyName.SERVERID));
		
		System.out.println ("Verified receiving RMV_SERVER");
		
		// broadcast back remove server
		seqSendRMCtrolMsg (rmvSrv);
		
		System.out.println ("Verified broadcasted RMV_SERVER");

		
		// TODO: reseive pause
		
		verifyServerAdd (serverRestart);

		
		// receive resume
		//GeneralMessage resume = seqReceiveCtrlMsg (serverControl);
		//assertEquals (MessageType.RESUME, resume.getMessageType());
		
		//System.out.println ("Verified receiving RESUME");
		
		// broadcast back add resume
		//seqSendRMCtrolMsg (resume);
		
		//System.out.println("Verified broadcasted RESUME");
	}
	
	@Test
	public void testAppRestartDueToNoRsp () throws IOException, InterruptedException {
		
		
		String [] args1 = {
				"hotelserver.ServerGordon", // app class
				"1",  // server ID
				"2000", // localport
				"localhost", // FE address
				"4000"  // FE port
		};
		
		// start three replications
		runResourceMain(args1);
		verifyServerAdd (1);

		
		String [] args2 = {
				"hotelserver.ServerGordon", // app class
				"2",  // server ID
				"2001", // localport
				"localhost", // FE address
				"4000"  // FE port
		};
		runResourceMain(args2);
		verifyServerAdd (2);

		
		String [] args3 = {
				"hotelserver.ServerGordon", // app class
				"3",  // server ID
				"2002", // localport
				"localhost", // FE address
				"4000"  // FE port
		};
		runResourceMain(args3);
		verifyServerAdd (3);
		
		// wait for some time
		try {
			Thread.sleep(3000);
		} catch (InterruptedException e) {

		}
		
		
		// now we reserve one first
		verifyReserve ("1234","H1","DOUBLE","20151205","20151210");
		
		GeneralMessage suspectRpt = new GeneralMessage (MessageType.REPORT_NO_RESPOND);
		suspectRpt.setValue(PropertyName.SERVERID, "1");
		seqSendRMCtrolMsg (suspectRpt);
		seqSendRMCtrolMsg (suspectRpt);

		// verify the restart process, the controlling server shall the one before
		// the failure one, and restart server is the failure one
		verifyServerRestart (3, 1);
		
		Thread.sleep(2000);
		
		// now we can reserve again
		verifyReserve ("1234","H3","SINGLE","20151205","20151210");
		
		List <ResourceManager> tmpList = new ArrayList<ResourceManager> ();
		tmpList.addAll(ResourceManager.activeRMs);
		
		for (ResourceManager rm : tmpList) 
			assertTrue (rm.stopApp());
		
	}
	

}
