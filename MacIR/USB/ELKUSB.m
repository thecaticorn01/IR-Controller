//
//  ELKUSB.m
//  MacIR
//

#import "ELKUSB.h"

#import <IOKit/IOKitLib.h>
#import <IOKit/serial/IOSerialKeys.h>
#import <errno.h>
#import <fcntl.h>
#import <poll.h>
#import <sys/ioctl.h>
#import <termios.h>
#import <unistd.h>
#import <IOKit/IOMessage.h>
#import <IOUSBHost/IOUSBHost.h>

NSErrorDomain const ELKUSBErrorDomain = @"ELKUSBErrorDomain";

/// kIOUSBTransactionTimeout (iokit_usb_err(0x51)), spelled out to avoid depending on legacy headers.
static const IOReturn ELKUSBTransactionTimeout = (IOReturn)0xe0004051;

static NSError *ELKMakeError(ELKUSBErrorCode code, NSString *description, NSError *underlying) {
    NSMutableDictionary *info = [NSMutableDictionary dictionaryWithObject:description forKey:NSLocalizedDescriptionKey];
    if (underlying != nil) {
        info[NSUnderlyingErrorKey] = underlying;
    }
    return [NSError errorWithDomain:ELKUSBErrorDomain code:code userInfo:info];
}

static BOOL ELKIsIOReturn(NSError *error, IOReturn value) {
    if (error == nil) {
        return NO;
    }
    if ((IOReturn)error.code == value) {
        return YES;
    }
    NSError *underlying = error.userInfo[NSUnderlyingErrorKey];
    return underlying != nil && (IOReturn)underlying.code == value;
}

static NSString *ELKStringProperty(io_service_t service, CFStringRef key) {
    CFTypeRef value = IORegistryEntryCreateCFProperty(service, key, kCFAllocatorDefault, 0);
    if (value == NULL) {
        return nil;
    }
    id object = CFBridgingRelease(value);
    return [object isKindOfClass:[NSString class]] ? object : nil;
}

static NSInteger ELKIntegerProperty(io_service_t service, CFStringRef key) {
    CFTypeRef value = IORegistryEntryCreateCFProperty(service, key, kCFAllocatorDefault, 0);
    if (value == NULL) {
        return -1;
    }
    id object = CFBridgingRelease(value);
    return [object isKindOfClass:[NSNumber class]] ? [object integerValue] : -1;
}

#pragma mark - Device info

@interface ELKUSBDeviceInfo ()
@property (nonatomic, readwrite) uint64_t registryID;
@property (nonatomic, readwrite) NSInteger vendorID;
@property (nonatomic, readwrite) NSInteger productID;
@property (nonatomic, readwrite) uint32_t locationID;
@property (nonatomic, readwrite, copy) NSString *productName;
@property (nonatomic, readwrite, copy, nullable) NSString *manufacturer;
@property (nonatomic, readwrite, copy, nullable) NSString *serialNumber;
@end

@implementation ELKUSBDeviceInfo

- (BOOL)isEqual:(id)object {
    if (![object isKindOfClass:[ELKUSBDeviceInfo class]]) {
        return NO;
    }
    return ((ELKUSBDeviceInfo *)object).registryID == self.registryID;
}

- (NSUInteger)hash {
    return (NSUInteger)self.registryID;
}

@end

#pragma mark - Monitor

@implementation ELKUSBMonitor {
    NSInteger _vendorID;
    NSSet<NSNumber *> *_productIDs;
    void (^_handler)(NSArray<ELKUSBDeviceInfo *> *);
    IONotificationPortRef _port;
    io_iterator_t _addedIterator;
    io_iterator_t _removedIterator;
}

static void ELKMonitorCallback(void *refcon, io_iterator_t iterator) {
    io_object_t object;
    while ((object = IOIteratorNext(iterator)) != IO_OBJECT_NULL) {
        IOObjectRelease(object);
    }
    ELKUSBMonitor *monitor = (__bridge ELKUSBMonitor *)refcon;
    [monitor scheduleNotify];
}

