package serverreplica;

import java.io.Serializable;
import java.rmi.RemoteException;
import java.util.List;

import HotelServerInterface.ErrorAndLogMsg.ErrorCode;
import HotelServerInterface.IHotelServer.Availability;
import HotelServerInterface.IHotelServer.Record;
import HotelServerInterface.IHotelServer.RoomType;
import miscutil.SimpleDate;

public interface HotelServerApp {
	
	/*	@SuppressWarnings("serial")
	public static class Availability implements Serializable {
		public String hotelName;
		public int availCount;
		public float rate;
	}
	
	// Complete information about a reservation
	@SuppressWarnings("serial")
	public static class Record implements Serializable {
		
	    public int resID;
		public String guestID; 
		public String shortName;
		public RoomType roomType;
		public SimpleDate checkInDate; 
		public SimpleDate checkOutDate;
		public float rate; // negative if not confirmed
		
		public Record () {
			super();
		}
		
		public Record (
		        int resID,
		        String guestID, 
				String shortName, 
				RoomType roomType,
				SimpleDate checkInDate,
				SimpleDate checkOutDate,
				float rate) {
		    this.resID = resID;
			this.guestID = guestID;
			this.shortName = shortName;
			this.roomType = roomType;
			this.checkInDate = checkInDate;
			this.checkOutDate = checkOutDate;
			this.rate = rate;
		}
		
		public Record (Record r) {
			this (r.resID, r.guestID, r.shortName, r.roomType, r.checkInDate, r.checkOutDate, r.rate);
		}
		
		public String toString() {
			String s = "Reservation ID:" + resID + "\n";
			s = s + "GuestID:" + guestID + "\n";
			s = s + "Hotel Short Name:" + shortName + "\n";
			s = s + "Room Type:" + roomType.toString() + "\n";
						
			s = s + "Check in Date:" + checkInDate + "\n";
			s = s + "Check out Date:" + checkOutDate + "\n";
			
			if (rate>0)
				s = s + "Rate:" + rate + "\n";
			
			return s;
		}
		
		public String toOneLineString() {
			String s = "ResID:" + resID;
			s = s + ";GuestID:" + guestID;
			s = s + ";Hotel:" + shortName;
			s = s + ";Type:" + roomType.toString();
						
			s = s + ";In:" + checkInDate;
			s = s + ";Out:" + checkOutDate;
			
			s = s + ";Rate:" + rate;
			
			return s;			
		}
		
	}
	
	*/
	
	public static class ReportSummary {
		public int totalRoomCnt;
		public String summary;
		
		@Override
		public String toString () {
			return "Total Room Cound:" + totalRoomCnt + "\n" + summary;
		}
	}
	
	public static class ReserveIDHolder {
		public long reserveID;
	}
		
	// Note: it is now not conformed with RMI interface to return long by parameter
	// this only works for CORBA
	public ErrorCode reserveRoom (
			String guestID, String hotelName, RoomType roomType, 
			SimpleDate checkInDate, SimpleDate checkOutDate, long resID);
	
	public ErrorCode cancelRoom (
			String guestID, String hotelName, RoomType roomType, 
			SimpleDate checkInDate, SimpleDate checkOutDate);
	
	public ErrorCode checkAvailability (
			String guestID, String hotelName, RoomType roomType,
			SimpleDate checkInDate, SimpleDate checkOutDate,
			ReportSummary summary);
	
	public ErrorCode transferRoom (
	        String guestID, long reservationID,
	        String hotelName, String targetHotel);
	
	public ErrorCode getServiceReport (
			String hotelName, SimpleDate serviceDate,
			ReportSummary summary);
	
	public ErrorCode getStatusReport (
			String hotelName, SimpleDate date,
			ReportSummary summary);
	
	public boolean launchApp(int serverID);
	
	public boolean restartApp(int serverID);
	
	public boolean killApp();
	
	public boolean startIterateSnapShotRecords();
	
	public Record getNextSnapShotRecord();
	
}
