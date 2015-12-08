package rm;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import HotelServerInterface.ErrorAndLogMsg;
import HotelServerInterface.ErrorAndLogMsg.ErrorCode;
import HotelServerInterface.ErrorAndLogMsg.MsgType;
import HotelServerInterface.IHotelServer.Record;
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
		
		if (bulkSyncFrom > 0) {
			if (!doBulkSyncFrom (bulkSyncFrom)) return false;
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
			
		case SYNC_REQUEST:
			return handleSyncRequest(msgRequest).encode();
		
		default:
			ErrorAndLogMsg.GeneralErr(ErrorCode.INVALID_REQUEST, 
					"Wrong request:" + msgRequest.encode());
				
		}
		
		return null;
	}
	

	private GeneralMessage handleSyncRequest(GeneralMessage msgRequest) {
		
		// Print out what ever RM Control message received
		System.out.println ("Received SYNC_REQUEST: \n" + msgRequest.encode());
		
		// Get information
		int toServerID = Integer.valueOf(msgRequest.getValue(PropertyName.SYNC_TO_SRV_ID));
		String toHost = msgRequest.getValue(PropertyName.SYNC_TO_SRV_ADDR);
		int toPort = Integer.valueOf(msgRequest.getValue(PropertyName.SYNC_TO_SRV_PORT));
		int fromServerID = Integer.valueOf(msgRequest.getValue(PropertyName.SYNC_FROM_SRV_ID));
		
		if (myID == fromServerID) {
			// Start a thread for the sync
			doBulkSyncTo(toServerID, new InetSocketAddress (toHost, toPort));
		} else {
			// Push the request message to queue
			if (!queueRMCtrlMsg.offer (msgRequest)) {
				
				// If it is full, there shall be stail control message.
				// Remove one and try again
				ErrorAndLogMsg.GeneralErr(
						ErrorCode.INTERNAL_ERROR, "RMCtrlQueue full").printMsg();
				queueRMCtrlMsg.poll();
				queueRMCtrlMsg.offer (msgRequest);
				//throw new RuntimeException ("Queue push error : " + type);
			}
		}

		// Build an ack
		GeneralMessage rsp = new GeneralMessage (MessageType.RESPOND);
		
		return rsp;
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
		final int TIMEOUT = 5;
		
		int attempts = 0;
		
		boolean received = false;
		
		MessageType requestType = msgRMCtrl.getMessageType();
		
		try {
			
			while (!received && attempts++ < MAX_ATTEMPTS) {
				receiver.sendRMControlMsg(myID, msgRMCtrl.encode());
			
				// Wait for a message coming in the queue
				GeneralMessage requst = //queueRMCtrlMsg.take();
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
	private boolean doBulkSyncFrom (int fromServerID) {
		
		
		class SyncReceiver {
			
			private Socket sock;
			
			BufferedReader in;
			
			public SyncReceiver (Socket sock) throws IOException {
				this.sock = sock;
				in = new BufferedReader(
				        new InputStreamReader(sock.getInputStream()));
			}
			
			public void close () throws IOException {
				in.close();
				sock.close();
			}
			
			// Receive from receiveSocket line by line
			// Until a complete GeneralMessage is received
			GeneralMessage receiveSyncMessage () throws IOException {
								
				// Read the first line as message Type
				String strType = in.readLine();
				MessageType type = MessageType.valueOf(strType);
				GeneralMessage msg = new GeneralMessage (type);
						
				// Read all property lines
				while (true) {
					String line = in.readLine();
					int i;
					if ( (i=line.indexOf(":")) > 0) {
						String property = line.substring(0, i);
						String value = line.substring(i+1);
						msg.setValue(PropertyName.valueOf(property), value);
					} else
						break;
				}
				
				// Have read a line without ":", suppose to be a breaker line
				
				return msg;				
			}
		};
	
		ServerSocket serverSocket = null;
		Socket receiveSocket = null;
		InetSocketAddress serverSockAddr;
		SyncReceiver receiver = null;
		
		try {
			//-------------------
			//Open a TCP port for receiving sync messages.
			//Start the port for listening
			serverSocket = new ServerSocket();
			serverSocket.bind(null);  // auto allocate port
			InetSocketAddress addr = (InetSocketAddress) serverSocket.getLocalSocketAddress();
			
			//-------------------
			//Send a SYNC_REQUEST to Sequencer, with fromServerID, toServerID (myself), and TCP listening address
			GeneralMessage msgSyncRequest = new GeneralMessage (MessageType.SYNC_REQUEST); 
			msgSyncRequest.setValue(PropertyName.SYNC_FROM_SRV_ID, String.valueOf(fromServerID));
			msgSyncRequest.setValue(PropertyName.SYNC_TO_SRV_ID, String.valueOf(myID));
			msgSyncRequest.setValue(PropertyName.SYNC_TO_SRV_ADDR, InetAddress.getLocalHost().getHostAddress() ); // TODO: how does it work for multiple network connections?
			msgSyncRequest.setValue(PropertyName.SYNC_TO_SRV_PORT, String.valueOf(addr.getPort()));
			this.initiateRMControlMessage(msgSyncRequest);
			
			//-------------------
			//Validate and accept a TCP connection from another server
			receiveSocket = serverSocket.accept();
			receiver = new SyncReceiver (receiveSocket);
			
			//TODO: some validation might be required
			
			//-------------------
			//Receive sync messages and invoke CORBA function correspondingly (Reserver, or cancel)
			boolean completed = false;
			while (!completed) {
				GeneralMessage msg = receiver.receiveSyncMessage();
				
				if (msg==null) {
					ErrorAndLogMsg.GeneralErr(ErrorCode.INVALID_REQUEST, 
							"Incomplete sync");
					break; // incomplete
				}
				else {
					switch (msg.getMessageType()) {
					case RESERVE:
						System.out.println ("Sync RESERVE received for resID:" + msg.getValue(PropertyName.RESID));
						this.handleReserve(0, msg); // use this reservation ID from property
						break;
					case CANCEL:
						this.handleCancel(msg);
						break;
					case SYNC_COMPLETE:
						// all done
						completed = true;
						break;
					default:
						//Shall not happen
						ErrorAndLogMsg.GeneralErr(ErrorCode.INVALID_REQUEST, 
								"Invalid message received during sync:" + msg.encode());
					}
				}
			}
			
			if (!completed) {
				receiver.close();
				serverSocket.close();
				return false;			
			}
			
			//-------------------
			//Upon receive of "Complete", now completed
			
			receiver.close();
			serverSocket.close();
			
			return true;
		} catch (Exception e) {
			ErrorAndLogMsg.ExceptionErr(e, "Exception when doing bulkSync From").printMsg();
			
			try {
				if (receiver!=null) receiver.close();
				if (serverSocket!=null) serverSocket.close();
			} catch (Exception e1) {}
			
			return false;
		}
	}
	
	Object bulkSyncLock = new Object();
	
	// Boolean flag that the bulk sync to another server has started
	boolean bulkSyncOngoing = false;
	
	// The list of requests message to be sent, 
	// which are received after the first round sync started.
	private List<GeneralMessage> toSyncList = new ArrayList<GeneralMessage>();

	
	// Do a bulk sync towards another server with serverID=toSeverID, TCP address = toSocketAddr
	private boolean doBulkSyncTo (final int toServerID, final InetSocketAddress toSocketAddr) {
		
		class SyncMessageBuilder {
			
			private GeneralMessage buildCommon (Record r) {
				GeneralMessage msg = new GeneralMessage (MessageType.RESERVE);
				
				msg.setValue(PropertyName.GUESTID, r.guestID);
				msg.setValue(PropertyName.HOTEL, r.shortName);
				msg.setValue(PropertyName.ROOMTYPE, r.roomType.toString());
				msg.setValue(PropertyName.CHECKINDATE, r.checkInDate.toString());
				msg.setValue(PropertyName.CHECKOUTDATE, r.checkOutDate.toString());
				
				return msg;
			}
			
			GeneralMessage buildSyncReserve (Record r) {

				GeneralMessage msg = buildCommon(r);
				msg.setValue(PropertyName.RESID, String.valueOf(r.resID));
				return msg;
			}
			
			GeneralMessage buildSyncCancel (Record r) {
				return buildCommon (r);
			}
		}
		//Start a new thread for all below steps
		new Thread () {
			@Override
			public void run() {
				
				Socket sendSocket = null;
				PrintWriter out = null;

				try {
					HotelServerApp app = (ResourceManager.this).activeApp;
					
					//-------------------
					//Open a TCP socket and connect to toSocketAddr
					sendSocket = new Socket ();
					sendSocket.connect(toSocketAddr);
					
					out = new PrintWriter(sendSocket.getOutputStream(), true);
	
					//-------------------
					//Get a frozen local snapshot, and mark the flag of bulkSyncOngoing
					//From now on, all received Reserve/Cancel will be also put into a toSyncList
					synchronized (bulkSyncLock) {
						if (! activeApp.startIterateSnapShotRecords()) {
							ErrorAndLogMsg.GeneralErr(ErrorCode.INTERNAL_ERROR, "Snap shot creation failure.");
							return;
						}
						
						bulkSyncOngoing = true;
					}
					
					//-------------------
					//Send reserver messages for the records in the frozen snapshot
					SyncMessageBuilder builder = new SyncMessageBuilder ();
					
					Record r;
					while ( (r = activeApp.getNextSnapShotRecord()) != null) {
						
						GeneralMessage msg = builder.buildSyncReserve(r);
						
						out.print(msg.encode());
						
						System.out.println("Sync sent for resID:" + r.resID);
												
						out.println("--"); // message separator
						
					}
					
					//-------------------
					//Send a PAUSE message to Sequencer to pause any further opertions.
					//Wait for a respons for PAUSE
					// TODO
					
					
					//-------------------
					//Iterate toSyncList and send Reserver/Cancel to the other server
					// TODO
					
					//-------------------
					//Send a "Complete" to the other server
					GeneralMessage msg = new GeneralMessage (MessageType.SYNC_COMPLETE);
					out.print(msg.encode());
					out.println("--");
					
					
					//-------------------
					//All done. Clear the toSyncList
					// TODO
					
					out.close();
					sendSocket.close();
					
				} catch (Exception e) {
					ErrorAndLogMsg.ExceptionErr(e, "Exception when doing bulk sync to.").printMsg();
					
					try {
						if (out!=null) out.close();
						if (sendSocket!=null) sendSocket.close();
					} catch (Exception e1) {}
					
					return;
				}
			}
		}.start();

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
		
			System.out.println ("Restarting HotelServer Apps with class" + newAppClass);
			
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

	
	private GeneralMessage handleReserve(long lresID, GeneralMessage request) {
				
		GeneralMessage reply = new GeneralMessage (MessageType.RESPOND);
		
		reply.setValue(PropertyName.SERVERID, String.valueOf(myID));
		
		ErrorCode err;
		
		// TODO , might have problem from long -> int
		int resID = (int) lresID;
		
		try {
			String guestID=request.getValue(PropertyName.GUESTID);
			String hotelName = request.getValue(PropertyName.HOTEL);
			RoomType roomType = getRoomType (request);
			SimpleDate checkInDate = getDate (request, PropertyName.CHECKINDATE);
			SimpleDate checkOutDate = getDate (request, PropertyName.CHECKOUTDATE);
			
			if (resID <= 0)
				//Use the reserveationID from property  (this is for the sync purpose)
				resID = Integer.valueOf(request.getValue(PropertyName.RESID));
						
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
					guestID, resID, hotelName, toHotelName); // use same reservation ID

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
