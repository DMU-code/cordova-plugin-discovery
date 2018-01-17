/**
 Implementation for SSDP service discovery. Sends a message on the standardized broadcast
 address/port and listens to the responses. The service type to be looked up
 is provided by the user.
 */
#import "ServiceDiscovery.h"

// MARK: Private members

static NSString *ADDRESS = @"239.255.255.250";
static NSInteger PORT = 1900;
static size_t bufferSize = 9216;
static NSString *TYPE = @"__TYPE__";
static NSString *TYPE_MSEARCH = @"M-SEARCH";
static NSString *TYPE_NOTIFY = @"NOTIFY";
static NSString *TYPE_RESPONSE = @"RESPONSE";
static NSString *TYPE_UNKNOWN = @"UNKNOWN";

NSString * volatile callbackId;
NSString * volatile serviceType;
BOOL broadcastMsearch = true;
BOOL listenForNotifies;
BOOL normalizeHeaders;
struct timeval timeout;
int sd;
struct sockaddr_in broadcastAddr;
volatile BOOL backgroundThreadActive;
volatile NSMutableDictionary *oldAnswers;


@implementation ServiceDiscovery

// MARK: Methods from CDVPlugin

/**
 Called by Cordova on first load.

 Initializes some needed variables.
 */
- (void) pluginInitialize
{
    // Configure the broadcast IP and port.
    memset(&broadcastAddr, 0, sizeof broadcastAddr);
    broadcastAddr.sin_family = AF_INET;
    inet_pton(AF_INET, [ADDRESS UTF8String], &broadcastAddr.sin_addr);
    broadcastAddr.sin_port = htons(PORT);

    oldAnswers = [@{} mutableCopy];
}

/**
 Called by Cordova after page reload.

 Tells an eventually running background thread to give up working by removing the callbackId.
 */
- (void) onReset
{
    callbackId = nil;
}


// MARK: Plugin methods

/**
 Listen for SSDP server discovery answers.

 You need to provide a proper SSDP service type as first argument.

 This will continuously send out a SSDP "M-SEARCH" discovery request and then listen for answers
 - basically forever or until the page is left or reloaded.

 Your JavaScript success callback will receive multiple callbacks (each with a new server answer)
 until all available servers have answered.

 Different errors in networking can happen which will call the error callback argument of your
 JavaScript call. Errors don't mean, that this plugin will stop listening. You have to explicitly
 call -stop: to achieve this!

 Succeeding calls to this method will overwrite the preceding call. In other words: Only the last
 caller will receive callbacks!

 @param command The reference to Cordova's JavaScript side.

 @see -stop:
 */
- (void) listen:(CDVInvokedUrlCommand*)command
{
    callbackId = command.callbackId;

    serviceType = command.arguments[0];
    if ([serviceType length] < 1)
    {
        [self error:@"serviceType must not be an empty string!"];
        callbackId = nil;
        return;
    }

    broadcastMsearch = [command.arguments[1] boolValue];
    listenForNotifies = [command.arguments[2] boolValue];

    normalizeHeaders = command.arguments.count > 3 && [command.arguments[3] boolValue];

    // Remove old answers, so we can give back everything again to the new listener.
    [oldAnswers removeAllObjects];

    if (command.arguments.count > 4)
    {
        NSInteger to = [command.arguments[4] integerValue];
        timeout.tv_sec = to / 1000;
        timeout.tv_usec = (to % 1000) /* rest */ * 1000 /* microseconds */;
    }
    else {
        // set default read timeout to 4 seconds.
        timeout.tv_sec = 4;
        timeout.tv_usec = 0;
    }

    NSLog(@"ServiceDiscovery#listen {serviceType=\"%@\", broadcastMsearch=%@, listenForNotifies=%@, normalizeHeaders=%@, timeout=%ld, backgroundThreadActive=%@}",
          serviceType,
          broadcastMsearch ? @"YES" : @"NO",
          listenForNotifies ? @"YES" : @"NO",
          normalizeHeaders ? @"YES" : @"NO",
          (timeout.tv_sec * 1000000 + timeout.tv_usec) / 1000,
          backgroundThreadActive ? @"YES" : @"NO");

    // We want to have exaclty one background thread running.
    if (backgroundThreadActive)
    {
        return;
    }

    [self.commandDelegate runInBackground:^{
        backgroundThreadActive = YES;

        while (callbackId)
        {
            if (broadcastMsearch)
            {
                [self broadcast];
            }

            if (![self receive])
            {
                // Keep loop frequency below 1/s.
                [NSThread sleepForTimeInterval:1];

                continue;
            }
        }

        [self close];

        backgroundThreadActive = NO;
    }];
}

