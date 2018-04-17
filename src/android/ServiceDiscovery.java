package com.scott.plugin;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.text.TextUtils;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.Iterator;
import java.util.Locale;

/**
 * Implementation for SSDP service discovery. Sends a message on the standardized broadcast
 * address/port and listens to the responses. The service type to be looked up
 * is provided by the user.
 */
public class ServiceDiscovery extends CordovaPlugin implements Runnable {

    private static final String MULTICAST_ADDRESS = "239.255.255.250";
    private static final int MULTICAST_PORT = 1900;
    private static final int RECEIVE_PACKET_SIZE = 9216;
    private static final String TYPE = "__TYPE__";
    private static final String TYPE_MSEARCH = "M-SEARCH";
    private static final String TYPE_NOTIFY = "NOTIFY";
    private static final String TYPE_RESPONSE = "RESPONSE";
    private static final String TYPE_UNKNOWN = "UNKNOWN";

    private static final String REQUEST = String.format(Locale.US,
        "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: %s:%d\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "ST: %s\r\n" +
        "MX: 2\r\n" +
        "\r\n", MULTICAST_ADDRESS, MULTICAST_PORT, "%s"
    );

    volatile private CallbackContext mCallbackContext;
    volatile private String mServiceType;
    volatile private boolean mBroadcastMsearch = true;
    volatile private boolean mListenForNotifies;
    volatile private boolean mNormalizeHeaders;
    volatile private int mTimeout = 4000;
    volatile private boolean mBackgroundThreadActive;
    volatile private JSONObject mOldAnswers = new JSONObject();
    private static InetAddress localInetAddress;
    private MulticastSocket multicastSocket;
    private WifiManager.MulticastLock mMulticastLock;
    private DatagramSocket datagramSocket;
    private SocketAddress multicastGroup;


    /**
     * Called after plugin construction and fields have been initialized.
     */
    protected void pluginInitialize() {
        super.pluginInitialize();

        WifiManager wm = (WifiManager) cordova.getActivity().getApplicationContext()
            .getSystemService(Context.WIFI_SERVICE);

        if (wm != null) mMulticastLock = wm.createMulticastLock("SERVICE_DISCOVERY_LOCK");
    }