- (instancetype)initWithVendorID:(NSInteger)vendorID
                      productIDs:(NSArray<NSNumber *> *)productIDs
                         handler:(void (^)(NSArray<ELKUSBDeviceInfo *> *))handler {
    if ((self = [super init])) {
        _vendorID = vendorID;
        _productIDs = [NSSet setWithArray:productIDs];
        _handler = [handler copy];
    }
    return self;
}

- (void)dealloc {
    [self stop];
}

/// Matches every USB device. USB matching rules (USB Common Class Specification)
/// don't accept a vendor ID on its own, so a vendor-only dictionary can match
/// nothing; filter by vendor and product in code instead, as libusb does.
- (CFMutableDictionaryRef)newMatchingDictionary CF_RETURNS_RETAINED {
    return IOServiceMatching("IOUSBHostDevice");
}

- (void)start {
    if (_port != NULL) {
        return;
    }
    _port = IONotificationPortCreate(kIOMainPortDefault);
    IONotificationPortSetDispatchQueue(_port, dispatch_get_main_queue());

    CFMutableDictionaryRef added = [self newMatchingDictionary];
    if (added != NULL &&
        IOServiceAddMatchingNotification(_port, kIOFirstMatchNotification, added, ELKMonitorCallback,
                                         (__bridge void *)self, &_addedIterator) == KERN_SUCCESS) {
        ELKMonitorCallback((__bridge void *)self, _addedIterator);
    }
    CFMutableDictionaryRef removed = [self newMatchingDictionary];
    if (removed != NULL &&
        IOServiceAddMatchingNotification(_port, kIOTerminatedNotification, removed, ELKMonitorCallback,
                                         (__bridge void *)self, &_removedIterator) == KERN_SUCCESS) {
        ELKMonitorCallback((__bridge void *)self, _removedIterator);
    }
    [self notify];
}

- (void)stop {
    if (_addedIterator != IO_OBJECT_NULL) {
        IOObjectRelease(_addedIterator);
        _addedIterator = IO_OBJECT_NULL;
    }
    if (_removedIterator != IO_OBJECT_NULL) {
        IOObjectRelease(_removedIterator);
        _removedIterator = IO_OBJECT_NULL;
    }
    if (_port != NULL) {
        IONotificationPortDestroy(_port);
        _port = NULL;
    }
}

- (void)scheduleNotify {
    // Terminated services can linger in the registry for a moment.
    __weak ELKUSBMonitor *weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.4 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        [weakSelf notify];
    });
}

- (void)notify {
    if (_handler != nil) {
        _handler([self currentDevices]);
    }
}

- (NSArray<ELKUSBDeviceInfo *> *)currentDevices {
    NSMutableArray<ELKUSBDeviceInfo *> *devices = [NSMutableArray array];
    CFMutableDictionaryRef matching = [self newMatchingDictionary];
    if (matching == NULL) {
        return devices;
    }
    io_iterator_t iterator = IO_OBJECT_NULL;
    if (IOServiceGetMatchingServices(kIOMainPortDefault, matching, &iterator) != KERN_SUCCESS) {
        return devices;
    }
    io_service_t service;
    while ((service = IOIteratorNext(iterator)) != IO_OBJECT_NULL) {
        NSInteger vendorID = ELKIntegerProperty(service, CFSTR("idVendor"));
        NSInteger productID = ELKIntegerProperty(service, CFSTR("idProduct"));
        if (vendorID == _vendorID && (_productIDs.count == 0 || [_productIDs containsObject:@(productID)])) {
            ELKUSBDeviceInfo *info = [[ELKUSBDeviceInfo alloc] init];
            uint64_t entryID = 0;
            IORegistryEntryGetRegistryEntryID(service, &entryID);
            info.registryID = entryID;
            info.vendorID = vendorID;
            info.productID = productID;
            NSInteger location = ELKIntegerProperty(service, CFSTR("locationID"));
            info.locationID = location >= 0 ? (uint32_t)location : 0;
            info.productName = ELKStringProperty(service, CFSTR("USB Product Name"))
                ?: ELKStringProperty(service, CFSTR("kUSBProductString")) ?: @"IR Blaster";
            info.manufacturer = ELKStringProperty(service, CFSTR("USB Vendor Name"))
                ?: ELKStringProperty(service, CFSTR("kUSBVendorString"));
            info.serialNumber = ELKStringProperty(service, CFSTR("USB Serial Number"));
            [devices addObject:info];
        }
        IOObjectRelease(service);
    }
    IOObjectRelease(iterator);
    return devices;
}

