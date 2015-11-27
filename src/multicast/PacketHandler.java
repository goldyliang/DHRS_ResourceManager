package multicast;

public interface PacketHandler {
    // handle a request stored in request, process and return 
	// a respond stored in String
	// seqNum is the sequence of this request (would be used as reservationID, etc)
    public String handlePacket (long seqNum, String request);
}
