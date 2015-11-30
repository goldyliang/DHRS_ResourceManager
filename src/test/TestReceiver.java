package test;

import static org.junit.Assert.*;

import org.junit.Test;

import sequencer.SequencerCommon;

public class TestReceiver {

	@Test
	public void testSequencerCommon() {
		
		String msg = "SEQ:5\tFEAddr:localhost-1234\nHello World!";
		assertEquals (5, SequencerCommon.getSeqNum(msg));
		assertEquals ("localhost-1234", SequencerCommon.getFEAddr(msg));
		assertEquals ("Hello World!", SequencerCommon.getMessageBody(msg));
		
		msg = "SEQ:9\tFEAddr:localhost-5544\tTYPE:RESPOND\tSERVERID:3\nHello World!";
		
		assertEquals (9, SequencerCommon.getSeqNum(msg));
		assertEquals ("localhost-5544", SequencerCommon.getFEAddr(msg));
		assertEquals ("RESPOND", SequencerCommon.getMessageType(msg));
		assertEquals (3, SequencerCommon.getServerID(msg));
		assertEquals ("Hello World!", SequencerCommon.getMessageBody(msg));
			    
	}

}