    /**
     * <p>
     * Implements two actions:
     * </p>
     * <dl>
     * <dt>
     * listen
     * </dt>
     * <dd>
     * <p>
     * Listen for SSDP server discovery answers.
     * </p>
     * <p>
     * You need to provide a proper SSDP service type as first argument.
     * </p>
     * <p>
     * This will continuously send out a SSDP "M-SEARCH" discovery request and then listen for
     * answers - basically forever or until the page is left or reloaded.
     * </p>
     * <p>
     * Your JavaScript success callback will receive multiple callbacks (each with a new server
     * answer) until all available servers have answered.
     * </p>
     * <p>
     * Different errors in networking can happen which will call the error callback argument of
     * your JavaScript call. Errors don't mean, that this plugin will stop listening. You have to
     * explicitly call "stop" to achieve this!
     * </p>
     * <p>
     * Succeeding calls to this action will overwrite the preceding call. In other words: Only the
     * last caller will receive callbacks!
     * </p>
     * </dd>
     * <dt>
     * stop
     * </dt>
     * <dd>
     * <p>
     * Stops listening for SSDP server discovery answers.
     * </p>
     * <p>
     * You will immediately stop receiving updates to your listener. The background thread will be
     * stopped after read timeout. (4 seconds per default).
     * </p>
     * <p>
     * It is safe to call this multiple times and before any call to "listen".
     * </p>
     * </dd>
     * </dl>
     *
     * @param action          The action to execute.
     * @param args            The exec() arguments.
     * @param callbackContext The callback context used when calling back into JavaScript.
     * @return                whether the action was valid.
     * @throws JSONException  if needed arguments are not provided.
     */
    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext)
        throws JSONException {

        if (action.equals("listen")) {
            mCallbackContext = callbackContext;
            mServiceType = args.getString(0);
            mBroadcastMsearch = args.optBoolean(1, true);
            mListenForNotifies = args.optBoolean(2, false);

            mNormalizeHeaders = args.optBoolean(3, false);

            // Remove old answers, so we can give back everything again to the new listener.
            mOldAnswers = new JSONObject();

            // set default read timeout to 4 seconds.
            mTimeout = args.optInt(4, 4000);

            Log.i("ServiceDiscovery", String.format("#listen {mServiceType=\"%s\", "
                    + "mBroadcastMsearch=%b, mListenForNotifies=%b, "
                    + "mNormalizeHeaders=%b, mTimeout=%d, mBackgroundThreadActive=%b}",
                mServiceType, mBroadcastMsearch, mListenForNotifies, mNormalizeHeaders, mTimeout,
                mBackgroundThreadActive));

            if (!mBackgroundThreadActive) cordova.getThreadPool().execute(this);

            return true;
        }

        if (action.equals("stop")) {
            mCallbackContext = null;

            Log.i("ServiceDiscovery", String.format("#stop {backgroundThreadActive=%b}",
                mBackgroundThreadActive));

            callbackContext.success();

            return true;
        }

        return false;
    }

    /**
     * <p>
     * Called by Cordova after page reload.
     * </p>
     * <p>
     * Tells an eventually running background thread to give up working by removing the
     * {@link #mCallbackContext}.
     * </p>
     */
    @Override
    public void onReset() {
        super.onReset();

        mCallbackContext = null;
    }

    /**
     * Thread for {@link #execute(String, JSONArray, CallbackContext)} action=listen.
     */
    @Override
    public void run() {
        mBackgroundThreadActive = true;

        while (mCallbackContext != null) {
            try {
                if (mBroadcastMsearch) broadcast();

                receive();
            } catch (IOException e) {
                e.printStackTrace();

                PluginResult result = new PluginResult(PluginResult.Status.ERROR, e.getMessage());
                result.setKeepCallback(true);
                mCallbackContext.sendPluginResult(result);

                // Keep loop frequency below 1/s.
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e1) {
                    break;
                }
            }
        }

        close();

        mBackgroundThreadActive = false;
    }

    /**
     * Sends the answer of a server to a M-SEARCH request to the callback of the last caller of
     * {@link #execute(String, JSONArray, CallbackContext)} action=listen, if it doesn't contain a
     * USN or if the USN was not seen before.
     *
     * @param answer A dictionary containing the answer of an SSDP server.
     */
    private void result(JSONObject answer) {
        if (mCallbackContext != null && answer != null) {
            String type = null;
            String nt = null;
            String usn = null;

            Iterator<String> i = answer.keys();

            while (i.hasNext()) {
                String key = i.next();

                if (key.compareToIgnoreCase(TYPE) == 0) {
                    type = answer.optString(key, null);
                }
                else if (key.compareToIgnoreCase("NT") == 0) {
                    nt = answer.optString(key, null);
                }
                else if (key.compareToIgnoreCase("USN") == 0) {
                    usn = answer.optString(key, null);
                    break;
                }
            }

            if (TYPE_UNKNOWN.equals(type)) {
                // We don't understand this. Probably doesn't make sense.
                return;
            }

            if (TYPE_MSEARCH.equals(type)) {
                // We're not interested in M-SEARCH requests. (Probably our own, anyway.)
                return;
            }

            if (TYPE_NOTIFY.equals(type) && (
                !mListenForNotifies ||
                (!"ssdp:all".equals(mServiceType) && !mServiceType.equals(nt))
            )) {
                // That's strange stuff from devices we don't want - ignore.
                return;
            }

            if (usn == null || !mOldAnswers.has(usn))
            {
                answer.remove(TYPE);

                PluginResult result = new PluginResult(PluginResult.Status.OK, answer);
                result.setKeepCallback(true);
                mCallbackContext.sendPluginResult(result);

                Log.i("ANSWER", answer.toString());

                if (usn != null) try {
                    mOldAnswers.put(usn, answer);
                } catch (JSONException e) {
                    // This should not happen.
                    e.printStackTrace();
                }
            }
        }
    }


    /**
     * Checks, if a {@link MulticastSocket} is already opened, and if not, does open and configure
     * it.
     *
     * @throws IOException if an I/O exception occurs while opening the {@link MulticastSocket}.
     */
    private void open() throws IOException {
        if (localInetAddress == null) {
            WifiManager wifiMgr = (WifiManager) cordova.getActivity().getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo wifiInfo = wifiMgr.getConnectionInfo();
            int ip = wifiInfo.getIpAddress();
            byte[] ipAddress = convertIpAddressToString(ip);
            localInetAddress = InetAddress.getByAddress(ipAddress);
        }

        if (multicastSocket == null) {
            multicastGroup = new InetSocketAddress(MULTICAST_ADDRESS, MULTICAST_PORT);
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(localInetAddress);

            multicastSocket = new MulticastSocket(MULTICAST_PORT);
            multicastSocket.joinGroup(multicastGroup, networkInterface);

            datagramSocket = new DatagramSocket(null);
            datagramSocket.setReuseAddress(true);
            datagramSocket.bind(new InetSocketAddress(localInetAddress, 0));
        }

        datagramSocket.setSoTimeout(mTimeout);
    }

    /**
     * Broadcasts the SSDP M-SEARCH request. Transparently tries to open and configure a
     * {@link MulticastSocket}, if not done, yet.
     *
     * @throws IOException if an I/O exception occurs while opening the {@link MulticastSocket}.
     */
    private void broadcast() throws IOException {
        open();

        byte[] request = String.format(Locale.US, REQUEST, mServiceType).getBytes();
        datagramSocket.send(new DatagramPacket(request, request.length, multicastGroup));

    }

    /**
     * <p>
     * Will listen for answers from SSDP servers. Transparently tries to open and
     * configure a {@link MulticastSocket}, if not done, yet.
     * </p>
     * <p>
     * When an answer was received, sends it immediately to the calling JavaScript.
     * </p>
     *
     * @throws IOException if an I/O exception occurs while opening the {@link MulticastSocket}.
     */
    private void receive() throws IOException {
        open();

        if (mMulticastLock != null) mMulticastLock.acquire();

        while (mCallbackContext != null) {
            try {
                byte[] data = new byte[RECEIVE_PACKET_SIZE];
                datagramSocket.receive(new DatagramPacket(data, data.length));

                result(convert(data, mNormalizeHeaders));
            } catch (SocketTimeoutException e) {
                break;
            }
        }

        if (mMulticastLock != null) mMulticastLock.release();
    }

    /**
     *  Checks, if the {@link MulticastSocket} is already closed, and if not, does close it.
     */
    private void close() {
        if (multicastSocket != null) {
            if (localInetAddress != null) {
                try {
                    multicastSocket.leaveGroup(localInetAddress);
                } catch (IOException e) {
                    // Ignore, closing anyway.
                }
            }

            multicastSocket.close();
            multicastSocket = null;

            datagramSocket.close();
            datagramSocket = null;
        }
    }

    /**
     * <p>
     * Converts the contents of a received byte buffer into a string and then breaks down the
     * contained HTTP headers.
     * </p>
     * <p>
     * See <a href="https://de.wikipedia.org/wiki/Simple_Service_Discovery_Protocol"
     * >Wikipedia: Simple Service Discovery Protocol</a> for an example of an SSDP response.
     * </p>
     * <p>
     * Will capitalize headers, if requested to do so.
     * </p>
     * @param data
     *            A byte buffer.
     * @param normalizeHeaders
     *            If headers should be capitalized.
     * @return A {@link JSONObject} containing all headers.
     */
    private static JSONObject convert(byte[] data, boolean normalizeHeaders) {
        JSONObject headers = new JSONObject();

        try {
            String answer = new String(data, "UTF-8").trim();

            for (String line : answer.split("\r")) {
                if (!line.contains(":")) {
                    if (line.trim().length() < 1) continue;

                    String header = line.toUpperCase(Locale.US);

                    try {
                        if (header.contains("M-SEARCH")) {
                            headers.put(TYPE, TYPE_MSEARCH);
                        } else if (header.contains("NOTIFY")) {
                            headers.put(TYPE, TYPE_NOTIFY);
                        } else if (header.contains("OK")) {
                            headers.put(TYPE, TYPE_RESPONSE);
                        } else {
                            headers.put(TYPE, TYPE_UNKNOWN);
                        }
                    } catch (JSONException e) {
                        // This should not happen.
                        e.printStackTrace();
                    }
                }
                else {
                    String parts[] = line.split(":");
                    StringBuilder value = new StringBuilder();

                    for (int i = 1; i < parts.length; i++) {
                        value.append(parts[i].trim());

                        if (i < parts.length - 1) value.append(":");
                    }

                    String key = parts[0].trim();

                    if (normalizeHeaders) key = capitalize(key);

                    try {
                        headers.put(key, value.toString());
                    } catch (JSONException e) {
                        // This should not happen.
                        e.printStackTrace();
                    }
                }
            }

        } catch (UnsupportedEncodingException e) {
            // Ignore, Android supports UTF-8 since API 1.
        }

        return headers;
    }

    /**
     * Capitalize HTTP header properly.
     *
     * @param string
     *            A HTTP header.
     * @return a capitalized HTTP header.
     */
    private static String capitalize(String string) {
        String[] parts = string.split("-");

        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() > 0) {
                parts[i] = parts[i].substring(0, 1).toUpperCase()
                    + parts[i].substring(1).toLowerCase();
            }
        }

        return TextUtils.join("-", parts);
    }

    /**
     * Converts ip's int notation to string notation
     *
     * @param ip
     * @return
     */
    private static byte[] convertIpAddressToString(int ip) {
        return new byte[] {
                (byte) (ip & 0xFF),
                (byte) ((ip >> 8) & 0xFF),
                (byte) ((ip >> 16) & 0xFF),
                (byte) ((ip >> 24) & 0xFF)};
    }

}
