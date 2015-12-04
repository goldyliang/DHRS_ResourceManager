package rm;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import HotelServerInterface.ErrorAndLogMsg;
import HotelServerInterface.ErrorAndLogMsg.ErrorCode;
import HotelServerInterface.ErrorAndLogMsg.MsgType;
import HotelServerInterface.IHotelServer.RoomType;
import message.GeneralMessage;
import message.GeneralMessage.MessageType;
import message.GeneralMessage.PropertyName;
import miscutil.SimpleDate;
import sequencer.PacketHandler;
import sequencer.SequencedReceiver;
import serverreplica.HotelServerApp;
import serverreplica.HotelServerApp.ReportSummary;
import serverreplica.ServerBase;
import serverreplica.ServerGordon;
import serverreplica.ServerYuchen;

public class ResourceManager implements PacketHandler {
	
	private enum ReplicationMode {HA, FT}; // High availability mode, or fault tolerence mode
	
	static ReplicationMode replicaMode;
	
	private static final int TOLERANT_SUSPECTED_RESPOND = 2;

	private static final int TOLERANT_NO_RESPOND = 2;

	HotelServerApp activeApp;
	
	SequencedReceiver receiver;
	
	InetSocketAddress addrSequencer;
	
	int localPort;
	
	int myID;
	
	private final int SERVER_NUM = 3;
	

	public static List <ResourceManager> activeRMs = new ArrayList <ResourceManager> ();
	
	public ResourceManager (int port, InetSocketAddress addrSeq, int id) {

		localPort = port;
		addrSequencer = addrSeq;
		myID = id;
		
		//Set proper direction of streaming logs and errors
		ErrorAndLogMsg.addStream( MsgType.ERR, System.err);
		ErrorAndLogMsg.addStream(MsgType.WARN, System.out);
		ErrorAndLogMsg.addStream(MsgType.INFO, System.out);
	}
	
	public boolean startReceiver () {
		
		if (receiver!=null)
			receiver.stopReceiver();
		
		receiver = new SequencedReceiver(localPort, addrSequencer, this);
		
		receiver.startReceiver();
		
		return true;
	}
	
	Class <? extends HotelServerApp> appClass = null;

	/*
	 * Launch the hotel server application, with class appClass
	 * do bulk sync from server bulkSyncFrom (<0 if no buikSyncRequired)
	 */
	public boolean launchApp (
			Class <? extends HotelServerApp> appClass,
			int bulkSyncFrom) {
		
		this.appClass = appClass;

		boolean success = false;
		
		try {
			
			activeApp = appClass.newInstance();
			
			if (activeApp!=null)
				success = activeApp.launchApp(myID);
			else
				return false;
			
		} catch (InstantiationException | IllegalAccessException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		}
		
		if (!success) return false;
		
		activeRMs.add(this);
		
		if (bulkSyncFrom >=0) {
			if (!bulkSync (bulkSyncFrom)) return false;
		}
		
		GeneralMessage msgAddServer =
				new GeneralMessage (MessageType.ADD_SERVER);
		msgAddServer.setValue(PropertyName.SERVERID, String.valueOf(myID));
		
		return initiateRMControlMessage (msgAddServer);
	}
	
	
	public boolean stopApp () {
		if (receiver!=null)
			receiver.stopReceiver();
		
		boolean b = activeApp.killApp();
		
		activeRMs.remove(this);
		
		return b;
	}
	

	@Override
	public String handlePacket(long seqNum, String request) {
		
		GeneralMessage msgRequest = GeneralMessage.decode(request);
		
		switch (msgRequest.getMessageType()) {
		
		case RESERVE:
			return handleReserve (seqNum, msgRequest).encode();
			
		case CANCEL:
			return handleCancel (msgRequest).encode();
			
		case TRANSFER:
			return handleTransfer (msgRequest).encode();
			
		case CHECKAVAILABILITY:
			return handleCheckAvail (msgRequest).encode();
			
		case SERVICEREPORT:
		case STATUSREPORT:
			return handleReport (msgRequest).encode();
			
		case REPORT_SUSPECTED_RESPOND:
		case REPORT_NO_RESPOND:
			return handleErrorReport (msgRequest);
			
		case ADD_SERVER:
		case RMV_SERVER:
		case PAUSE:
		//case RESUME:
			return handleRMControlMsg (msgRequest).encode();
		
		default:
			ErrorAndLogMsg.GeneralErr(ErrorCode.INVALID_REQUEST, 
					"Wrong request:" + msgRequest.encode());
				
		}
		
		return null;
	}
	

