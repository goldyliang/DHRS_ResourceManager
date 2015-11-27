package multicast;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;

public class SequencedReceiver implements Runnable {

	PacketHandler handler;
	
	DatagramSocket socketLocal;
	InetSocketAddress addrSequencer;
	
	int localPort;
	
	
	public SequencedReceiver (int port, InetSocketAddress addrSeq, PacketHandler handler) {
		localPort = port;
		addrSequencer = addrSeq;

		this.handler = handler;
	}
	
    private void deliverPacket (final String header, final String request) {
    	
    	new Thread (){
			@Override
			public void run() {
				long seqNum = getSeqNum (header);
		        String response = handler.handlePacket (seqNum, request);
		        sendRespond (header, response);
			}
    	}.start();
        
    }
    
    private void sendRespond (String header, String response) {
    	
    	String msg = header + "\n" + response;
    	byte[] buf = msg.getBytes();
    	
    	try {
			DatagramPacket p = new DatagramPacket(buf, buf.length, addrSequencer);
			
			socketLocal.send(p);
			
		} catch (IOException e) {
			e.printStackTrace();
		}
    	
    }
    
    public void sendRMControlMsg (String controlMsg) {
    	
    }


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
		
		if (k<=0) return null;
		
		String addr = message.substring(j+1,k);
		
		return addr;
	}
	
	@Override
	public void run() {
		
		byte[] buf = new byte[1000];
		
		DatagramPacket p = new DatagramPacket(buf, buf.length);
		
		while (true) {
			
			try {
				
				if (Thread.interrupted() || socketLocal == null || socketLocal.isClosed())
					return;
				
				socketLocal.receive(p);
				
				if (Thread.interrupted()) return;
				
				String request = new String (p.getData());
				
				int i = request.indexOf("\n");
				
				if (i>0) {
					String header = request.substring(0, i);
					deliverPacket(header, request.substring(i+1));
				}
				
			} catch (SocketException e) {
				if (! e.toString().contains("Socket closed"))
					e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
	}
	

	Thread thReceiver;
	
	public synchronized void startReceiver() {
		
		try {
			if (socketLocal!=null)
				socketLocal.close();
			
			socketLocal = new DatagramSocket(localPort);
		} catch (SocketException e) {
			e.printStackTrace();
			return;
		}
		
		if (thReceiver!=null)
			thReceiver.interrupt();
		
		thReceiver = new Thread (this);
		
		if (thReceiver!=null)
			thReceiver.start();
	}
	
	public synchronized void stopReceiver() {
		
		if (socketLocal!=null)
			socketLocal.close();
		
		socketLocal = null;
		
		if (thReceiver!=null)
			thReceiver.interrupt();
		
		thReceiver = null;
	}

	public boolean isRunning () {
		return thReceiver.isAlive();
	}
}