/**
 Stops listening for SSDP server discovery answers.

 You will immediately stop receiving updates to your listener. The background thread will be
 stopped after read timeout. (4 seconds per default).

 It is safe to call this multiple times and before any call to -listen:.

 @param command The reference to Cordova's JavaScript side.
 */
- (void) stop:(CDVInvokedUrlCommand*)command
{
    callbackId = nil;

    NSLog(@"ServiceDiscovery#stop {backgroundThreadActive=%@}",
          backgroundThreadActive ? @"YES" : @"NO");

    [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_OK]
                                callbackId:command.callbackId];
}

// MARK: Private Methods

/**
 Logs an error message and sends an error containing that message to the last caller of -listen:.

 @param message The error message.

 @see -listen:
 */
- (void) error:(NSString *)format, ...
{
    va_list args;
    va_start(args, format);
    NSString *message = [[NSString alloc] initWithFormat:format arguments:args];
    va_end(args);

    NSLog(@"ServiceDiscovery Error: %@", message);

    if (callbackId)
    {
        CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                messageAsString:message];
        [result setKeepCallbackAsBool:YES];

        [self.commandDelegate sendPluginResult:result callbackId:callbackId];
    }
}

/**
 Sends the answer of a server to a M-SEARCH request to the callback of the last caller of -listen:,
 if it doesn't contain a USN or if the USN was not seen before.

 @param answer A dictionary containing the answer of an SSDP server.
 */
- (void) result:(NSMutableDictionary *)answer
{
    if (callbackId && answer)
    {
        NSString *type;
        NSString *nt;
        NSString *usn;

        for (NSString *k in [answer allKeys]) {
            if ([k caseInsensitiveCompare:TYPE] == NSOrderedSame)
            {
                type = answer[k];
            }
            else if ([k caseInsensitiveCompare:@"NT"] == NSOrderedSame)
            {
                nt = answer[k];
            }
            else if ([k caseInsensitiveCompare:@"USN"] == NSOrderedSame)
            {
                usn = answer[k];
            }
        }

        if ([type isEqualToString:TYPE_UNKNOWN])
        {
            // We don't understand this. Probably doesn't make sense.
            return;
        }

        if ([type isEqualToString:TYPE_MSEARCH])
        {
            // We're not interested in M-SEARCH requests. (Probably our own, anyway.)
            return;
        }

        if ([type isEqualToString:TYPE_NOTIFY] && (
           !listenForNotifies ||
           (![@"ssdp:all" isEqualToString:serviceType] && ![nt isEqualToString:serviceType])))
        {
            // That's strange stuff from devices we don't want - ignore.
            return;
        }

        if (!usn || !oldAnswers[usn])
        {
            [answer removeObjectForKey:TYPE];

            CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                       messageAsDictionary:answer];
            [result setKeepCallbackAsBool:YES];

            [self.commandDelegate sendPluginResult:result callbackId:callbackId];

            if (usn)
            {
                oldAnswers[usn] = answer;
            }
        }
    }
}

