package sequencer;

import java.net.InetSocketAddress;

public abstract class SequencerCommon {

	//
	/*
	 (3)  Sequencer multi-case the messages to all HotelServers 
	    Packet Format: “SEQ:<seq#>\tFEAddr:IPAddress-port\n<Original request packet>” 

    send the packet to each server... stored the count of  requests = multicasted 



    (4)  Sequencer receives a respond from one of the HotelServers. 

    Packet Format: “SEQ:<seq#>\tFEAddr:IPAddress-port\tTYPE:<msg_type>\tSERVERID:<id>\n<Original respond packet>”
    
    Sequencer receives a control message 
    Packet Format: "SEQ:0\tFEAddr:0-0\tType:<type>\tSERVERID:<id>\n"
    
    where type= RESPOND, NACK, RMCTRL
	 */
	
	
 
	public static long getSeqNum (String message) {
		
		int i = message.indexOf(":");
		
		if (i<=0) return -1;
		
		if (!message.substring(0,i).equals("SEQ")) return -1;
		
		int j = message.indexOf("\t", i+1);
		
		if (j<=0) return -1;
		
		long seqNum = Integer.valueOf(message.substring(i+1,j));
		
		return seqNum;
	}
	
	public static String getMessageBody (String message) {
		
		int i = message.indexOf("\n");
		
		if (i<=0) return null;
		
		return message.substring(i+1);
	}
	
	public static String getFEAddr (String message) {
		
		int i = message.indexOf("\t");
		
		if (i<=0) return null;
		
		int j = message.indexOf(":", i+1);
		if (j<=0) return null;
		
		if (!message.substring(i+1,j).equals("FEAddr")) return null;
		
		int k = message.indexOf("\n", j+1);
		if (k<=0) return null; // shall not happen
		
		int k1 = message.indexOf("\t", j+1);
		if (k1 >=0 && k1 < k)
			// "\t" before "\n"
			k = k1;
				
		if (k<=0) return null;
		
		String addr = message.substring(j+1,k);
		
		return addr;
	}
	
	public static InetSocketAddress getFESocketAddr (String message) {
		//TODO
		return null;
	}
	
	public static String getMessageType (String message) {
		String tag = "TYPE:";
		int i = message.indexOf(tag);
		if (i<0) return "";
		
		int j = message.indexOf("\t",i+1);
		if (j<0) return "";
		
		return message.substring(i+ tag.length(),j);
	}
	
	/*
	public static int getHeaderServerID (String message) {
		String tag = "SERVERID:";

		int i = message.indexOf(tag);
		if (i<0) return -1;
		
		int j = message.indexOf("\t",i+1);
		if (j<0) return -1;
		
		return Integer.valueOf(message.substring(i + tag.length(),j));
	} */
	
	public static String getBodyMessageType (String message) {
		String content = message.substring(message.indexOf("\n")+1);

		int i = content.indexOf("\n");
		String type = content.substring(0, i);
		return type;
	}
	
	public static int getBodyServerID (String message) {
		String content = message.substring(message.indexOf("\n")+1);
		
		String tag = "SERVERID:";
		int i = content.indexOf(tag);
		if (i<0) return -1;
		
		int j = content.indexOf("\n",i+1);
		if (j<0) return -1;
		
		return Integer.valueOf(content.substring(i + tag.length(),j));
	}
	
/*	public static InetSocketAddress getServerSocketAddress (String message) {
		String tag = "SERVERADDR:";
		
		int i = message.indexOf(tag);
		if (i<0) return null;
		
		int j = message.indexOf("\t",i+1);
		
		String addr = message.substring(i + tag.length(), j);
		
		int k = addr.indexOf("-");
		
		InetSocketAddress sockAddr = new InetSocketAddress (
				addr.substring(0, k),
				Integer.valueOf(addr.substring(k+1)) );
				
		return sockAddr;
			
	} */
	
}
