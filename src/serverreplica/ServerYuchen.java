package serverreplica;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;


import org.omg.CORBA.ORB;

import DHRS_Corba.function;
import DHRS_Corba.functionHelper;
//import DHRS_FE.function;
//import DHRS_FE.functionHelper;
import HotelServerInterface.ErrorAndLogMsg;
import HotelServerInterface.ErrorAndLogMsg.ErrorCode;
import HotelServerInterface.IHotelServer.Record;
import HotelServerInterface.IHotelServer.RoomType;
import miscutil.SimpleDate;


public class ServerYuchen extends ServerBase {

	// CORBA remote objects
	function H1;
    function H2;
    function H3;
    
    // convert room type to String required by the application
    // First letter captital
    String convertRoomType (RoomType roomType) {
    	String s = roomType.toString();
    	return s.substring(0,1) + s.substring(1).toLowerCase();
    }
    
    int convertDate (SimpleDate date) {
    	String sDate = date.toString();
    	
    	return Integer.parseInt(sDate);
    }
    
	@Override
	public ErrorCode reserveRoom(
			String guestID, String hotelName, RoomType roomType, SimpleDate checkInDate, SimpleDate checkOutDate,
			long resID) {

		int iGuestID = Integer.valueOf(guestID);
		String sType = convertRoomType (roomType);
		int iCheckIn = convertDate (checkInDate);
		int iCheckOut = convertDate(checkOutDate);
		
		function hotel;
		
		switch (hotelName) {
		case "H1":
			hotel = H1; break;
		case "H2":
			hotel = H2; break;
		case "H3":
			hotel = H3; break;
		default:
			ErrorAndLogMsg.GeneralErr(ErrorCode.HOTEL_NOT_FOUND, "Invalid hotel:" + hotelName).printMsg();
			return ErrorCode.HOTEL_NOT_FOUND;
		}
				
		//TODO: potential error of long->int
		String result = hotel.reserveRoom(iGuestID, hotelName, sType, iCheckIn, iCheckOut, (int)resID);
		
		System.out.println("Server return results:" + result);

		if (result.indexOf("Successful")>=0)
			return ErrorCode.SUCCESS;
		else
			return ErrorCode.ROOM_UNAVAILABLE;
	}

	@Override
	public ErrorCode cancelRoom(
			String guestID, String hotelName, RoomType roomType, SimpleDate checkInDate, SimpleDate checkOutDate) {
		
		int iGuestID = Integer.valueOf(guestID);
		String sType = convertRoomType (roomType);
		int iCheckIn = convertDate (checkInDate);
		int iCheckOut = convertDate(checkOutDate);
		
		function hotel;
		
		switch (hotelName) {
		case "H1":
			hotel = H1; break;
		case "H2":
			hotel = H2; break;
		case "H3":
			hotel = H3; break;
		default:
			ErrorAndLogMsg.GeneralErr(ErrorCode.HOTEL_NOT_FOUND, "Invalid hotel:" + hotelName).printMsg();
			return ErrorCode.HOTEL_NOT_FOUND;
		}
				
		String result = hotel.cancelRoom(iGuestID, hotelName, sType, iCheckIn, iCheckOut);
		
		System.out.println("Server return results:" + result);

		if (result.indexOf("Successful")>=0)
			return ErrorCode.SUCCESS;
		else
			return ErrorCode.RECORD_NOT_FOUND;
	}

	@Override
	public ErrorCode transferRoom(
			String guestID, long reservationID, String hotelName, 
			String targetHotel) {
		
		int iGuestID = Integer.valueOf(guestID);
		
		function hotel;
		
		switch (hotelName) {
		case "H1":
			hotel = H1; break;
		case "H2":
			hotel = H2; break;
		case "H3":
			hotel = H3; break;
		default:
			ErrorAndLogMsg.GeneralErr(ErrorCode.HOTEL_NOT_FOUND, "Invalid hotel:" + hotelName).printMsg();
			return ErrorCode.HOTEL_NOT_FOUND;
		}
				
		//TODO: potential error of long->int
		//Use the original reservationID and omit the new one 
		String result = hotel.transferReservation(iGuestID, (int)reservationID, hotelName, targetHotel);
		
		System.out.println("Server return results:" + result);

		if (result.indexOf("Successful")>=0)
			return ErrorCode.SUCCESS;
		else
			return ErrorCode.ROOM_UNAVAILABLE;
	}