/**
 Checks, if the socket is already opened, and if not, does open it.

 Will send an error to the calling JavaScript, if this can not be achieved.

 @return YES on success, NO on error.
*/
- (BOOL) open
{
    if (sd < 1)
    {
        sd = socket(PF_INET, SOCK_DGRAM, IPPROTO_UDP);
        if (sd < 1)
        {
            [self error:@"Socket creation failed with error %d!", errno];
            return NO;
        }

        // Set socket so address/port can be reused by others. We ignore any errors, though, since
        // experiments have shown, that it will work either way. Anyway, we want to be a good
        // citizen. See the following link for explanations:
        // https://stackoverflow.com/questions/14388706/socket-options-so-reuseaddr-and-so-reuseport-how-do-they-differ-do-they-mean-t
        int flag = 1;
        setsockopt(sd, SOL_SOCKET, SO_REUSEADDR, &flag, sizeof(flag));
        setsockopt(sd, SOL_SOCKET, SO_REUSEPORT, &flag, sizeof(flag));

        // Bind to all interfaces on port 1900, so we can receive unsolicited SSDP packets, also,
        // not just the ones we receive as answer to our M-SEARCH.
        struct sockaddr_in sin;
        memset(&sin, 0, sizeof(sin));
        sin.sin_family = AF_INET;
        sin.sin_addr.s_addr = htonl(INADDR_ANY);
        sin.sin_port = htons(PORT);

        if (bind(sd, (struct sockaddr *)&sin, sizeof(sin))) {
            [self close];
            [self error:@"Socket bind failed with error %d!", errno];
            return NO;
        }
    }

    return YES;
}

/**
 Switches socket into broadcast mode. Transparently tries to open the socket, if not done, yet.

 Will send an error to the calling JavaScript, if this can not be achieved.

 @return YES on success, NO on error.
 */
- (BOOL) enableBroadcast
{
    if ([self open])
    {
        int broadcastEnable = 1;
        if (!setsockopt(sd, SOL_SOCKET, SO_BROADCAST, &broadcastEnable, sizeof(broadcastEnable)))
        {
            return YES;
        }

        [self error:@"Could not enable broadcast on socket due to error %d!", errno];
    }

    return NO;
}

/**
 Broadcasts the SSDP M-SEARCH request. Transparently tries to open the socket and switch it into
 broadcast mode, if not done, yet.

 Will send an error to the calling JavaScript, if this can not be achieved.

 @return YES on success, NO on error.
 */
- (BOOL) broadcast
{
    if ([self enableBroadcast])
    {
        // Send the broadcast request for the given service type
        const char *request = [[NSString stringWithFormat:
                                @"M-SEARCH * HTTP/1.1\r\nHOST: %@:%ld\r\nMAN: \"ssdp:discover\"\r\nST: %@\r\nMX: 2\r\n\r\n",
                                ADDRESS, (long)PORT, serviceType]
                               UTF8String];

        if (sendto(sd, request, strlen(request), 0, (struct sockaddr*)&broadcastAddr,
                   sizeof(broadcastAddr)) >= 0)
        {
            return YES;
        }

        [self error:@"Could not send broadcast due to error %d!", errno];
    }

    return NO;
}
/**
 Switches socket into listen mode with a given timeout (4 second default). Transparently tries to
 open the socket, if not done, yet.

 Will send an error to the calling JavaScript, if this can not be achieved.

 @return YES on success, NO on error.
 */