static void ELKAppendTree(NSMutableString *report, io_registry_entry_t entry, int depth) {
    io_name_t className = {0};
    IOObjectGetClass(entry, className);
    NSMutableString *line = [NSMutableString stringWithFormat:@"%@- %s", [@"" stringByPaddingToLength:(NSUInteger)depth * 2 withString:@" " startingAtIndex:0], className];
    NSArray<NSString *> *integerKeys = @[@"bInterfaceNumber", @"bInterfaceClass", @"bInterfaceSubClass", @"bInterfaceProtocol",
                                         @"bConfigurationValue", @"bDeviceClass", @"bDeviceSubClass"];
    for (NSString *key in integerKeys) {
        NSInteger value = ELKIntegerProperty(entry, (__bridge CFStringRef)key);
        if (value >= 0) [line appendFormat:@" %@=%ld", key, (long)value];
    }
    for (NSString *key in @[@"IOCalloutDevice", @"IOTTYBaseName", @"CFBundleIdentifier", @"IOUserClientCreator"]) {
        NSString *value = ELKStringProperty(entry, (__bridge CFStringRef)key);
        if (value != nil) [line appendFormat:@" %@=%@", key, value];
    }
    [report appendFormat:@"%@\n", line];
    if (depth > 8) return;
    io_iterator_t children = IO_OBJECT_NULL;
    if (IORegistryEntryGetChildIterator(entry, kIOServicePlane, &children) == KERN_SUCCESS) {
        io_registry_entry_t child;
        while ((child = IOIteratorNext(children)) != IO_OBJECT_NULL) {
            ELKAppendTree(report, child, depth + 1);
            IOObjectRelease(child);
        }
        IOObjectRelease(children);
    }
}

+ (NSString *)diagnosticReportForVendorID:(NSInteger)vendorID {
    NSMutableString *report = [NSMutableString string];
    io_iterator_t iterator = IO_OBJECT_NULL;
    kern_return_t result = IOServiceGetMatchingServices(kIOMainPortDefault, IOServiceMatching("IOUSBHostDevice"), &iterator);
    if (result != KERN_SUCCESS) {
        [report appendFormat:@"IOServiceGetMatchingServices failed: 0x%08x\n", result];
        return report;
    }
    NSUInteger count = 0;
    NSMutableString *trees = [NSMutableString string];
    io_service_t service;
    while ((service = IOIteratorNext(iterator)) != IO_OBJECT_NULL) {
        count++;
        NSInteger vendor = ELKIntegerProperty(service, CFSTR("idVendor"));
        NSInteger product = ELKIntegerProperty(service, CFSTR("idProduct"));
        NSString *name = ELKStringProperty(service, CFSTR("USB Product Name")) ?: ELKStringProperty(service, CFSTR("kUSBProductString")) ?: @"?";
        uint64_t entryID = 0;
        IORegistryEntryGetRegistryEntryID(service, &entryID);
        [report appendFormat:@"USB %04lx:%04lx %@ (entry 0x%llx)\n", (long)vendor, (long)product, name, entryID];
        if (vendor == vendorID) {
            [trees appendFormat:@"\nDriver tree for %04lx:%04lx %@:\n", (long)vendor, (long)product, name];
            ELKAppendTree(trees, service, 0);
        }
        IOObjectRelease(service);
    }
    IOObjectRelease(iterator);
    [report insertString:[NSString stringWithFormat:@"%lu USB device(s) visible to MacIR\n", (unsigned long)count] atIndex:0];
    [report appendString:trees];
    return report;
}

@end

#pragma mark - Connection

@implementation ELKUSBConnection {
    int _serialFD;
    IOUSBHostDevice *_capturedHostDevice;
    IOUSBHostInterface *_interface;
    IOUSBHostPipe *_outPipe;
    IOUSBHostPipe *_inPipe;
}

