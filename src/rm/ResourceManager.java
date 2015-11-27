package rm;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.rmi.RemoteException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

import HotelServerInterface.ErrorAndLogMsg;
import HotelServerInterface.ErrorAndLogMsg.ErrorCode;
import HotelServerInterface.ErrorAndLogMsg.MsgType;
import HotelServerInterface.IHotelServer.RoomType;
import hotelserver.HotelServerApp;
import hotelserver.ServerGordon;
import message.GeneralMessage;
import message.GeneralMessage.MessageType;
import message.GeneralMessage.PropertyName;
import miscutil.SimpleDate;
import multicast.PacketHandler;
import multicast.SequencedReceiver;

public class ResourceManager implements PacketHandler {
	
	HotelServerApp activeApp;
	
	SequencedReceiver receiver;
	
	InetSocketAddress addrSequencer;
	
	int localPort;
	
	int myID;
	
	private final int SERVER_NUM = 3;
	
	private final String RM_CMD_LINE = "rm";
	
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
	/*
	 * Launch the hotel server application, with class appClass
	 * do bulk sync from server bulkSyncFrom (<0 if no buikSyncRequired)
	 */
	public boolean launchApp (
			Class <? extends HotelServerApp> appClass,
			int bulkSyncFrom) {
		try {
			
			activeApp = appClass.newInstance();
			
			if (activeApp!=null)
				activeApp.launchApp(myID);
			else
				return false;
			
		} catch (InstantiationException | IllegalAccessException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		}
		
		activeRMs.add(this);

		if (bulkSyncFrom >=0) {
			return bulkSync (bulkSyncFrom);
		} else {
			return true;
		}
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
			//TODO: others...
		case REPORT_SUSPECTED_RESPOND:
		case REPORT_NO_RESPOND:
			return handleErrorReport (msgRequest);
		}
		
		return null;
	}
	
	/*
	 * Initiate an RM control message (RMV_SERVER, ADD_SERVER, PAUSE, RESUME)
	 * Send RM control message to Sequencer
	 * and wait for the multi-cast RM control send back from Sequencer
	 * Retry if not receiving RM control multi-cast message from Sequencer
	 * Return true if the RM control message goes through
	 */
	private boolean initiateRMControlMessage (GeneralMessage msgRMCtrl) {
		
		final int MAX_ATTEMPTS = 3;
		
		int attempts = 0;
		
		boolean received = false;
		
		while (!received && attempts++ < MAX_ATTEMPTS) {
			receiver.sendRMControlMsg(msgRMCtrl.encode());
		
			//TODO wait for a flag indicating the same RMControl received
			//set received = true if received, 
			// false if timeout
			received = true;
			
			if (received)
				return true;
		}
		
		return false;
	}
	
	private int getNextServerID () {
		return (myID + 1) % SERVER_NUM;
	}
	
	// do a bulkSync to update local application, from another server with fromServerID
	// a PAUSE will be sent to Sequencer at a proper time to freeze the requests
	// to guarantee a complete sync
	private boolean bulkSync (int fromServerID) {
		return true;
	}
	
	public Thread replaceApp (
			final Class <? extends HotelServerApp> appClass,
			final int bulkSyncFrom) {
		
		Thread th =
			new Thread () {
				@Override public void run() {
					GeneralMessage msgRmvServer = 
							new GeneralMessage (MessageType.RMV_SERVER);
					
					msgRmvServer.setValue(PropertyName.SERVERID, String.valueOf(myID));
					
					initiateRMControlMessage (msgRmvServer);
					
					activeApp.killApp();
					launchApp (appClass, bulkSyncFrom);
										
					GeneralMessage msgAddServer =
							new GeneralMessage (MessageType.ADD_SERVER);
					msgAddServer.setValue(PropertyName.SERVERID, String.valueOf(myID));
					
					initiateRMControlMessage (msgAddServer);
					
					GeneralMessage msgResume = 
							new GeneralMessage (MessageType.RESUME);
					initiateRMControlMessage (msgResume);
				}
			};
			
		th.start();
		
		return th;
	}
	
	private void launchNewRM (
			final Class <? extends HotelServerApp> appClass,
			final int bulkSyncFrom) {
		// TODO
		
		try {
			System.out.println("Launching new RM");
			Runtime runTime = Runtime.getRuntime();
			
			runTime.exec(RM_CMD_LINE);
			
			System.out.println("New RM Launched.");
						
		} catch (IOException e) {
			e.printStackTrace();
		}

	}
	
	private String handleErrorReport (GeneralMessage errorReport) {
		
		// a logic to determine whether we need to restart local application
		// , or, start another local application (besides the current one)
		
		boolean restartLocal = true;
		final Class <? extends HotelServerApp> appClass = ServerGordon.class;
		
		boolean launchNew = false; // whether to launch a new RM and application in local machine
		int serverFailure = 0; // the failure server ID going to be re-launched
		
		if (restartLocal) {
			replaceApp (appClass, getNextServerID());
		} else if (launchNew) {
			launchNewRM (appClass, serverFailure);
		}
		
		GeneralMessage ret = new GeneralMessage (MessageType.RESPOND);
		return ret.encode();
	}
	
	RoomType getRoomType (GeneralMessage request) {
		return RoomType.valueOf(request.getValue(PropertyName.ROOMTYPE));
	}
	
	static SimpleDate getDate (GeneralMessage request, PropertyName prop) throws ParseException {
		String dtStr = request.getValue(prop);
		return SimpleDate.parse(dtStr);
	}
	
	private GeneralMessage handleReserve(long seqNum, GeneralMessage request) {
				
		try {
			String guestID=request.getValue(PropertyName.GUESTID);
			String hotelName = request.getValue(PropertyName.HOTEL);
			RoomType roomType = RoomType.valueOf(request.getValue(PropertyName.ROOMTYPE));
			SimpleDate checkInDate = getDate (request, PropertyName.CHECKINDATE);
			SimpleDate checkOutDate = getDate (request, PropertyName.CHECKOUTDATE);
			
			int resID = (int) seqNum;
			
			ErrorCode err = ErrorCode.SUCCESS;
			
			err = activeApp.reserveRoom(
					guestID, hotelName, roomType, checkInDate, checkOutDate, resID);

			
			GeneralMessage reply = new GeneralMessage (MessageType.RESPOND);
			
			reply.setValue(PropertyName.RETCODE, err.toString());
			
			return reply;

		} catch (Exception e) {
			ErrorAndLogMsg.ExceptionErr(e, "Exception when reserver - request:" 
					+ request.encode()).printMsg();
			return null;
		}
		
	}
	
	// arg[0] - application version name (class file?)
	// arg[1] - serverID
	// arg[2] - local port
	// arg[3] - Sequencer address
	// arg[4] - Sequencer port
	// arg[5] - whether to do bulksync from a server (server ID if exist)
	public static void main (String[] args) {
		
		Class <? extends HotelServerApp> appClass = null;
		
	    ClassLoader classLoader = ResourceManager.class.getClassLoader();

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
		int localPort = Integer.valueOf(args[2]);
		String seqAddr = args[3];
		int seqPort = Integer.valueOf(args[4]);
		
		int syncFromServer = (args.length>=6 ? Integer.valueOf(args[5]) : -1);
		
		ResourceManager rm = new ResourceManager (
				localPort, 
				new InetSocketAddress(seqAddr, seqPort),
				serverID);
		
		rm.launchApp(appClass, rm.getNextServerID());
		
		rm.startReceiver();
		
		
		if (syncFromServer >=0) {
			rm.bulkSync(syncFromServer);
		}
		
	}
	
}