	@Override
	public ErrorCode checkAvailability(
			String guestID, String hotelName, RoomType roomType,
			SimpleDate checkInDate, SimpleDate checkOutDate,
			ReportSummary summary) {
		
		int iGuestID = Integer.valueOf(guestID);
		String sType = convertRoomType (roomType);
		int iCheckIn = convertDate (checkInDate);
		int iCheckOut = convertDate(checkOutDate);
		
		
		function hotel;
		
		switch (hotelName) {
		case "H1":
			hotel = H1; break;
		case "H2":
			hotel = H2; break;
		case "H3":
			hotel = H3; break;
		default:
			ErrorAndLogMsg.GeneralErr(ErrorCode.HOTEL_NOT_FOUND, "Invalid hotel:" + hotelName).printMsg();
			return ErrorCode.HOTEL_NOT_FOUND;
		}
		
		String res = hotel.checkAvailability(iGuestID, hotelName, sType, iCheckIn, iCheckOut);
		summary.summary = res;
		
		try {
			// The format of summary is like
			// return "Available "+Available+" Rent: "+rent;
			// grab room counts from it
			String k = "Available";
			int i = res.indexOf(k) + k.length();
			int j = res.indexOf("Rent:");
			String cnt = res.substring(i, j);
			summary.totalRoomCnt = Integer.valueOf(cnt);
			
			return ErrorCode.SUCCESS;
			
		} catch (Exception e) {
			ErrorAndLogMsg.ExceptionErr(e, "Wrong in check.");
			
			return ErrorCode.INVALID_REQUEST;
		}
		
	}
	
	
	@Override
	public ErrorCode getServiceReport (
			String hotelName, SimpleDate serviceDate,
			ReportSummary summary) {		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ErrorCode getStatusReport (
			String hotelName, SimpleDate date,
			ReportSummary summary)  {
		// TODO Auto-generated method stub
		return null;
	}
	
	private Process serverProcesses; // process of the three servers

	
	private static final String LAUNCH_CMD = "xterm -T 'YuchenHotelServer' -e java -classpath bin/ DHRS_Corba.DHRS_Server ";
	
	private static final String LAUNCH_FOLDER = "/home/gordon/workspace/DHRS-Yuchen/";
	
	@Override
	public boolean launchApp(int serverID) {
		
		if (!super.launchApp(serverID)) 
			return false;
		
		try {
			System.out.println("Launching Yuchen");
			Runtime runTime = Runtime.getRuntime();
			
			String curPath = System.getProperty("user.dir");
			
			serverProcesses = runTime.exec(
					LAUNCH_CMD + " " + serverID, // put serverID in the command line to different replia 
					null,
					new File(LAUNCH_FOLDER));
			
			System.out.println("Yuchen processes launched...");
			
			//Give some time for the servers to produce IOR files
			Thread.sleep(3000);
			
			String args[] = new String[0];
			
			ORB orb=ORB.init(args, null);
	        BufferedReader br1=new BufferedReader(new FileReader(LAUNCH_FOLDER + "ior1.txt"));
	        BufferedReader br2=new BufferedReader(new FileReader(LAUNCH_FOLDER + "ior2.txt"));
	        BufferedReader br3=new BufferedReader(new FileReader(LAUNCH_FOLDER + "ior3.txt"));
	        String ior1=br1.readLine();
	        String ior2=br2.readLine();
	        String ior3=br3.readLine();
	        br1.close();br2.close();br3.close();
	        org.omg.CORBA.Object o1=orb.string_to_object(ior1);
	        org.omg.CORBA.Object o2=orb.string_to_object(ior2);
	        org.omg.CORBA.Object o3=orb.string_to_object(ior3);
	        H1=functionHelper.narrow(o1);
	        H2=functionHelper.narrow(o2);
	        H3=functionHelper.narrow(o3);
	        
			return (H1!=null && H2!=null && H3!=null);
			
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
			
			if (myServerID>=0)
				proc = rt.exec(curPath + "/killyuchen.sh " + myServerID);
			else
				proc = rt.exec(curPath + "/killyuchen.sh");
			
			proc.waitFor();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			// ignore
		}
		
		return true;
	}

	@Override
	public boolean startIterateSnapShotRecords() {
		ErrorAndLogMsg.GeneralErr(ErrorCode.INVALID_REQUEST, "Bulk Sync From not supported.");
		return false;
	}

	@Override
	public Record getNextSnapShotRecord() {
		ErrorAndLogMsg.GeneralErr(ErrorCode.INVALID_REQUEST, "Bulk Sync From not supported.");
		return null;
	}

}
