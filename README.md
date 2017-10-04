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
    
    var success = function(devices) {
        console.log(devices);
    };
    
    var failure = function(error) {
        alert("Error calling Service Discovery Plugin: " + error);
    };
    
    /**
	 * Similar to the W3C specification for Network Service Discovery api 'http://www.w3.org/TR/discovery-api/'
	 * @method listen
	 * @param {String} serviceType e.g. "urn:schemas-upnp-org:service:ContentDirectory:1", "ssdp:all", "urn:schemas-upnp-org:service:AVTransport:1"
	 * @param {Function} success callback an object of services, indexed by their URNs.
	 * @param {Function} failure callback 
	*/
    serviceDiscovery.listen(serviceType, success, failure);
    
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
