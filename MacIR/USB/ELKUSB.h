//
//  ELKUSB.h
//  MacIR
//
//  Minimal user-space USB access for ELK Smart infrared dongles, built on
//  IOUSBHost. Exposed to Swift through the bridging header.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

extern NSErrorDomain const ELKUSBErrorDomain;

typedef NS_ERROR_ENUM(ELKUSBErrorDomain, ELKUSBErrorCode) {
    ELKUSBErrorDeviceNotFound = 1,
    ELKUSBErrorNoInterface = 2,
    ELKUSBErrorNoEndpoints = 3,
    ELKUSBErrorExclusiveAccess = 4,
    ELKUSBErrorClosed = 5,
};

/// A USB device that matched the monitor's vendor/product filter.
NS_SWIFT_NAME(USBDeviceInfo)
@interface ELKUSBDeviceInfo : NSObject
@property (nonatomic, readonly) uint64_t registryID;
@property (nonatomic, readonly) NSInteger vendorID;
@property (nonatomic, readonly) NSInteger productID;
@property (nonatomic, readonly) uint32_t locationID;
@property (nonatomic, readonly, copy) NSString *productName;
@property (nonatomic, readonly, copy, nullable) NSString *manufacturer;
@property (nonatomic, readonly, copy, nullable) NSString *serialNumber;
@end

/// Watches for devices being plugged in or removed.
NS_SWIFT_NAME(USBDeviceMonitor)
@interface ELKUSBMonitor : NSObject
/// @param productIDs Accepted product IDs; empty accepts every product of the vendor.
/// @param handler Called on the main queue with the full list whenever it changes.
- (instancetype)initWithVendorID:(NSInteger)vendorID
                      productIDs:(NSArray<NSNumber *> *)productIDs
                         handler:(void (^)(NSArray<ELKUSBDeviceInfo *> *devices))handler
    NS_SWIFT_NAME(init(vendorID:productIDs:handler:));
- (instancetype)init NS_UNAVAILABLE;
- (void)start;
- (void)stop;
- (NSArray<ELKUSBDeviceInfo *> *)currentDevices;

/// Human-readable dump of every USB device MacIR can see, plus the IOKit
/// driver tree below devices from `vendorID`, for troubleshooting.
+ (NSString *)diagnosticReportForVendorID:(NSInteger)vendorID NS_SWIFT_NAME(diagnosticReport(vendorID:));
@end

/// An open bulk IN/OUT pipe pair on the device's first interface.
/// Not thread-safe; use from a single serial queue.
NS_SWIFT_NAME(USBBulkConnection)
@interface ELKUSBConnection : NSObject
+ (nullable instancetype)openWithRegistryID:(uint64_t)registryID error:(NSError **)error
    NS_SWIFT_NAME(open(registryID:));
- (instancetype)init NS_UNAVAILABLE;

/// YES when the interface had to be captured from a kernel driver.
@property (nonatomic, readonly) BOOL capturedDevice;
/// Path of the serial device when macOS's CDC driver owns the dongle and we
/// talk to it through its serial port instead of raw USB pipes; nil otherwise.
@property (nonatomic, readonly, copy, nullable) NSString *serialPath;
@property (nonatomic, readonly) uint8_t outEndpointAddress;
@property (nonatomic, readonly) uint8_t inEndpointAddress;

- (BOOL)writeData:(NSData *)data timeout:(NSTimeInterval)timeout error:(NSError **)error
    NS_SWIFT_NAME(write(_:timeout:));
/// Returns an empty NSData when the timeout expires without data.
- (nullable NSData *)readDataWithMaxLength:(NSUInteger)maxLength timeout:(NSTimeInterval)timeout error:(NSError **)error
    NS_SWIFT_NAME(read(maxLength:timeout:));
- (void)close;
@end

NS_ASSUME_NONNULL_END
