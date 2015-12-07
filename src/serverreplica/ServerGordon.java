package serverreplica;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import Client.HotelClient;
import HotelServerInterface.ErrorAndLogMsg;
import HotelServerInterface.ErrorAndLogMsg.ErrorCode;
import HotelServerInterface.ErrorAndLogMsg.MsgType;
import HotelServerInterface.IHotelServer.Availability;
import HotelServerInterface.IHotelServer.Record;
import HotelServerInterface.IHotelServer.RoomType;
import miscutil.SimpleDate;
import serverreplica.HotelServerApp.ReportSummary;


public class ServerGordon extends ServerBase {

	HotelClient clientProxy;
	
	static Process nameServerProcess;
	
	static {
		// run this once when the class loads
		try {
			nameServerProcess = Runtime.getRuntime().exec("orbd -ORBInitialPort 1050");
			
			Thread.sleep (1000); // wait for ordb service ready
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		} 
	}
			
	
	@Override
	public ErrorCode reserveRoom(
			String guestID, String hotelName, RoomType roomType, SimpleDate checkInDate, SimpleDate checkOutDate,
			long resID)  {

		//TODO: potential error for long -> int;
		ErrorAndLogMsg m = clientProxy.reserveHotel(
				guestID, hotelName, roomType, checkInDate, checkOutDate, 
				(int)resID);
		
		System.out.print("RESERVE INFO:");
		m.print(System.out);
		System.out.println("");
		
		return m.errorCode();
	}

	@Override
	public ErrorCode cancelRoom(
			String guestID, String hotelName, RoomType roomType, SimpleDate checkInDate, SimpleDate checkOutDate)
			 {
		ErrorAndLogMsg m = clientProxy.cancelHotel(
				guestID, hotelName, roomType, checkInDate, checkOutDate);
		
		System.out.print("CANCEL INFO:");
		m.print(System.out);
		System.out.println("");
		
		return m.errorCode();
	}

	@Override
	public ErrorCode checkAvailability (
			String guestID, String hotelName, RoomType roomType,
			SimpleDate checkInDate, SimpleDate checkOutDate,
			ReportSummary summary) {
		
		Record rec = new Record (
				0,
				guestID,
				hotelName,
				roomType,
				checkInDate,
				checkOutDate,
				0);
		
		List <Availability> avails = new ArrayList <Availability> ();
		
		ErrorAndLogMsg m = clientProxy.checkAvailability(rec, avails);
		
		if (m==null || !m.anyError()) {
			StringBuilder sum = new StringBuilder();
			sum.append("Availablity:\n");
			
			summary.totalRoomCnt = 0;
			
			for (Availability avail : avails) {
				sum.append(avail.toString());
				sum.append("\n");
				summary.totalRoomCnt += avail.availCount;
			}
			
			summary.summary = sum.toString();
			
			System.out.println ("Got availability:");
			System.out.println (summary);
			
			return ErrorCode.SUCCESS;
		} else {
			m.printMsg();
			return m.errorCode();
		}

	}

	@Override
	public ErrorCode transferRoom(
			String guestID, long reservationID, String hotelName, 
			String targetHotel) {
		
		//TODO: potential error of long->int if ID is large
		ErrorAndLogMsg m = clientProxy.transferRoom(
				guestID, (int)reservationID, hotelName, 
				
				// The RoomType and checkin/out dates are not going to be checked,
				// Just fill some non-null values
				RoomType.SINGLE, new SimpleDate(), new SimpleDate(),
				targetHotel, (int)reservationID);
		
		System.out.print("TRANSFER INFO:");
		m.print(System.out);
		System.out.println("");
		
		return m.errorCode();
	}

	@Override
	public ErrorCode getServiceReport (
			String hotelName, SimpleDate serviceDate,
			ReportSummary summary) {
		
		// Temporary solution by hard-coding user name and password
		ErrorAndLogMsg m = clientProxy.loginAsManager(hotelName, "manager", "pass");

		
		if (m.anyError()) {
			m.print(System.out);
			System.out.println();
			return m.errorCode();
		}
		
		Collection <Record> records = new ArrayList <Record> ();
		
		m = clientProxy.getServiceReport(
				hotelName, serviceDate, records);
		
		if (m==null || !m.anyError()) {
			StringBuilder sum = new StringBuilder();
			sum.append("Service Report:\n");
			
			summary.totalRoomCnt = records.size();
			
			for (Record rec : records) {
				sum.append(rec.toString());		
				sum.append("\n======\n");
			}
			
			summary.summary = sum.toString();
			
			System.out.println ("Got service report:");
			System.out.println (summary);
			
			return ErrorCode.SUCCESS;
		} else {
			m.printMsg();
			return m.errorCode();
		}

	}

