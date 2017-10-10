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
     * Your JavaScript success callback will receive multiple callbacks (each with a new server answer)
     * until all available servers have answered.
     *
     * Different errors in networking can happen which will call the error callback argument.
     * Errors don't mean, that this plugin will stop listening. You have to explicitly
     * call {@link stop} to achieve this!
     *
     * Succeeding calls to this method will overwrite the preceding call. In other words: Only the last
     * caller will receive callbacks!
     *
     * @param {string} serviceType
     *            A valid SSDP service type. (e.g. "urn:schemas-upnp-org:service:ContentDirectory:1", "ssdp:all",
     *            "urn:schemas-upnp-org:service:AVTransport:1")
     * @param {listenCallback} successCallback
     *            Callback to receive SSDP server answers.
     * @param {errorCallback} errorCallback
     *            Callback to receive error messages.
     * @param {boolean=} normalizeHeaders
     *            Set true, if you want capitalized headers. If false, headers will be passed unmodified (default).
     * @param {number=} readTimeout
     *            Read timeout in milliseconds. (DEFAULT: 4000) Will send a new "M-SEARCH" request after this time.
     */
    listen: function (serviceType, successCallback, errorCallback, normalizeHeaders, readTimeout) {
        var args = [serviceType];

        if (typeof normalizeHeaders === 'boolean') {
            args.push(normalizeHeaders);
        }

        if (typeof readTimeout === 'number') {
            args.push(readTimeout);
        }

        cordova.exec(successCallback, errorCallback, 'ServiceDiscovery', 'listen', args);
    },

    /**
     * Stops listening for SSDP server discovery answers.
     *
     * You will immediately stop receiving updates to your listener. The background thread will be
     * stopped after read timeout. (4 seconds per default).
     *
     * It is safe to call this multiple times and before any call to {@link listen}.
     *
     * @param {stopCallback=} successCallback
     *            Callback to indicate successful execution.
     */
    stop: function (successCallback) {
        cordova.exec(successCallback, null, 'ServiceDiscovery', 'stop', []);
    }

    /**
     * Callback for {@link listen}, containing one SSDP server answer.
     *
     * @callback listenCallback
     * @param {Object<string, string>} answer
     *            A map of a SSDP server answer.
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