	private int getNextServerID () {
		switch (myID) {
		case 1: return 2;
		case 2: return 3;
		case 3: return 1;
		default:
			throw new RuntimeException ("Wrong ID:" + myID);
		}
	}

	// Blocking FIFO queue for all message to be handled service restart
	// messages, during 
	// replaceApp process, bulk sync, etc
	ArrayBlockingQueue <GeneralMessage> queueRMCtrlMsg = 
			new ArrayBlockingQueue <GeneralMessage> (10);
	
	// Handle RM control message sent from Sequencer
	// (RMV_SERVER, ADD_SERVER, PAUSE, RESUME)
	// Return the ack needed to send back
	// Push the message in queueRMCtrlMsg
	private GeneralMessage handleRMControlMsg (GeneralMessage rmRequest) {
		
		MessageType type = rmRequest.getMessageType();
		
		if (type != MessageType.RMV_SERVER &&
			type != MessageType.ADD_SERVER &&
			type != MessageType.PAUSE )
			//type != MessageType.RESUME)
			return null;
		
		if (type == MessageType.ADD_SERVER) {
			// Clear the error counters for the new server
			int addedServer = Integer.valueOf(rmRequest.getValue(PropertyName.SERVERID));
			countSuspected [addedServer-1] = 0;
			countNoRespond [addedServer-1] = 0;
		}
		
		// Print out what ever RM Control message received
		System.out.println ("Received multi-casted RM control message: \n" + rmRequest.encode());
		
		// Build a ack
		GeneralMessage rsp = new GeneralMessage (MessageType.RESPOND);
		
		// Push the request message to queue
		if (!queueRMCtrlMsg.offer (rmRequest)) {
			
			// If it is full, there shall be stail control message.
			// Remove one and try again
			ErrorAndLogMsg.GeneralErr(
					ErrorCode.INTERNAL_ERROR, "RMCtrlQueue full").printMsg();
			queueRMCtrlMsg.poll();
			queueRMCtrlMsg.offer (rmRequest);
			//throw new RuntimeException ("Queue push error : " + type);
		}

		return rsp;
	}
	/*
	 * Initiate an RM control message (RMV_SERVER, ADD_SERVER, PAUSE, RESUME)
	 * Send RM control message to Sequencer
	 * and wait for the multi-cast RM control send back from Sequencer
	 * Retry if not receiving RM control multi-cast message from Sequencer
	 * Return true if the RM control message goes through
	 */
	private boolean initiateRMControlMessage (GeneralMessage msgRMCtrl) {
		
		final int MAX_ATTEMPTS = 1;
		final int TIMEOUT = 3;
		
		int attempts = 0;
		
		boolean received = false;
		
		MessageType requestType = msgRMCtrl.getMessageType();
		
		try {
			
			while (!received && attempts++ < MAX_ATTEMPTS) {
				receiver.sendRMControlMsg(myID, msgRMCtrl.encode());
			
				// Wait for a message coming in the queue
				GeneralMessage requst = //queueSrvRestartMsg.take();
						queueRMCtrlMsg.poll(TIMEOUT, TimeUnit.SECONDS);
				
				if (requst == null) {
					ErrorAndLogMsg.GeneralErr(ErrorCode.TIME_OUT, 
							"Not receiving RMCTRL respond:" + msgRMCtrl.getMessageType()).printMsg();
				} else if (requst.getMessageType() == requestType) {
					// we get the right message
					received = true;
					break;
				} else
					// wrong message, print the error
					ErrorAndLogMsg.GeneralErr(ErrorCode.MSG_DECODE_ERR, 
							"Received unexpected msg: " + requst.getMessageType()).printMsg();
			}
		} catch (InterruptedException e) {
			ErrorAndLogMsg.GeneralErr(ErrorCode.TIME_OUT,
					"Timeout when waiting for RM control respond - " + msgRMCtrl);
			return false;
		}
		
		return true;
	}
	
	
	// do a bulkSync to update local application, from another server with fromServerID
	// a PAUSE will be sent to Sequencer at a proper time to freeze the requests
	// to guarantee a complete sync
	private boolean bulkSync (int fromServerID) {
		return true;
	}
	
