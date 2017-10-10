package com.scott.plugin;

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
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.util.Iterator;
import java.util.Locale;

/**
 * Implementation for SSDP service discovery. Sends a message on the standardized broadcast
 * address/port and listens to the responses. The service type to be looked up
 * is provided by the user.
 */
public class ServiceDiscovery extends CordovaPlugin implements Runnable {

    private static final String ADDRESS = "239.255.255.250";
    private static final int PORT = 1900;
    private static final int RECEIVE_PACKET_SIZE = 9216;
    private static final String REQUEST = String.format(Locale.US, "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: %s:%d\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "ST: %s\r\nMX: 2\r\n" +
        "\r\n", ADDRESS, PORT, "%s");

    volatile private CallbackContext mCallbackContext;
    volatile private String mServiceType;
    volatile private boolean mNormalizeHeaders;
    volatile private int mTimeout = 4000;
    volatile private boolean mBackgroundThreadActive;
    volatile private JSONObject mOldAnswers = new JSONObject();
    private static InetAddress sGroup;
    private MulticastSocket mMcs;


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

            mNormalizeHeaders = args.optBoolean(1, false);

            // Remove old answers, so we can give back everything again to the new listener.
            mOldAnswers = new JSONObject();

            // set default read timeout to 4 seconds.
            mTimeout = args.optInt(2, 4000);

            Log.i("ServiceDiscovery", String.format("#listen {mServiceType=\"%s\", "
                    + "mNormalizeHeaders=%b, mTimeout=%d, mBackgroundThreadActive=%b}",
                mServiceType, mNormalizeHeaders, mTimeout, mBackgroundThreadActive));

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
                broadcast();

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
            String usn = null;

            Iterator<String> i = answer.keys();

            while (i.hasNext()) {
                String key = i.next();

                if (key.compareToIgnoreCase("USN") == 0) {
                    usn = answer.optString(key, null);
                    break;
                }
            }

            if (usn == null || !mOldAnswers.has(usn))
            {
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
        if (sGroup == null) {
            sGroup = InetAddress.getByName(ADDRESS);
        }

        if (mMcs == null) {
            mMcs = new MulticastSocket(null);
            mMcs.joinGroup(sGroup);
            mMcs.setTimeToLive(4);
        }

        mMcs.setSoTimeout(mTimeout);
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
        mMcs.send(new DatagramPacket(request, request.length, sGroup, PORT));
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

        while (mCallbackContext != null) {
            try {
                byte[] data = new byte[RECEIVE_PACKET_SIZE];
                mMcs.receive(new DatagramPacket(data, data.length));

                result(convert(data, mNormalizeHeaders));
            } catch (SocketTimeoutException e) {
                break;
            }
        }
    }

    /**
     *  Checks, if the {@link MulticastSocket} is already closed, and if not, does close it.
     */
    private void close() {
        if (mMcs != null) {
            if (sGroup != null) {
                try {
                    mMcs.leaveGroup(sGroup);
                } catch (IOException e) {
                    // Ignore, closing anyway.
                }
            }

            mMcs.close();
            mMcs = null;
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
                String parts[] = line.split(":");

                if (parts.length > 1) {
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
}
