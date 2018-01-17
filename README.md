# Cordova Service Discovery

Simple plugin to get any SSDP / UPnP / DLNA service on a local network

## Using
Clone the plugin

    $ git clone https://github.com/scottdermott/cordova-plugin-discovery.git

Create a new Cordova Project

    $ cordova create myApp com.example.myApp MyApp

Add Android platform

    cordova platform add android
    cordova platform add ios
    
Install the plugin

    $ cd myApp
    $ cordova plugin add ../cordova-plugin-discovery
    

Edit `www/js/index.js` and add the following code inside `onDeviceReady`

```js
    var serviceType = "ssdp:all";
    
    var success = function(device) {
        console.log(device);
    };
    
    var failure = function(error) {
        alert("Error calling Service Discovery Plugin: " + error);
    };
    
    var normalizeHeaders = true;
    
    var readTimeout = 4000;
    
    var listenForNotifies = true;
    
    var broadcastMsearch = true;
    
    /**
     * Similar to the W3C specification for Network Service Discovery api 'http://www.w3.org/TR/discovery-api/'
     * 
     * @method listen
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
     * @param {boolean=} listenForNotifies
     *            Listen for unsolicited NOTIFY messages. (DEFAULT: false) If this is enabled, you will also receive
     *            NOTIFY messages which match the <b>exact</b> serviceType you provided, or <b>all</b>, if you used
     *            "ssdp:all".
     *            <b>ATTENTION</b>: The content of NOTIFY messages is slightly different than answers to M-SEARCH
     *            messages!
     * @param {boolean=} broadcastMsearch
     *            Send M-SEARCH messages and get all responses to it. (DEFAULT: true) This is the original behaviour
     *            of this plugin which can now be switched off to just listen passively.
     */
    serviceDiscovery.listen(serviceType, success, failure, normalizeHeaders, readTimeout, listenForNotifies, broadcastMsearch);
    
    setTimeout(
        function() {
            serviceDiscovery.stop(function() {
                console.log('Service Discovery stopped.');
            });
        },
        16000
    );
```


Run the code

    cordova run android
    cordova run ios

## Supported Platforms
- Android
- iOS

## Authors
- [Scott Dermott](http://sd-media.co.uk/)
- [Benjamin Erhart](https://die.netzarchitekten.com)

## Sponsors
- [DMU GmbH](http://dmu-gmbh.at/)