/// Finds the first IOUSBHostInterface below a device (retained; caller releases).
static io_service_t ELKCopyInterfaceService(io_service_t device) {
    io_iterator_t iterator = IO_OBJECT_NULL;
    if (IORegistryEntryCreateIterator(device, kIOServicePlane, kIORegistryIterateRecursively, &iterator) != KERN_SUCCESS) {
        return IO_OBJECT_NULL;
    }
    io_service_t found = IO_OBJECT_NULL;
    io_registry_entry_t entry;
    while ((entry = IOIteratorNext(iterator)) != IO_OBJECT_NULL) {
        if (found == IO_OBJECT_NULL && IOObjectConformsTo(entry, "IOUSBHostInterface")) {
            NSInteger number = ELKIntegerProperty(entry, CFSTR("bInterfaceNumber"));
            if (number <= 0) {
                found = entry;
                continue;
            }
        }
        IOObjectRelease(entry);
    }
    IOObjectRelease(iterator);
    return found;
}

/// "IOUSBHostErrorDomain 0xe00002c9: Failed to create IOUSBHostInterface. (Failed [super init]) ← …"
static NSString *ELKDescribeError(NSError *error) {
    NSMutableArray<NSString *> *parts = [NSMutableArray array];
    for (NSError *current = error; current != nil && parts.count < 4; current = current.userInfo[NSUnderlyingErrorKey]) {
        NSMutableString *part = [NSMutableString stringWithFormat:@"%@ 0x%08x: %@", current.domain, (unsigned int)current.code, current.localizedDescription];
        if (current.localizedFailureReason.length > 0) {
            [part appendFormat:@" (%@)", current.localizedFailureReason];
        }
        [parts addObject:part];
    }
    return [parts componentsJoinedByString:@" ← "];
}

/// "pid 123, ProcessName" for every user client already attached to a service.
static NSArray<NSString *> *ELKUserClientOwners(io_service_t service) {
    NSMutableArray<NSString *> *owners = [NSMutableArray array];
    io_iterator_t children = IO_OBJECT_NULL;
    if (IORegistryEntryGetChildIterator(service, kIOServicePlane, &children) != KERN_SUCCESS) {
        return owners;
    }
    io_registry_entry_t child;
    while ((child = IOIteratorNext(children)) != IO_OBJECT_NULL) {
        NSString *creator = ELKStringProperty(child, CFSTR("IOUserClientCreator"));
        if (creator != nil) {
            [owners addObject:creator];
        }
        IOObjectRelease(child);
    }
    IOObjectRelease(children);
    return owners;
}

/// Finds the serial "callout" device (/dev/cu.*) published for a device by a CDC driver.
static NSString *ELKCalloutPath(io_service_t device) {
    io_iterator_t iterator = IO_OBJECT_NULL;
    if (IORegistryEntryCreateIterator(device, kIOServicePlane, kIORegistryIterateRecursively, &iterator) != KERN_SUCCESS) {
        return nil;
    }
    NSString *path = nil;
    io_registry_entry_t entry;
    while ((entry = IOIteratorNext(iterator)) != IO_OBJECT_NULL) {
        if (path == nil) {
            path = ELKStringProperty(entry, CFSTR(kIOCalloutDeviceKey));
        }
        IOObjectRelease(entry);
    }
    IOObjectRelease(iterator);
    return path;
}

static io_service_t ELKCopyServiceForRegistryID(uint64_t registryID) {
    CFMutableDictionaryRef matching = IORegistryEntryIDMatching(registryID);
    if (matching == NULL) {
        return IO_OBJECT_NULL;
    }
    return IOServiceGetMatchingService(kIOMainPortDefault, matching);
}

+ (nullable instancetype)openWithRegistryID:(uint64_t)registryID error:(NSError **)error {
    ELKUSBConnection *connection = [[ELKUSBConnection alloc] initPrivate];
    return [connection openWithRegistryID:registryID error:error] ? connection : nil;
}

- (instancetype)initPrivate {
    if ((self = [super init])) {
        _serialFD = -1;
    }
    return self;
}

- (void)dealloc {
    [self close];
}

