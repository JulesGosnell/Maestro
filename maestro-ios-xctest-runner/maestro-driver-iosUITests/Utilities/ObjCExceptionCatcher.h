#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

extern NSErrorDomain const ObjCExceptionErrorDomain;

/**
 Swift cannot catch an NSException. One raised by XCTest inside a route handler escapes
 to XCTest's own handler, which asserts and tears the runner down (#4697).
 */
@interface ObjCExceptionCatcher : NSObject

/**
 Runs block. An NSException it raises is logged (name, reason, call stack) and returned
 as an NSError in ObjCExceptionErrorDomain, its description naming the exception.
 */
+ (BOOL)catchException:(NS_NOESCAPE void (^)(void))block error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
