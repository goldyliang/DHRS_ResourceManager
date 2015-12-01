package test;

import static org.junit.Assert.*;

import java.net.InetSocketAddress;

import org.junit.Test;

import sequencer.SequencerCommon;

public class TestReceiver {

	@Test
	public void testSequencerCommon() {
		
		String msg = "SEQ:5\t\nHello World!";
		assertEquals (5, SequencerCommon.getSeqNum(msg));
		assertEquals ("Hello World!", SequencerCommon.getMessageBody(msg));
		
		msg = "SEQ:9\tTYPE:RESPOND\t\nHello World!";
		
		assertEquals (9, SequencerCommon.getSeqNum(msg));
		assertEquals ("RESPOND", SequencerCommon.getMessageType(msg));
		assertEquals ("Hello World!", SequencerCommon.getMessageBody(msg));
			    
		msg = "TYPE:RMCTRL\t\nADD_SERVER\nSERVERID:1\n";
		//InetSocketAddress addr = SequencerCommon.getServerSocketAddress(msg);
		
		//assertEquals ("127.0.0.1", addr.getHostString());
		//assertEquals (3333, addr.getPort());
		//assertEquals (3, SequencerCommon.getHeaderServerID(msg));
		assertEquals (1, SequencerCommon.getBodyServerID(msg));

		
	}


}