- (BOOL)openWithRegistryID:(uint64_t)registryID error:(NSError **)error {
    io_service_t device = ELKCopyServiceForRegistryID(registryID);
    if (device == IO_OBJECT_NULL) {
        if (error) *error = ELKMakeError(ELKUSBErrorDeviceNotFound, @"The IR blaster is no longer connected.", nil);
        return NO;
    }

    NSError *openError = nil;
    NSArray<NSString *> *otherOwners = @[];
    io_service_t interfaceService = ELKCopyInterfaceService(device);
    if (interfaceService != IO_OBJECT_NULL) {
        _interface = [[IOUSBHostInterface alloc] initWithIOService:interfaceService
                                                           options:IOUSBHostObjectInitOptionsNone
                                                             queue:nil
                                                             error:&openError
                                                   interestHandler:nil];
        if (_interface == nil) {
            // Note who else has the interface open, excluding this process.
            NSString *me = [NSString stringWithFormat:@"pid %d,", getpid()];
            NSPredicate *notMe = [NSPredicate predicateWithBlock:^BOOL(NSString *owner, NSDictionary *bindings) {
                return ![owner hasPrefix:me];
            }];
            otherOwners = [ELKUserClientOwners(interfaceService) filteredArrayUsingPredicate:notMe];
        }
        IOObjectRelease(interfaceService);
    }

    if (_interface == nil) {
        // macOS's CDC driver may own the dongle (it reports a communications-class
        // interface). If it published a serial port, the port maps onto the same
        // bulk endpoints, so talk to the dongle through it.
        NSString *calloutPath = ELKCalloutPath(device);
        if (calloutPath != nil && [self openSerialPort:calloutPath]) {
            IOObjectRelease(device);
            return YES;
        }
    }

    if (_interface == nil) {
        // A kernel driver (e.g. the CDC driver, since the dongle reports a
        // communications-class interface) may own the interface. Capturing the
        // device detaches it; this needs root or the device-access entitlement.
        NSError *captureError = nil;
        _capturedHostDevice = [[IOUSBHostDevice alloc] initWithIOService:device
                                                                 options:IOUSBHostObjectInitOptionsDeviceCapture
                                                                   queue:nil
                                                                   error:&captureError
                                                         interestHandler:nil];
        if (_capturedHostDevice != nil) {
            NSError *configureError = nil;
            [_capturedHostDevice configureWithValue:1 matchInterfaces:NO error:&configureError];
            for (int attempt = 0; attempt < 20 && _interface == nil; attempt++) {
                io_service_t captured = ELKCopyInterfaceService(device);
                if (captured != IO_OBJECT_NULL) {
                    _interface = [[IOUSBHostInterface alloc] initWithIOService:captured
                                                                       options:IOUSBHostObjectInitOptionsNone
                                                                         queue:nil
                                                                         error:&openError
                                                               interestHandler:nil];
                    IOObjectRelease(captured);
                }
                if (_interface == nil) {
                    [NSThread sleepForTimeInterval:0.05];
                }
            }
            _capturedDevice = (_interface != nil);
        }
    }
    IOObjectRelease(device);

    if (_interface == nil) {
        if (error) {
            if (otherOwners.count > 0) {
                *error = ELKMakeError(ELKUSBErrorExclusiveAccess,
                                      [NSString stringWithFormat:@"The IR blaster is in use by another program (%@). Quit it, then unplug and reconnect the blaster.",
                                       [otherOwners componentsJoinedByString:@"; "]],
                                      openError);
            } else if (ELKIsIOReturn(openError, kIOReturnExclusiveAccess)) {
                *error = ELKMakeError(ELKUSBErrorExclusiveAccess,
                                      @"Another app or driver is using the IR blaster. Quit other remote-control apps, then unplug and reconnect the blaster.",
                                      openError);
            } else if (openError != nil) {
                *error = ELKMakeError(ELKUSBErrorNoInterface,
                                      [NSString stringWithFormat:@"Couldn’t open the IR blaster. %@", ELKDescribeError(openError)],
                                      openError);
            } else {
                *error = ELKMakeError(ELKUSBErrorNoInterface, @"The IR blaster has no usable USB interface yet. Try reconnecting it.", nil);
            }
        }
        [self close];
        return NO;
    }

    if (![self findEndpointsWithError:error]) {
        [self close];
        return NO;
    }
    return YES;
}

