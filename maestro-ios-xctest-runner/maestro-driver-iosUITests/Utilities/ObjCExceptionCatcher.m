#import "ObjCExceptionCatcher.h"

NSErrorDomain const ObjCExceptionErrorDomain = @"ObjCExceptionErrorDomain";

@implementation ObjCExceptionCatcher

+ (BOOL)catchException:(NS_NOESCAPE void (^)(void))block error:(NSError **)error
{
    @try {
        block();
        return YES;
    } @catch (NSException *exception) {
        NSLog(@"Caught NSException %@: %@\n%@", exception.name, exception.reason, exception.callStackSymbols);
        if (error) {
            NSString *description = [NSString stringWithFormat:@"NSException %@: %@", exception.name, exception.reason];
            *error = [NSError errorWithDomain:ObjCExceptionErrorDomain
                                         code:0
                                     userInfo:@{NSLocalizedDescriptionKey: description}];
        }
        return NO;
    }
}

@end
