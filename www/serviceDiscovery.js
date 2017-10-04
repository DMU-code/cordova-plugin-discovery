/*global cordova, module*/
module.exports = {

    /**
     * Listen for SSDP server discovery answers.
     *
     * You need to provide a proper SSDP service type as first argument.
     *
     * This will continuously send out a SSDP "M-SEARCH" discovery request and then listen for answers
     * - basically forever or until the page is left or reloaded.
     *
     * Your success callback will possibly receive multiple callbacks (each with a new set of
     * server answers) until all available servers have answered.
     *
     * Different errors in networking can happen which will call the error callback argument.
     * Errors don't mean, that this plugin will stop listening. You have to explicitly
     * call {@link stop} to achieve this!
     *
     * Succeeding calls to this method will overwrite the preceding call. In other words: Only the last
     * caller will receive callbacks!
     *
     * @param {string} serviceType
     *            A valid SSDP service type.
     * @param {listenCallback} successCallback
     *            Callback to receive SSDP server answers.
     * @param {errorCallback} errorCallback
     *            Callback to receive error messages.
     */
    listen: function (serviceType, successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'ServiceDiscovery', 'listen', [serviceType]);
    },

    /**
     * Stops listening for SSDP server discovery answers.
     *
     * You will immediately stop receiving updates to your listener. The background thread will be
     * stopped within 4 seconds (the read timeout).
     *
     * It is safe to call this multiple times and before any call to {@link listen}.
     *
     * @param {stopCallback} successCallback
     *            Callback to indicate successful execution.
     */
    stop: function (successCallback) {
        cordova.exec(successCallback, null, 'ServiceDiscovery', 'stop', []);
    }

    /**
     * Callback for {@link listen}, containing SSDP server answers.
     *
     * @callback listenCallback
     * @param {Object<string, Object<string, string>>} answers
     *            A map of SSDP servers and their answers. The key is the USN, the map for each key contains the
     *            returned headers per USN.
     */

    /**
     * Callback for {@link listen}, containing an error message.
     *
     * @callback errorCallback
     * @param {string} error
     *            An error message.
     */

    /**
     * Callback for {@link stop}. Just announces the successful execution of the native part, which is quite inevitable,
     * since that part is really just one line of code.
     *
     * @callback stopCallback
     */
};