- (BOOL) enableListen
{
    if ([self open])
    {
        // Try to add ourselves to the SSDP multicast group, so we can receive unsolicited
        // NOTIFYs, also. For an unknown reason, IP_ADD_MEMBERSHIP always returns errno 48
        // (EADDRINUSE), but it will work anyway. If really not, we will still be able to
        // receive answers to our M-SEARCH broadcast, so we ignore any errors here.
        // See the following for deeper explanantions:
        // http://openbook.rheinwerk-verlag.de/linux_unix_programmierung/Kap11-018.htm#RxxKap11018040003971F01A100
        struct ip_mreq mreq;
        memset(&mreq, 0, sizeof(mreq));
        mreq.imr_multiaddr = broadcastAddr.sin_addr;
        mreq.imr_interface.s_addr = htonl(INADDR_ANY);
        setsockopt(sd, IPPROTO_IP, IP_ADD_MEMBERSHIP, &mreq, sizeof(mreq));

        if (!setsockopt(sd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)))
        {
            return YES;
        }

        [self error:@"Could not enable listening on socket due to error %d!", errno];
    }

    return NO;
}

/**
 Will listen for answers from SSDP servers. Transparently tries to open the socket and
 switch it into listen mode, if not done, yet.

 When an answer was received, sends it immediately to the calling JavaScript.

 @return YES on success, NO on error.
 */
- (BOOL) receive
{
    if ([self enableListen])
    {
        void *buffer = malloc(bufferSize);

        // Keep listening till the socket timeout event occurs and we have a callbackId.
        while (callbackId)
        {
            ssize_t length = recv(sd, buffer, bufferSize, 0);

            // Timeout.
            if (length < 0)
            {
                break;
            }

            [self result:[ServiceDiscovery convert:buffer length:length
                                  normalizeHeaders:normalizeHeaders]];
        }

        free(buffer);

        return YES;
    }

    return NO;
}

/**
 Checks, if the socket is already closed, and if not, does close it.
 */
- (void) close
{
    if (sd > 0)
    {
        close(sd);
        sd = 0;
    }
}

/**
 Converts the contents of a received byte buffer into a string and then breaks down the contained
 HTTP headers.

 See https://de.wikipedia.org/wiki/Simple_Service_Discovery_Protocol for an example of an SSDP
 response.

 Will capitalize headers, if requested to do so.

 @param bytes A byte buffer.
 @param length The length of valid data in the buffer.
 @param normalizeHeaders If headers should be capitalized.
 @return A dictionary containing all headers.
 */
+ (NSMutableDictionary *) convert:(void *)bytes length:(NSUInteger)length normalizeHeaders:(BOOL)normalizeHeaders
{
    NSMutableDictionary *data = [@{} mutableCopy];

    NSArray *lines = [[[NSString alloc]
                       initWithData:[NSData dataWithBytesNoCopy:bytes length:length freeWhenDone:NO]
                       encoding:NSUTF8StringEncoding] componentsSeparatedByString:@"\r"];

    NSCharacterSet *white = [NSCharacterSet whitespaceAndNewlineCharacterSet];

    for (NSString *line in lines) {
        NSRange posOfFirstColon = [line rangeOfString:@":"];

        if (posOfFirstColon.location == NSNotFound)
        {
            if ([[line stringByTrimmingCharactersInSet:white] length] < 1)
            {
                continue;
            }

            // Must be the first line containing the HTTP request verb or response header.
            NSString *header = [line uppercaseString];

            if ([header containsString:@"M-SEARCH"])
            {
                data[TYPE] = TYPE_MSEARCH;
            }
            else if ([header containsString:@"NOTIFY"])
            {
                data[TYPE] = TYPE_NOTIFY;
            }
            else if ([header containsString:@"OK"])
            {
                data[TYPE] = TYPE_RESPONSE;
            }
            else {
                data[TYPE] = TYPE_UNKNOWN;
            }
        }
        else {
            NSRange range = NSMakeRange(0, posOfFirstColon.location);
            NSString *key = [[line substringWithRange:range] stringByTrimmingCharactersInSet:white];

            if (normalizeHeaders)
            {
                key = [key capitalizedString];
            }

            range = NSMakeRange(posOfFirstColon.location + 1, [line length] - posOfFirstColon.location - 1);
            NSString *value = [[line substringWithRange:range] stringByTrimmingCharactersInSet:white];

            data[key] = value;
        }
    }

    return data;
}

@end
