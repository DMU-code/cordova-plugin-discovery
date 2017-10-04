/**
 Implementation for SSDP service discovery. It sends a message on the standardized broadcast
 address/port and listens to the responses. The service type to be looked up
 is provided by the user.
 */
#import "ServiceDiscovery.h"

// MARK: Private members

static NSString *ADDRESS = @"239.255.255.250";
static NSInteger PORT = 1900;
static size_t bufferSize = 9216;

NSString * volatile callbackId;
int sd;
struct sockaddr_in broadcastAddr;
volatile BOOL backgroundThreadActive;
struct timeval timeout;
volatile NSMutableDictionary *oldAnswers;


@implementation ServiceDiscovery

// MARK: Methods from CDVPlugin

/**
 Called by Cordova on first load.

 Initializes some needed variables.
 */
- (void)pluginInitialize
{
    // Configure the broadcast IP and port.
    memset(&broadcastAddr, 0, sizeof broadcastAddr);
    broadcastAddr.sin_family = AF_INET;
    inet_pton(AF_INET, [ADDRESS UTF8String], &broadcastAddr.sin_addr);
    broadcastAddr.sin_port = htons(PORT);

    // set read timeout to 4 seconds.
    timeout.tv_sec = 4;
    timeout.tv_usec = 0;

    oldAnswers = [@{} mutableCopy];
}

/**
 Called by Cordova after page reload.

 Tells an eventually running background thread to give up working by removing the callbackId.
 */
- (void)onReset
{
    callbackId = nil;
}


// MARK: Plugin methods

/**
 Listen for SSDP server discovery answers.

 You need to provide a proper SSDP service type as first argument.

 This will continuously send out a SSDP "M-SEARCH" discovery request and then listen for answers
 - basically forever or until the page is left or reloaded.

 Your JavaScript success callback will possibly receive multiple callbacks (each with a new set of
 server answers) until all available servers have answered.

 Different errors in networking can happen which will call the error callback argument of your
 JavaScript call. Errors don't mean, that this plugin will stop listening. You have to explicitly
 call -stop: to achieve this!

 Succeeding calls to this method will overwrite the preceding call. In other words: Only the last
 caller will receive callbacks!

 @param command The reference to Cordova's JavaScript side.

 @see -stop:
 */
- (void)listen: (CDVInvokedUrlCommand*)command
{
    callbackId = command.callbackId;

    // Remove old answers, so we can give back everything again to the new listener.
    [oldAnswers removeAllObjects];

    NSString* serviceType = command.arguments[0];
    if ([serviceType length] < 1)
    {
        [self error:@"serviceType must not be an empty string!"];
        callbackId = nil;
        return;
    }

    // We want to have exaclty one background thread running.
    if (backgroundThreadActive)
    {
        return;
    }

    [self.commandDelegate runInBackground:^{
        backgroundThreadActive = YES;

        while (callbackId)
        {
            // Keep loop frequency below 1/s.
            [NSThread sleepForTimeInterval:1];

            if (![self broadcast:serviceType])
            {
                continue;
            }

            if (![self enableListen])
            {
                continue;
            }

            NSMutableDictionary *newAnswers = [self receive];
            [newAnswers removeObjectsForKeys:[oldAnswers allKeys]];

            // Extra check here - receive can take a while and it could be, that we got
            // cancelled in the meantime.
            if (callbackId) {
                [self.commandDelegate sendPluginResult:[CDVPluginResult
                                                        resultWithStatus:CDVCommandStatus_OK
                                                        messageAsDictionary:newAnswers]
                                            callbackId:callbackId];

                [oldAnswers addEntriesFromDictionary:newAnswers];
            }
        }

        [self close];

        backgroundThreadActive = NO;
    }];
}

/**
 Stops listening for SSDP server discovery answers.

 You will immediately stop receiving updates to your listener. The background thread will be
 stopped within 4 seconds (the read timeout).

 It is safe to call this multiple times and before any call to -listen:.

 @param command The reference to Cordova's JavaScript side.
 */
- (void)stop: (CDVInvokedUrlCommand*)command
{
    callbackId = nil;

    [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_OK]
                                callbackId:command.callbackId];
}

// MARK: Private Methods

/**
 Logs an error message and sends an error containing that message to the last caller of -listen:.

 @params message The error message.

 @see -listen:
 */
