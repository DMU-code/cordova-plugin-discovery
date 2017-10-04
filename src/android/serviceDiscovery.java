package com.scott.plugin;

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
 * Implementation for SSDP service discovery. It sends a message on the standardized broadcast
 * address/port and listens to the responses. The service type to be looked up
 * is provided by the user.
 */
public class ServiceDiscovery extends CordovaPlugin implements Runnable {

    private static final String ADDRESS = "239.255.255.250";
    private static final int PORT = 1900;
    private static final int TIMEOUT = 4000;
    private static final int RECEIVE_PACKET_SIZE = 1536;
    private static final String REQUEST = String.format(Locale.US, "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: %s:%d\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "ST: %s\r\nMX: 2\r\n" +
        "\r\n", ADDRESS, PORT, "%s");

    volatile private CallbackContext mCallbackContext;
    volatile private String mServiceType;
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
     * Your JavaScript success callback will possibly receive multiple callbacks (each with a new
     * set of server answers) until all available servers have answered.
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
     *  stopped within 4 seconds (the read timeout).
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
            mOldAnswers = new JSONObject();

            if (!mBackgroundThreadActive) cordova.getThreadPool().execute(this);

            return true;
        }

        if (action.equals("stop")) {
            mCallbackContext = null;

            callbackContext.success();

            return true;
        }

        return false;
    }

    /**
     * Called by Cordova after page reload.
     *
     * Tells an eventually running background thread to give up working by removing the
     * {@link #mCallbackContext}.
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

                JSONObject answers = receive();

                removeKeys(answers, mOldAnswers);

                if (answers.length() > 0) {
                    PluginResult result = new PluginResult(PluginResult.Status.OK, answers);
                    result.setKeepCallback(true);
                    mCallbackContext.sendPluginResult(result);
                }

                merge(mOldAnswers, answers);

            } catch (IOException e) {
                e.printStackTrace();

                PluginResult result = new PluginResult(PluginResult.Status.ERROR, e.getMessage());
                result.setKeepCallback(true);
                mCallbackContext.sendPluginResult(result);
            }
        }

        close();

        mBackgroundThreadActive = false;
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
            mMcs.setSoTimeout(TIMEOUT);
        }
    }

    /**
     * Broadcasts the SSDP M-SEARCH query. Transparently tries to open and configure a
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
     * Will listen 4 seconds for answers from SSDP servers. Transparently tries to open and
     * configure a {@link MulticastSocket}, if not done, yet.
     *
     * @return A {@link JSONObject} keyed by received USNs containing {@link JSONObject}s with all
     * HTTP header responses received during that time.
     * @throws IOException if an I/O exception occurs while opening the {@link MulticastSocket}.
     */
    private JSONObject receive() throws IOException {
        open();

        JSONObject answers = new JSONObject();

        while (mCallbackContext != null) {
            try {
                byte[] data = new byte[RECEIVE_PACKET_SIZE];
                mMcs.receive(new DatagramPacket(data, data.length));

                merge(answers, convert(data));
            } catch (SocketTimeoutException e) {
                break;
            }
        }

        return answers;
    }

    /**
     * Converts the contents of a received byte buffer into a string and then breaks down the
     * contained HTTP headers.
     *
     * See <a href="https://de.wikipedia.org/wiki/Simple_Service_Discovery_Protocol"
     * >Wikipedia: Simple Service Discovery Protocol</a> for an example of an SSDP response.
     * @param data
     *            A byte buffer.
     * @return A {@link JSONObject} with one key: "USN", containing another {@link JSONObject}
     * containing all headers.
     */
    private JSONObject convert(byte[] data) {
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

                    try {
                        headers.put(parts[0].trim(), value.toString());
                    } catch (JSONException e) {
                        // This should not happen.
                        e.printStackTrace();
                    }
                }
            }

        } catch (UnsupportedEncodingException e) {
            // Ignore, Android supports UTF-8 since API 1.
        }

        if (headers.has("USN")) {
            try {
                return new JSONObject().put(headers.getString("USN"), headers);
            } catch (JSONException e) {
                // This should not happen.
                e.printStackTrace();
            }
        }

        return null;
    }

    /**
     * Removes al keys in the first {@link JSONObject}, which are also in the second and following
     * {@link JSONObject}s.
     *
     * @param target
     *            The object to modify.
     * @param sources
     *            The objects, which keys are used.
     */
    private void removeKeys(JSONObject target, JSONObject... sources) {
        for (JSONObject s : sources) {
            Iterator<String> i = s.keys();

            while (i.hasNext()) {
                String key = i.next();

                if (target.has(key)) target.remove(key);
            }
        }
    }

    /**
     * <p>
     * Merges the second and following {@link JSONObject}s into the first {@link JSONObject}.
     * </p>
     * <p>
     * The last object with identical keys will win!
     * </p>
     *
     * @param target
     *            The target which gets all the content. Can be NULL.
     *            (which makes no sense to use, though.)
     * @param sources
     *            The source objects from where the content is copied into the target. Objects can
     *            be null.
     */
    private void merge(JSONObject target, JSONObject... sources) {
        if (target == null) return;

        for (JSONObject s : sources) {
            if (s != null) {
                Iterator<String> i = s.keys();

                while (i.hasNext()) {
                    String key = i.next();

                    try {
                        target.put(key, s.get(key));
                    } catch (JSONException e) {
                        // This should not happen.
                        e.printStackTrace();
                    }
                }
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
}