- (BOOL)findEndpointsWithError:(NSError **)error {
    uint8_t outAddress = 0, inAddress = 0;

    // Walk the raw configuration descriptor to find this interface's bulk endpoints.
    const uint8_t *config = (const uint8_t *)_interface.configurationDescriptor;
    const uint8_t *interfaceDescriptor = (const uint8_t *)_interface.interfaceDescriptor;
    if (config != NULL && interfaceDescriptor != NULL) {
        uint16_t totalLength = (uint16_t)(config[2] | (config[3] << 8));
        uint8_t wantedNumber = interfaceDescriptor[2];
        uint8_t wantedAlternate = interfaceDescriptor[3];
        BOOL inWantedInterface = NO;
        uint16_t offset = 0;
        while (offset + 2 <= totalLength) {
            uint8_t length = config[offset];
            uint8_t type = config[offset + 1];
            if (length < 2 || offset + length > totalLength) {
                break;
            }
            if (type == 4 && length >= 4) {
                inWantedInterface = (config[offset + 2] == wantedNumber && config[offset + 3] == wantedAlternate);
            } else if (type == 5 && length >= 4 && inWantedInterface) {
                uint8_t address = config[offset + 2];
                uint8_t attributes = config[offset + 3];
                if ((attributes & 0x03) == 0x02) {
                    if ((address & 0x80) != 0) {
                        if (inAddress == 0) inAddress = address;
                    } else {
                        if (outAddress == 0) outAddress = address;
                    }
                }
            }
            offset += length;
        }
    }

    NSError *pipeError = nil;
    if (outAddress != 0) _outPipe = [_interface copyPipeWithAddress:outAddress error:&pipeError];
    if (inAddress != 0) _inPipe = [_interface copyPipeWithAddress:inAddress error:&pipeError];

    // Fallback: probe the usual endpoint addresses.
    for (uint8_t number = 1; number <= 15 && (_outPipe == nil || _inPipe == nil); number++) {
        if (_outPipe == nil) {
            _outPipe = [_interface copyPipeWithAddress:number error:nil];
            if (_outPipe != nil) outAddress = number;
        }
        if (_inPipe == nil) {
            _inPipe = [_interface copyPipeWithAddress:(uint8_t)(0x80 | number) error:nil];
            if (_inPipe != nil) inAddress = (uint8_t)(0x80 | number);
        }
    }

    if (_outPipe == nil || _inPipe == nil) {
        if (error) *error = ELKMakeError(ELKUSBErrorNoEndpoints, @"The IR blaster does not expose the expected USB endpoints.", pipeError);
        return NO;
    }
    _outEndpointAddress = outAddress;
    _inEndpointAddress = inAddress;
    return YES;
}

- (BOOL)openSerialPort:(NSString *)path {
    int fd = open(path.fileSystemRepresentation, O_RDWR | O_NOCTTY | O_NONBLOCK);
    if (fd < 0) {
        return NO;
    }
    if (ioctl(fd, TIOCEXCL) != 0) {
        close(fd);
        return NO;
    }
    struct termios options;
    if (tcgetattr(fd, &options) != 0) {
        close(fd);
        return NO;
    }
    cfmakeraw(&options);
    options.c_cflag |= (CLOCAL | CREAD);
    options.c_cc[VMIN] = 0;
    options.c_cc[VTIME] = 0;
    cfsetspeed(&options, B115200);
    if (tcsetattr(fd, TCSANOW, &options) != 0) {
        close(fd);
        return NO;
    }
    tcflush(fd, TCIOFLUSH);
    _serialFD = fd;
    _serialPath = [path copy];
    return YES;
}

static BOOL ELKPoll(int fd, short events, NSTimeInterval timeout) {
    struct pollfd descriptor = { .fd = fd, .events = events, .revents = 0 };
    int milliseconds = (int)MAX(0, timeout * 1000);
    int result;
    do {
        result = poll(&descriptor, 1, milliseconds);
    } while (result < 0 && errno == EINTR);
    return result > 0 && (descriptor.revents & events) != 0;
}

