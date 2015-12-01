package hotelserver;

import HotelServerInterface.ErrorAndLogMsg;
import HotelServerInterface.ErrorAndLogMsg.MsgType;

public abstract class ServerBase implements HotelServerApp {

	int myServerID = -1;
	
	public int getMyID() {
		return myServerID;
	}
	
	@Override
	public boolean launchApp(int serverID) {
		
		//Set proper direction of streaming logs and errors
		ErrorAndLogMsg.addStream( MsgType.ERR, System.err);
		ErrorAndLogMsg.addStream(MsgType.WARN, System.out);
		ErrorAndLogMsg.addStream(MsgType.INFO, System.out);		
		
		myServerID = serverID;

		killApp();	
		
		return true;
	}
	
	@Override
	public boolean restartApp(int serverID) {

		myServerID = serverID;
		
		killApp();
		
		return launchApp(serverID);
	}

	public static boolean isWindows () {
		String OS = System.getProperty("os.name");
		
		return (OS.indexOf("Windows") >= 0);
	}
}
