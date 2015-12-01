package test;

import static org.junit.Assert.*;

import java.net.InetSocketAddress;

import org.junit.Test;

import sequencer.SequencerCommon;

public class TestReceiver {

	@Test
	public void testSequencerCommon() {
		
		String msg = "SEQ:5\tFEAddr:localhost-1234\nHello World!";
		assertEquals (5, SequencerCommon.getSeqNum(msg));
		assertEquals ("localhost-1234", SequencerCommon.getFEAddr(msg));
		assertEquals ("Hello World!", SequencerCommon.getMessageBody(msg));
		
		msg = "SEQ:9\tFEAddr:localhost-5544\tTYPE:RESPOND\tSERVERID:3\t\nHello World!";
		
		assertEquals (9, SequencerCommon.getSeqNum(msg));
		assertEquals ("localhost-5544", SequencerCommon.getFEAddr(msg));
		assertEquals ("RESPOND", SequencerCommon.getMessageType(msg));
		assertEquals (3, SequencerCommon.getServerID(msg));
		assertEquals ("Hello World!", SequencerCommon.getMessageBody(msg));
			    
		msg = "FEAddr:localhost-5544\tTYPE:ADD_SERVER\tSERVERID:3\tSERVERADDR:127.0.0.1-3333\t\n";
		InetSocketAddress addr = SequencerCommon.getServerSocketAddress(msg);
		
		assertEquals ("localhost", addr.getHostString());
		assertEquals (3333, addr.getPort());
	}


}
