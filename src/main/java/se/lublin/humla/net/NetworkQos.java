package se.lublin.humla.net;

import android.util.Log;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.SocketException;

/** Outgoing IP classification; the AP/driver decides the actual WMM category. */
final class NetworkQos {
    static final int VOICE_DSCP = 46;
    static final int SIGNALING_DSCP = 40;
    private static final String TAG = "HumlaQos";

    private NetworkQos() {}

    static int trafficClass(int previous, int dscp) {
        return (dscp << 2) | (previous & 3);
    }

    static void voice(DatagramSocket socket) {
        try {
            socket.setTrafficClass(trafficClass(socket.getTrafficClass(), VOICE_DSCP));
            Log.i(TAG, "UDP voice requested_dscp=46 socket_dscp=" + (socket.getTrafficClass() >> 2));
        } catch (SocketException | SecurityException error) {
            Log.w(TAG, "UDP voice marking failed; retaining OS defaults", error);
        }
    }

    static void tcp(Socket socket, boolean voiceTunneling) {
        int dscp = voiceTunneling ? VOICE_DSCP : SIGNALING_DSCP;
        try {
            socket.setTrafficClass(trafficClass(socket.getTrafficClass(), dscp));
            Log.i(TAG, "TCP voice_tunneling=" + voiceTunneling + " requested_dscp=" + dscp
                    + " socket_dscp=" + (socket.getTrafficClass() >> 2));
        } catch (SocketException | SecurityException error) {
            Log.w(TAG, "TCP marking failed; retaining OS defaults", error);
        }
    }
}
