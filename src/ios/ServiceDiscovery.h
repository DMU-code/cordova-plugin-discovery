/*
 * Service discovery plugin interface
 */


#import <Cordova/CDVPlugin.h>
#include <netinet/in.h>
#include <arpa/inet.h>

@interface ServiceDiscovery : CDVPlugin

- (void)listen: (CDVInvokedUrlCommand*)command;
- (void)stop: (CDVInvokedUrlCommand*)command;

@end
