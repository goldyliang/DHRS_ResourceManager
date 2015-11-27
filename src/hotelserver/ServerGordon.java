package hotelserver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.rmi.RemoteException;
import java.util.List;

import Client.HotelClient;
import HotelServerInterface.ErrorAndLogMsg;
import HotelServerInterface.ErrorAndLogMsg.ErrorCode;
import HotelServerInterface.ErrorAndLogMsg.MsgType;
import HotelServerInterface.IHotelServer.RoomType;
import miscutil.SimpleDate;


public class ServerGordon implements HotelServerApp {

	HotelClient clientProxy;
	
	int myServerID = -1;
	
	@Override
	public ErrorCode reserveRoom(
			String guestID, String hotelName, RoomType roomType, SimpleDate checkInDate, SimpleDate checkOutDate,
			int resID) throws RemoteException {

		ErrorAndLogMsg m = clientProxy.reserveHotel(
				guestID, hotelName, roomType, checkInDate, checkOutDate, 
				resID);
		
		System.out.print("RESERVE INFO:");
		m.print(System.out);
		System.out.println("");
		
		return m.errorCode();
	}

	@Override
	public ErrorCode cancelRoom(String guestID, RoomType roomType, SimpleDate checkInDate, SimpleDate checkOutDate)
			throws RemoteException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<Availability> checkAvailability(String guestID, RoomType roomType, SimpleDate checkInDate,
			SimpleDate checkOutDate) throws RemoteException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ErrorCode transferRoom(String guestID, int reservationID, RoomType roomType, SimpleDate checkInDate,
			SimpleDate checkOutDate, String targetHotel, int newResID) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Record[] getServiceReport(SimpleDate serviceDate) throws RemoteException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Record[] getStatusReport(SimpleDate date) throws RemoteException {
		// TODO Auto-generated method stub
		return null;
	}
	
	private Process nameServerProcess;
	private Process[] serverProcesses = new Process[3]; // process of the three servers

	// wait until process has output "pattern"
	boolean waitForProcessOutput (Process proc, String pattern) {

		BufferedReader stdInput = new BufferedReader(new 
		     InputStreamReader(proc.getInputStream()));

		while (true) {
			String s;
			try {
				s = stdInput.readLine();
				if (s.contains("pattern"))
					return true;
			} catch (IOException e) {
				e.printStackTrace();
			}

		}
	}
	
	@Override
	public boolean launchApp(int serverID) {
		
		myServerID = serverID;

		killApp();		
		
		try {
			System.out.println("Launching Gordon");
			Runtime runTime = Runtime.getRuntime();
			
			nameServerProcess = runTime.exec("orbd -ORBInitialPort 1050");
			String curPath = System.getProperty("user.dir");
			
			serverProcesses[0] = runTime.exec(
					"xterm -e java -classpath bin/ HotelServer.HotelServer config.properties.Gordon " + serverID,
					null,
					new File("/home/gordon/workspace/DHRS/DistributedHotelReservation"));
			//waitForProcessOutput (serverProcesses[0], "Server running");

			
			serverProcesses[1] = runTime.exec(
					"xterm -e java -classpath bin/ HotelServer.HotelServer config.properties.Star " + serverID,
					null,
					new File("/home/gordon/workspace/DHRS/DistributedHotelReservation"));
			//waitForProcessOutput (serverProcesses[1], "Server running");

			serverProcesses[2] = runTime.exec(
					"xterm -e java -classpath bin/ HotelServer.HotelServer config.properties.Motel " + serverID,
					null,
					new File("/home/gordon/workspace/DHRS/DistributedHotelReservation"));
			//waitForProcessOutput (serverProcesses[2], "Server running");

			
			System.out.println("Gordon processes launched...");
			
			//Give some time for the servers to register to name service
			//Thread.sleep(5000);
			
			//Set proper direction of streaming logs and errors
			ErrorAndLogMsg.addStream( MsgType.ERR, System.err);
			ErrorAndLogMsg.addStream(MsgType.WARN, System.out);
			ErrorAndLogMsg.addStream(MsgType.INFO, System.out);			
			
			String regHost = "localhost";
			int regPort = 1050;
			
			System.out.println ("Initilization in progress...\n");
			
			clientProxy = new HotelClient(serverID);
			
			ErrorAndLogMsg m = clientProxy.Initialize(regHost, regPort);
			
			return (m==null || !m.anyError());
			
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}

	}
	
	@Override
	public boolean restartApp(int serverID) {

		myServerID = serverID;
		
		killApp();
		
		return launchApp(serverID);
	}

	@Override
	public boolean killApp() {
		
		Runtime rt = Runtime.getRuntime();
		String curPath = System.getProperty("user.dir");

		try {
			
			Process proc;
			
			if (myServerID>=0)
				proc = rt.exec(curPath + "/killgordon.sh " + myServerID);
			else
				proc = rt.exec(curPath + "/killgordon.sh");
			
			proc.waitFor();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			// ignore
		}
		
		return true;
	}

}