	private class ReplaceAppThread extends Thread {
		
		Class <? extends HotelServerApp> newAppClass;
		int bulkSyncFrom;
		
		public ReplaceAppThread (Class <? extends HotelServerApp> appClass,
									int bulkSyncFrom ) {
			this.newAppClass = appClass;
			this.bulkSyncFrom = bulkSyncFrom;
			
		}
		
		@Override public void run () {
			// Clear the message queue first
		
			
			queueRMCtrlMsg.clear();
		
			GeneralMessage msgRmvServer = 
					new GeneralMessage (MessageType.RMV_SERVER);
			
			msgRmvServer.setValue(PropertyName.SERVERID, String.valueOf(myID));
			
			initiateRMControlMessage (msgRmvServer);
			
			activeApp.killApp();
			
			boolean success = launchApp (newAppClass, bulkSyncFrom);
								
			if (success)
				System.out.println("Server started successfully: " + newAppClass);
			else
				System.out.println("Server start failure: " + newAppClass);
			
			// TODO: remove this to the sync control server
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
/*			GeneralMessage msgResume = 
					new GeneralMessage (MessageType.RESUME);
			initiateRMControlMessage (msgResume); */
		}
	}
	
	public boolean replaceApp (
		Class <? extends HotelServerApp> appClass,
		int bulkSyncFrom) {
		
		new ReplaceAppThread (appClass, bulkSyncFrom).start();
		return true;
	}
	
	

