package test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;

public class UDPEmulator {

	DatagramSocket socket;
	
	SocketAddress dest;
	
	public UDPEmulator (int port) throws SocketException {
		 socket = new DatagramSocket (port);
		 
	}
	
	public synchronized String receivePacket () throws IOException {
		byte[] buffer = new byte[2000];
		
		DatagramPacket p = new DatagramPacket (buffer, buffer.length);
		
		socket.receive(p);
		
		dest = p.getSocketAddress();
		
		return new String (p.getData());
	}
	
	// send back to last received address
	public synchronized void sendPacket (String s) throws IOException {
		byte [] buffer = s.getBytes();
		
		DatagramPacket p = new DatagramPacket (buffer,buffer.length);
		
		p.setSocketAddress(dest);
		
		socket.send(p);
	}
	
	// send to specific  address
	public synchronized void sendPacket (SocketAddress addr, String s) throws IOException {
		byte [] buffer = s.getBytes();
		
		DatagramPacket p = new DatagramPacket (buffer,buffer.length);
		
		p.setSocketAddress(addr);
		
		socket.send(p);
	}
	
	public synchronized void close () {
		if (socket!=null) {
			socket.close();
			socket = null;
		}
	}
	
	public synchronized SocketAddress getLastReceivePacketAddress () {
		return dest;
	}
	
	public static void main (String[] args) throws IOException {
		UDPEmulator em = new UDPEmulator (5555);
		
		while (true)
		em.sendPacket( new InetSocketAddress (
				"132.205.46.179", 52020), "TEST TEST");
		
		
	}
}