	@Override
	public ErrorCode getStatusReport (
			String hotelName, SimpleDate date,
			ReportSummary summary)  {
		
		// Temporary solution by hard-coding user name and password
		ErrorAndLogMsg m = clientProxy.loginAsManager(hotelName, "manager", "pass");

		
		if (m.anyError()) {
			m.print(System.out);
			System.out.println();
			return m.errorCode();
		}
		
		Collection <Record> records = new ArrayList <Record> ();
		
		m = clientProxy.getStatusReport(
				hotelName, date, records);
		
		if (m==null || !m.anyError()) {
			StringBuilder sum = new StringBuilder();
			sum.append("Status Report:\n");
			
			summary.totalRoomCnt = records.size();
			
			for (Record rec : records) {
				sum.append(rec.toString());		
				sum.append("\n======\n");
			}
			
			summary.summary = sum.toString();
			
			System.out.println ("Got status report:");
			System.out.println (summary);
			
			return ErrorCode.SUCCESS;
		} else {
			m.printMsg();
			return m.errorCode();
		}
	}
	
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
	
	private static String getLaunchCmd (String configFile, int serverID) {
		String hotelName = configFile.substring(configFile.lastIndexOf(".")+1);
		String title = hotelName + "-Replication-" + serverID;

		String javaCmd = " java -classpath bin/ HotelServer.HotelServer " 
				+ configFile + " " + serverID;
		
		if (isWindows())
			return "cmd.exe /K start \"Replica#" + serverID + "-" + hotelName + "\"" + javaCmd; // put replication ID ahead in order to use taskkill /FI "Replica#n*"
		else
			return "xterm -T " + title + " -e " + javaCmd;
	}

	
	private static final String LAUNCH_FOLDER = System.getProperty("user.dir") + "/../DistributedHotelReservation";
	
	@Override
	public boolean launchApp(int serverID) {
		
		if (!super.launchApp(serverID)) 
			return false;
		
		try {
			System.out.println("Launching Gordon");
			Runtime runTime = Runtime.getRuntime();
			
			String curPath = System.getProperty("user.dir");
			
			serverProcesses[0] = runTime.exec(
					getLaunchCmd ("config.properties.H1", serverID),
					null,
					new File(LAUNCH_FOLDER));
			//waitForProcessOutput (serverProcesses[0], "Server running");

			
			serverProcesses[1] = runTime.exec(
					getLaunchCmd ("config.properties.H2", serverID),
					null,
					new File(LAUNCH_FOLDER));
			//waitForProcessOutput (serverProcesses[1], "Server running");

			serverProcesses[2] = runTime.exec(
					getLaunchCmd ("config.properties.H3", serverID),
					null,
					new File(LAUNCH_FOLDER));
			//waitForProcessOutput (serverProcesses[2], "Server running");

			
			System.out.println("Gordon processes launched...");
			
			String regHost = "localhost";
			int regPort = 1050;
			
			System.out.println ("Initilization in progress...\n");
			
			clientProxy = new HotelClient(serverID);
			
			ErrorAndLogMsg m = clientProxy.Initialize(regHost, regPort);
			
			//Give some time for the servers to register to name service
			Thread.sleep(3000);
			
			return (m==null || !m.anyError());
			
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}

	}
	
	@Override
	public boolean killApp() {
		
		Runtime rt = Runtime.getRuntime();
		String curPath = System.getProperty("user.dir");

		try {
			
			Process proc;
			
			if (!isWindows()) {
			
/*				String pattern = "'^(?!.*xterm.*).*java.*HotelServer.*";
				if (myServerID > 0)
					pattern = pattern + myServerID;
				
				pattern = pattern + "$'";
				
				String cmd = "ps -ef | grep -P " + pattern + " | tr -s ' ' | cut -f2 -d' ' | xargs kill -9";
*/
				String cmd = curPath + "/killgordon.sh";
				if (myServerID > 0)
					cmd = cmd + " " + myServerID;
				
				proc = rt.exec(cmd);
				
			} else {
				if (myServerID > 0)
					proc = rt.exec("taskkill /FI \"WINDOWTITLE eq Replica#" + myServerID + "*\"");
				else
					proc = rt.exec("taskkill /FI \"WINDOWTITLE eq Replica#*");
			}
			
			proc.waitFor();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			// ignore
		}
		
		return true;
	}

	private Collection <Record> recSnapshot = null;
	private Iterator <Record> iterSnapshot = null;
	
	@Override
	public boolean startIterateSnapShotRecords() {
		
		// TODO Auto-generated method stub
		Collection <Record> records = new ArrayList <Record> ();
		ErrorAndLogMsg m = clientProxy.getRecordSnapshot(records);
		
		if (m!=null && m.anyError())
			return false;
		else {
			iterSnapshot = records.iterator();
			return true;
		}
	}

	@Override
	public Record getNextSnapShotRecord() {
		// TODO Auto-generated method stub
		if (iterSnapshot == null)
			return null;
		
		if (iterSnapshot.hasNext())
			return iterSnapshot.next();
		else
			return null;
	}

}