	private void launchNewRM (
			final Class <? extends HotelServerApp> appClass,
			final int restartServerID) {
		
		final String rmCmdLine;
		
		if (ServerBase.isWindows())
			rmCmdLine = "cmd.exe /K start \"NewResourceManager\" java -classpath bin/ rm.ResourceManager ";
		else 
			rmCmdLine = "xterm -T NewResourceManager -e java -classpath bin/ rm.ResourceManager ";
		
		final String rmCmdFolder = System.getProperty("user.dir");

		
		new Thread () {
			@Override
			public void run () {
				
				// Send remove server
				queueRMCtrlMsg.clear();
				
				GeneralMessage msgRmvServer = 
						new GeneralMessage (MessageType.RMV_SERVER);
				
				msgRmvServer.setValue(PropertyName.SERVERID, String.valueOf(restartServerID));
				
				initiateRMControlMessage (msgRmvServer);
				
				
				// TODO: remove... just for testing
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					
				}
				
				try {
					System.out.println("Launching new RM");
					Runtime runTime = Runtime.getRuntime();
					
					/*Example of arguments:
					 * String [] args = {
							"hotelserver.ServerGordon", // app class
							"1",  // server ID
							"2000", // localport
							"localhost", // sequencer address
							"4000",  // sequencer port
							//optional sync from which server
					};*/
					
					String arguments = " " + appClass.getName() 
							+ " " + restartServerID 
							+ " " + myID + " " // Let it sync from me
							+ addrSequencer.getHostString() 
							+ " " + addrSequencer.getPort()
							+ " " + replicaMode; // use the same replication mode as me
					
					String cmd = rmCmdLine + arguments;
					
					runTime.exec(cmd, null, new File (rmCmdFolder) );
					
					System.out.println("New RM Launched.");
								
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}.start();
		

	}
	
	private int [] countSuspected = new int [SERVER_NUM];
	private int [] countNoRespond = new int [SERVER_NUM];


	private String handleErrorReport (GeneralMessage errorReport) {
		
		// a logic to determine whether we need to restart local application
		// , or, start another local application (besides the current one)
		
		// Get ServerID of the error report.
		int serverFailure = Integer.valueOf(errorReport.getValue(PropertyName.SERVERID));
		
		if (serverFailure <1 || serverFailure > SERVER_NUM) {
			throw new RuntimeException ("Wrong server ID:" + serverFailure);
		}
		
		boolean restartLocal = false;
		boolean launchNew = false; // whether to launch a new RM and application in local machine
		//int serverFailure = 0; // the failure server ID going to be re-launched
		
		switch (errorReport.getMessageType()) {
		
		case REPORT_SUSPECTED_RESPOND:
			// Increment counter of accumulated suspected response (for each server)
			countSuspected [serverFailure - 1] ++;

			// If ever received two or more suspected response 
			if (countSuspected [serverFailure - 1] >= TOLERANT_SUSPECTED_RESPOND) {
			
				// If the failure server is myself, trigger a restart process
				// Otherwise, ignore and let the right server handle it
				// The counter gets reset when receiving ADD_SERVER for it
				if (serverFailure == this.myID) {
					
					// TODO: determine which server version to start
					
					if (replicaMode == ReplicationMode.FT) {
						// For fault tolerance mode, need to change to another application class
						// Otherwise, keep current class
						if (appClass.equals(ServerGordon.class))
							appClass = ServerYuchen.class;
						else
							appClass = ServerGordon.class;
					}
	
					restartLocal = true;
				}
			}
			break;
		case REPORT_NO_RESPOND:
			// Increment counter of accumulated suspected response (for each server)
			countNoRespond [serverFailure - 1] ++;
						
			// If ever received two or more suspected response 
			if (countNoRespond [serverFailure - 1] >= TOLERANT_NO_RESPOND) {
			
				// It is reported that there is a server with no respond
				// Now check whether I am the first-order deligate (the failure one is my next)
				
				// The counter gets reset when receiving ADD_SERVER for it

				if (getNextServerID() == serverFailure) {
					// If yes, I am responsible to launch a RM in my server
					
					launchNew = true;	
				}
			}
			break;
						
		default:
			throw new RuntimeException (
					"Wrong report type: " + errorReport.getMessageType());
		}
		
		if (restartLocal) {
			replaceApp (appClass, getNextServerID());
		} else if (launchNew) {
			// If launch a new RM in this machine, always run the same app as mine
			launchNewRM (appClass, serverFailure);
		}
		
		GeneralMessage ret = new GeneralMessage (MessageType.RESPOND);
		return ret.encode();
	}
	
	static RoomType getRoomType (GeneralMessage request) {
		String typ = request.getValue(PropertyName.ROOMTYPE);
		
		typ = typ.toUpperCase();
		
		return RoomType.valueOf(typ);
	}
	
	static SimpleDate getDate (GeneralMessage request, PropertyName prop) {
		try {
			String dtStr = request.getValue(prop);
			if (dtStr == null)
				throw new RuntimeException ("Date not correct:" + request);
			return SimpleDate.parse(dtStr);
		} catch (Exception e) {
			ErrorAndLogMsg.ExceptionErr(e, "Exception when getting data:" + request).printMsg();;
			return null;
		}
	}
	
	static long getResID (GeneralMessage request) {
		String id = request.getValue(PropertyName.RESID);
		
		return Long.valueOf(id);
	}

	
	private GeneralMessage handleReserve(long seqNum, GeneralMessage request) {
				
		GeneralMessage reply = new GeneralMessage (MessageType.RESPOND);
		
		reply.setValue(PropertyName.SERVERID, String.valueOf(myID));
		
		ErrorCode err;
		
		int resID = (int) seqNum;
		
		try {
			String guestID=request.getValue(PropertyName.GUESTID);
			String hotelName = request.getValue(PropertyName.HOTEL);
			RoomType roomType = getRoomType (request);
			SimpleDate checkInDate = getDate (request, PropertyName.CHECKINDATE);
			SimpleDate checkOutDate = getDate (request, PropertyName.CHECKOUTDATE);
						
			err = activeApp.reserveRoom(
					guestID, hotelName, roomType, checkInDate, checkOutDate, resID);

		} catch (Exception e) {
			ErrorAndLogMsg.ExceptionErr(e, "Exception when reserver - request:" 
					+ request.encode()).printMsg();
			err = ErrorCode.EXCEPTION_THROWED;
		}
		
		if (err == ErrorCode.SUCCESS)
			reply.setValue(PropertyName.RESID, String.valueOf(resID));
		else
			reply.setValue(PropertyName.RESID, "-1");
		
		return reply;
		
	}
	
	private GeneralMessage handleCancel (GeneralMessage request) {
		
		GeneralMessage reply = new GeneralMessage (MessageType.RESPOND);
		reply.setValue(PropertyName.SERVERID, String.valueOf(myID));

		
		ErrorCode err;
				
		try {
			String guestID=request.getValue(PropertyName.GUESTID);
			String hotelName = request.getValue(PropertyName.HOTEL);
			RoomType roomType = getRoomType (request);
			SimpleDate checkInDate = getDate (request, PropertyName.CHECKINDATE);
			SimpleDate checkOutDate = getDate (request, PropertyName.CHECKOUTDATE);
						
			err = activeApp.cancelRoom (
					guestID, hotelName, roomType, checkInDate, checkOutDate);

		} catch (Exception e) {
			ErrorAndLogMsg.ExceptionErr(e, "Exception when reserver - request:" 
					+ request.encode()).printMsg();
			err = ErrorCode.EXCEPTION_THROWED;
		}
		
		if (err == ErrorCode.SUCCESS)
			// The returned RESID is a fake ID. It just indicate
			// success or not > 0 if success.
			// (the current server interface does not return
			// a real server ID)
			reply.setValue(PropertyName.RESID, "1");
		else
			reply.setValue(PropertyName.RESID, "-1");
		
		return reply;
		
	}
	
	private GeneralMessage handleTransfer (GeneralMessage request) {
		
		GeneralMessage reply = new GeneralMessage (MessageType.RESPOND);
		reply.setValue(PropertyName.SERVERID, String.valueOf(myID));

		
		ErrorCode err;
		
		long resID = 0;
				
		try {
			String guestID=request.getValue(PropertyName.GUESTID);
			String hotelName = request.getValue(PropertyName.HOTEL);
			String toHotelName = request.getValue(PropertyName.OTHERHOTEL);
			resID = getResID(request);
			
						
			err = activeApp.transferRoom(
					guestID, resID, hotelName, toHotelName, 
					resID); // use same reservation ID

		} catch (Exception e) {
			ErrorAndLogMsg.ExceptionErr(e, "Exception when reserver - request:" 
					+ request.encode()).printMsg();
			err = ErrorCode.EXCEPTION_THROWED;
		}
		
		if (err == ErrorCode.SUCCESS)
			reply.setValue(PropertyName.RESID, String.valueOf(resID)); // ID not changed
		else
			reply.setValue(PropertyName.RESID, "-1");
		
		return reply;
		
	}
	
	private GeneralMessage handleCheckAvail (GeneralMessage request) {
		
		GeneralMessage reply = new GeneralMessage (MessageType.RESPOND);
		reply.setValue(PropertyName.SERVERID, String.valueOf(myID));

		
		ErrorCode err;
		
		ReportSummary summary=new ReportSummary();
		summary.summary = "";
		
		try {
			String guestID=request.getValue(PropertyName.GUESTID);
			String hotelName = request.getValue(PropertyName.HOTEL);
			RoomType roomType = getRoomType (request);
			SimpleDate checkInDate = getDate (request, PropertyName.CHECKINDATE);
			SimpleDate checkOutDate = getDate (request, PropertyName.CHECKOUTDATE);
						
			err = activeApp.checkAvailability(
					guestID, hotelName, roomType, checkInDate, checkOutDate, summary);

		} catch (Exception e) {
			ErrorAndLogMsg.ExceptionErr(e, "Exception when reserver - request:" 
					+ request.encode()).printMsg();
			err = ErrorCode.EXCEPTION_THROWED;
		}
		
		if (err == ErrorCode.SUCCESS) {
			reply.setValue(PropertyName.ROOMSCOUNT, String.valueOf(summary.totalRoomCnt));
			reply.setValue(PropertyName.AVALIABLITY, summary.summary);
		}
		else {
			reply.setValue(PropertyName.ROOMSCOUNT, "0");
			reply.setValue(PropertyName.AVALIABLITY, "ERROR, code=" + err);
		}
		
		return reply;
		
	}
	
	// Handle both Service Report and Status Report
	private GeneralMessage handleReport (GeneralMessage request) {
		
		GeneralMessage reply = new GeneralMessage (MessageType.RESPOND);
		reply.setValue(PropertyName.SERVERID, String.valueOf(myID));

		
		ErrorCode err = ErrorCode.SUCCESS;
		
		ReportSummary summary=new ReportSummary();
		summary.summary = "";
		
		MessageType requestType = request.getMessageType();
		
		try {
			String hotelName = request.getValue(PropertyName.HOTEL);
			
			SimpleDate date;
			
			if (requestType == MessageType.SERVICEREPORT) {
				date = getDate (request, PropertyName.CHECKOUTDATE);
				
				err = activeApp.getServiceReport(hotelName, date, summary);
			} else if (requestType == MessageType.STATUSREPORT) {
				date = getDate (request, PropertyName.DATE);
				
				err = activeApp.getStatusReport(hotelName, date, summary);
			} else
				throw new RuntimeException ("Wrong report request:" + request);

		} catch (Exception e) {
			ErrorAndLogMsg.ExceptionErr(e, "Exception when reserver - request:" 
					+ request.encode()).printMsg();
			err = ErrorCode.EXCEPTION_THROWED;
		}
		
		PropertyName prop = (requestType == MessageType.SERVICEREPORT ?
				PropertyName.SERVICEREPORT : PropertyName.PRINTSTATUS);
		
		if (err == ErrorCode.SUCCESS) {
			reply.setValue(PropertyName.ROOMSCOUNT, String.valueOf(summary.totalRoomCnt));
			reply.setValue(prop, summary.summary);
		}
		else {
			reply.setValue(PropertyName.ROOMSCOUNT, "0");
			reply.setValue(prop, "Error code:" + err);
		}
		
		return reply;
		
	}
	
	// arg[0] - application version name (class path)
	// arg[1] - serverID
	// arg[2] - whether to do bulksync from a server (server ID if exist, 0 otherwise)
	// arg[3] - Sequencer address
	// arg[4] - Sequencer port
	// arg[5] - replication mode, "HA", or "FT"
	
	public static void main (String[] args) {
		
	    ClassLoader classLoader = ResourceManager.class.getClassLoader();

	    Class <? extends HotelServerApp> appClass = null;
	    
		try {
	        Class loaded = classLoader.loadClass(args[0]);
	        
	        if (HotelServerApp.class.isAssignableFrom(loaded)) {
	        	appClass = (Class <? extends HotelServerApp>) loaded;
	        } else {
	        	System.err.println(args[0] + " not implements" 
	        			+ HotelServerApp.class.toString());
	        	return;
	        }
	    } catch (ClassNotFoundException e) {
	        e.printStackTrace();
	        return;
	    }
		
		int serverID = Integer.valueOf(args[1]);
		int localPort = 0;
		String seqAddr = args[3];
		int seqPort = Integer.valueOf(args[4]);
		
		int syncFromServer = Integer.valueOf(args[2]);
		replicaMode = ReplicationMode.valueOf(args[5]);
		
		
		ResourceManager rm = new ResourceManager (
				localPort, 
				new InetSocketAddress(seqAddr, seqPort),
				serverID);
		
		rm.startReceiver();

		rm.launchApp(appClass, syncFromServer);
		
		
	}
	
}
