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
import multicast.SequencedReceiver;

import org.junit.Test;

import HotelServerInterface.ErrorAndLogMsg.ErrorCode;
import rm.ResourceManager;
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
					"0",  // server ID
					"2000", // localport
					"localhost", // FE address
					"4000"  // FE port
			};
			
			ResourceManager.main(args);
			
			activeRm = ResourceManager.activeRMs.get(0);
			
			assertSeqReceiverRunning();
			
			assertEquals (3, cntOfAppRunning_Gordon ());
			
			Thread th = (activeRm.replaceApp(
						hotelserver.ServerGordon.class, -1));
			assertNotNull (th);
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
	
	
	
	@Test
	public void testBulkSync () {
		
	}
	
	@Test
	public void testReservation () throws IOException {
		
		String [] args = {
				"hotelserver.ServerGordon", // app class
				"0",  // server ID
				"2000", // localport
				"localhost", // FE address
				"4000"  // FE port
		};
		
		// start three replications
		ResourceManager.main(args);
		
		args[1] = "1";
		args[2] = "2001";
		ResourceManager.main(args);
		
		args[1] = "2";
		args[2] = "2002";
		ResourceManager.main(args);
		
		// wait for some time
		try {
			Thread.sleep(3000);
		} catch (InterruptedException e) {

		}
		
		
		UDPEmulator udpSeq = new UDPEmulator (4000);
		
		InetSocketAddress rmAddr[] = new InetSocketAddress [3];
		
		rmAddr[0] = new InetSocketAddress ("localhost", 2000);
		rmAddr[1] = new InetSocketAddress ("localhost", 2001);
		rmAddr[2] = new InetSocketAddress ("localhost", 2002);
		
		String request = 
				 "SEQ:5\tFEAddr:localhost-3333\n" 
				+ MessageType.RESERVE + "\n"
				+ PropertyName.GUESTID + ":35555\n"
				+ PropertyName.HOTEL + ":Gordon\n"
				+ PropertyName.ROOMTYPE + ":SINGLE\n"
				+ PropertyName.CHECKINDATE + ":2015/12/5\n"
				+ PropertyName.CHECKOUTDATE + ":2015/12/20\n";
		
		udpSeq.sendPacket(rmAddr[0], request);
		udpSeq.sendPacket(rmAddr[1], request);
		udpSeq.sendPacket(rmAddr[2], request);

		for (int i=0; i<3; i++) {
			// receive three times
			String ret = udpSeq.receivePacket();
			
			assertNotNull (ret);
			
			assertEquals (5, SequencedReceiver.getSeqNum(ret));
			assertEquals ("localhost-3333", SequencedReceiver.getFEAddr(ret));
			
			GeneralMessage msg = GeneralMessage.decode(SequencedReceiver.getMessageBody(ret));
			
			assertEquals ( MessageType.RESPOND, msg.getMessageType() );
			assertEquals ( ErrorCode.SUCCESS.toString(), msg.getValue(PropertyName.RETCODE));
		}
		
		List <ResourceManager> tmpList = new ArrayList<ResourceManager> ();
		tmpList.addAll(ResourceManager.activeRMs);
		
		for (ResourceManager rm : tmpList) 
			assertTrue (rm.stopApp());
				
	}
	
	

}