- (BOOL)serialWrite:(NSData *)data timeout:(NSTimeInterval)timeout error:(NSError **)error {
    const uint8_t *bytes = data.bytes;
    NSUInteger written = 0;
    while (written < data.length) {
        if (!ELKPoll(_serialFD, POLLOUT, timeout)) {
            if (error) *error = [NSError errorWithDomain:NSPOSIXErrorDomain code:ETIMEDOUT userInfo:nil];
            return NO;
        }
        ssize_t count = write(_serialFD, bytes + written, data.length - written);
        if (count < 0) {
            if (errno == EAGAIN || errno == EINTR) continue;
            if (error) *error = [NSError errorWithDomain:NSPOSIXErrorDomain code:errno userInfo:nil];
            return NO;
        }
        written += (NSUInteger)count;
    }
    // Keep packet boundaries: wait until the driver has handed the bytes to USB.
    tcdrain(_serialFD);
    return YES;
}

- (nullable NSData *)serialReadWithMaxLength:(NSUInteger)maxLength timeout:(NSTimeInterval)timeout error:(NSError **)error {
    if (!ELKPoll(_serialFD, POLLIN, timeout)) {
        return [NSData data];
    }
    NSMutableData *buffer = [NSMutableData dataWithLength:maxLength];
    ssize_t count = read(_serialFD, buffer.mutableBytes, maxLength);
    if (count < 0) {
        if (errno == EAGAIN || errno == EINTR) return [NSData data];
        if (error) *error = [NSError errorWithDomain:NSPOSIXErrorDomain code:errno userInfo:nil];
        return nil;
    }
    buffer.length = (NSUInteger)count;
    return buffer;
}

- (NSMutableData *)bufferWithLength:(NSUInteger)length {
    NSMutableData *buffer = [_interface ioDataWithCapacity:length error:nil];
    if (buffer == nil || buffer.length != length) {
        buffer = [NSMutableData dataWithLength:length];
    }
    return buffer;
}

- (BOOL)writeData:(NSData *)data timeout:(NSTimeInterval)timeout error:(NSError **)error {
    if (_serialFD >= 0) {
        return [self serialWrite:data timeout:timeout error:error];
    }
    if (_outPipe == nil) {
        if (error) *error = ELKMakeError(ELKUSBErrorClosed, @"The IR blaster connection is closed.", nil);
        return NO;
    }
    NSMutableData *buffer = [self bufferWithLength:data.length];
    memcpy(buffer.mutableBytes, data.bytes, data.length);
    NSUInteger transferred = 0;
    return [_outPipe sendIORequestWithData:buffer bytesTransferred:&transferred completionTimeout:timeout error:error];
}

- (nullable NSData *)readDataWithMaxLength:(NSUInteger)maxLength timeout:(NSTimeInterval)timeout error:(NSError **)error {
    if (_serialFD >= 0) {
        return [self serialReadWithMaxLength:maxLength timeout:timeout error:error];
    }
    if (_inPipe == nil) {
        if (error) *error = ELKMakeError(ELKUSBErrorClosed, @"The IR blaster connection is closed.", nil);
        return nil;
    }
    NSMutableData *buffer = [self bufferWithLength:maxLength];
    NSUInteger transferred = 0;
    NSError *readError = nil;
    BOOL ok = [_inPipe sendIORequestWithData:buffer bytesTransferred:&transferred completionTimeout:timeout error:&readError];
    if (!ok) {
        if (ELKIsIOReturn(readError, kIOReturnTimeout) || ELKIsIOReturn(readError, ELKUSBTransactionTimeout) ||
            ELKIsIOReturn(readError, kIOReturnAborted)) {
            return [NSData data];
        }
        if (error) *error = readError;
        return nil;
    }
    return [NSData dataWithBytes:buffer.bytes length:MIN(transferred, buffer.length)];
}

- (void)close {
    if (_serialFD >= 0) {
        close(_serialFD);
        _serialFD = -1;
    }
    if (_outPipe != nil) {
        [_outPipe abortWithError:nil];
        _outPipe = nil;
    }
    if (_inPipe != nil) {
        [_inPipe abortWithError:nil];
        _inPipe = nil;
    }
    if (_interface != nil) {
        [_interface destroy];
        _interface = nil;
    }
    if (_capturedHostDevice != nil) {
        [_capturedHostDevice destroy];
        _capturedHostDevice = nil;
    }
}

@end