- (void) error:(NSString *)message
{
    NSLog(@"ServiceDiscovery Error: %@", message);

    if (callbackId)
    {
        [self.commandDelegate sendPluginResult:[CDVPluginResult
                                                resultWithStatus:CDVCommandStatus_ERROR
                                                messageAsString:message] callbackId:callbackId];
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
            [self error:@"Socket creation failed!"];
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

        [self error:@"Could not enable broadcast on socket!"];
    }

    return NO;
}

/**
 Broadcasts the SSDP M-SEARCH query. Transparently tries to open the socket and switch it into
 broadcast mode, if not done, yet.

 Will send an error to the calling JavaScript, if this can not be achieved.

 @return YES on success, NO on error.
 */
- (BOOL) broadcast:(NSString *)serviceType
{
    if ([self enableBroadcast])
    {
        // Send the broadcast request for the given service type
        const char *request = [[NSString stringWithFormat:
                                @"M-SEARCH * HTTP/1.1\r\nHOST: %@:%ld\r\nMAN: \"ssdp:discover\"\r\nST: %@\r\nMX: 2\r\n\r\n",
                                ADDRESS, (long)PORT, serviceType]
                               UTF8String];

        if (sendto(sd, request, strlen(request), 0, (struct sockaddr*)&broadcastAddr,
                   sizeof broadcastAddr) >= 0)
        {
            return YES;
        }

        [self error:@"Could not send broadcast!"];
    }

    return NO;
}
/**
 Switches socket into listen mode with a 4 second timeout. Transparently tries to open the socket,
 if not done, yet.

 Will send an error to the calling JavaScript, if this can not be achieved.

 @return YES on success, NO on error.
 */
- (BOOL) enableListen
{
    if ([self open])
    {
        if (!setsockopt(sd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)))
        {
            return YES;
        }

        [self error:@"Could not enable listening on socket!"];
    }

    return NO;
}

/**
 Will listen 4 seconds for answers from SSDP servers.

 Contrary to the other methods, you will have to call -enableListen yourself, so you are able to
 distinguish an error there from an empty answer.

 @return A dictionary keyed by received USNs containing dictionaries with all HTTP header responses
 received during that time.

 @see -enableListen
 */
- (NSMutableDictionary *)receive
{
    struct sockaddr_in receiveSockaddr;
    socklen_t receiveSockaddrLen = sizeof(receiveSockaddr);

    void *buffer = malloc(bufferSize);

    NSMutableDictionary *answers = [@{} mutableCopy];

    // Keep listening till the socket timeout event occurs
    while (callbackId)
    {
        ssize_t length = recvfrom(sd, buffer, bufferSize, 0, (struct sockaddr *)&receiveSockaddr,
                                  &receiveSockaddrLen);
        // Timeout or no answers anymore.
        if (length < 0)
        {
            break;
        }

        [answers addEntriesFromDictionary:[self convert:buffer length:length]];
    }

    free(buffer);

    return answers;
}

/**
 Converts the contents of a received byte buffer into a string and then breaks down the contained
 HTTP headers.

 See https://de.wikipedia.org/wiki/Simple_Service_Discovery_Protocol for an example of an SSDP
 response.

 @param bytes A byte buffer.
 @param length The length of valid data in the buffer.
 @return A dictionary with one key: "USN", containing another dictionary containing all headers.
 */
- (NSDictionary *)convert:(void *)bytes length:(NSUInteger)length
{
    NSMutableDictionary *data = [@{} mutableCopy];

    NSArray *lines = [[[NSString alloc]
                       initWithData:[NSData dataWithBytesNoCopy:bytes length:length freeWhenDone:NO]
                       encoding:NSUTF8StringEncoding] componentsSeparatedByString:@"\r"];

    NSCharacterSet *white = [NSCharacterSet whitespaceAndNewlineCharacterSet];

    for (NSString *line in lines) {
        NSRange posOfFirstColon = [line rangeOfString:@":"];

        if (posOfFirstColon.location != NSNotFound)
        {
            NSRange range = NSMakeRange(0, posOfFirstColon.location);
            NSString *key = [[line substringWithRange:range] stringByTrimmingCharactersInSet:white];

            range = NSMakeRange(posOfFirstColon.location + 1, [line length] - posOfFirstColon.location - 1);
            NSString *value = [[line substringWithRange:range] stringByTrimmingCharactersInSet:white];

            data[key] = value;
        }
    }

    if (data[@"USN"])
    {
        return @{data[@"USN"]: data};
    }

    return nil;
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

@end
