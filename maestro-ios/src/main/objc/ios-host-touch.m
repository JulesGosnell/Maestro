// smithr#1313: touches for an iOS SIMULATOR, sent host-side through the simulator's own
// persistent built-in digitizer (SimulatorKit SimDeviceLegacyHIDClient + Indigo HID messages),
// the way Meta's idb does (SimulatorIndigoHID).
//
// XCTest's _XCT_synthesizeEvent attaches a NEW virtual digitizer for every gesture, and
// backboardd must classify it within a 32 ms IPC deadline. Under CPU load the classification
// fails and backboardd ignores the whole gesture, while XCTest reports success. The built-in
// digitizer is classified once, at boot, so a touch sent through it has no deadline to miss.
// This process also times the UP itself, so a tap cannot stretch into a long press.
//
// One process per Maestro session, LONG-LIVED: started once, then driven line by line.
//
//   usage: ios-host-touch <udid>
//   stdout, once: "ready <width_pt> <height_pt> <scale> <device name>" | "fail <reason>" (exit != 0)
//   stdin, one command per line; coordinates are FRACTIONS (0..1) of the PORTRAIT screen:
//     tap   X Y HOLD_MS                        down, hold, up
//     swipe X1 Y1 X2 Y2 HOLD_MS MOVE_MS         down at 1, hold, move to 2 over MOVE_MS, up at 2
//     ping
//   reply, one line per command:
//     "ok <down_to_up_ms> <ack_ms>"
//     "refused <reason>"   nothing was sent; the caller may use another path
//     "err <reason>"       something was sent; the touch may have landed, so it must not be re-sent
//     down_to_up_ms: from sending the down to sending the up, on this process's clock
//     ack_ms:        from sending the up to the simulator acknowledging every message
//
// It exits on stdin EOF, so it ends with the JVM that started it however that JVM ends.
#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>
#include <dlfcn.h>
#include <mach/mach.h>
#include <mach/mach_time.h>
#include <mach/thread_policy.h>
#include <objc/message.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#define DEVELOPER_DIR "/Applications/Xcode.app/Contents/Developer"
#define CORE_SIMULATOR "/Library/Developer/PrivateFrameworks/CoreSimulator.framework/CoreSimulator"
#define SIMULATOR_KIT "/Library/PrivateFrameworks/SimulatorKit.framework/SimulatorKit"

enum { DOWN = 1, UP = 2 };
static const double MOVE_STEP_MS = 8;   // ~120 Hz, finer than any display refresh the sim draws
static const int64_t ACK_TIMEOUT_NS = 5 * NSEC_PER_SEC;

typedef void *(*IndigoMouseFn)(CGPoint *, CGPoint *, uint32_t, unsigned long, CGSize, uint32_t);
static IndigoMouseFn indigoMouse;
static id hidClient;
static dispatch_queue_t ackQueue;
static dispatch_semaphore_t acks;
static volatile int ackErrors;
static mach_timebase_info_data_t timebase;

static uint64_t nsToAbs(double ns) { return (uint64_t)(ns * timebase.denom / timebase.numer); }
static double absToMs(uint64_t abs) { return (double)abs * timebase.numer / timebase.denom / 1e6; }

// A touch at (x, y), fractions of the portrait screen, in the layout idb sends: the mouse
// message SimulatorKit builds, repackaged as a touch event for the built-in digitizer.
static void *touchMessage(double x, double y, int direction) {
  CGPoint p = { x, y };
  uint8_t *mouse = indigoMouse(&p, NULL, 0x32, direction, CGSizeMake(1, 1), 0);
  if (!mouse) return NULL;
  *(double *)(mouse + 0x3c) = p.x;
  *(double *)(mouse + 0x44) = p.y;
  uint8_t *m = calloc(1, 0x140);
  *(uint32_t *)(m + 0x18) = 0x90;
  m[0x1c] = 2;
  *(uint32_t *)(m + 0x20) = 0xB;
  *(uint64_t *)(m + 0x24) = mach_absolute_time();
  memcpy(m + 0x30, mouse + 0x30, 0x70);
  free(mouse);
  memcpy(m + 0x20 + 0x90, m + 0x20, 0x90);
  *(uint32_t *)(m + 0xB0 + 0x10) = 1;
  *(uint32_t *)(m + 0xB0 + 0x14) = 2;
  return m;
}

// Sends one message; returns the mach time it was handed over, or 0 when it could not be built.
static uint64_t send1(double x, double y, int direction) {
  void *m = touchMessage(x, y, direction);
  if (!m) return 0;
  void (^done)(NSError *) = ^(NSError *e) {
    if (e) { ackErrors++; fprintf(stderr, "ios-host-touch: ack error %s\n", e.description.UTF8String); }
    dispatch_semaphore_signal(acks);
  };
  uint64_t at = mach_absolute_time();
  ((void (*)(id, SEL, void *, BOOL, dispatch_queue_t, id))objc_msgSend)(
      hidClient, NSSelectorFromString(@"sendWithMessage:freeWhenDone:completionQueue:completion:"),
      m, YES, ackQueue, done);
  return at;
}

static int awaitAcks(int sent) {
  int missing = 0;
  for (int i = 0; i < sent; i++)
    if (dispatch_semaphore_wait(acks, dispatch_time(DISPATCH_TIME_NOW, ACK_TIMEOUT_NS))) missing++;
  return missing;
}

static void reply(const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  vprintf(fmt, ap);
  va_end(ap);
  putchar('\n');
  fflush(stdout);
}

// Down at (x1, y1), held HOLD_MS, moved to (x2, y2) over MOVE_MS, up there. Every step waits for
// an ABSOLUTE deadline from the down, so a late wake-up is not carried into the next step and
// the up is sent when it was asked for, however busy the machine.
static void gesture(double x1, double y1, double x2, double y2, double holdMs, double moveMs) {
  ackErrors = 0;
  int sent = 0;
  uint64_t down = send1(x1, y1, DOWN);
  if (!down) { reply("refused cannot build a touch message"); return; }
  sent++;
  int steps = moveMs > 0 ? (int)(moveMs / MOVE_STEP_MS) : 0;
  if (steps < 1 && moveMs > 0) steps = 1;
  mach_wait_until(down + nsToAbs(holdMs * 1e6));
  for (int i = 1; i <= steps; i++) {
    double f = (double)i / steps;
    mach_wait_until(down + nsToAbs((holdMs + moveMs * f) * 1e6));
    if (send1(x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, DOWN)) sent++;
  }
  uint64_t up = send1(x2, y2, UP);
  if (!up) { reply("err cannot build the up; the touch may still be down"); return; }
  sent++;
  int missing = awaitAcks(sent);
  double ackMs = absToMs(mach_absolute_time() - up);
  if (missing || ackErrors) { reply("err %d of %d messages unacknowledged, %d failed", missing, sent, ackErrors); return; }
  reply("ok %.1f %.1f", absToMs(up - down), ackMs);
}

// The thread that times the gestures runs under the real-time (time-constraint) policy. Under
// the default policy, even at QOS_CLASS_USER_INTERACTIVE, macOS coalesces timers: measured on a
// build VM at load 8 of 24 CPUs, a 100 ms mach_wait_until woke a mean 16 ms and up to 147 ms
// late, which turns a tap into a long press. Under this policy: mean 0.02 ms, worst 0.05 ms.
static void realTime(void) {
  double msToAbs = 1e6 * timebase.denom / timebase.numer;
  thread_time_constraint_policy_data_t policy = {
    .period = 0, .computation = (uint32_t)(1 * msToAbs), .constraint = (uint32_t)(5 * msToAbs), .preemptible = 1,
  };
  kern_return_t kr = thread_policy_set(mach_thread_self(), THREAD_TIME_CONSTRAINT_POLICY,
                                       (thread_policy_t)&policy, THREAD_TIME_CONSTRAINT_POLICY_COUNT);
  if (kr != KERN_SUCCESS) fprintf(stderr, "ios-host-touch: no real-time policy (%d); timing may stretch\n", kr);
}

static int fail(int code, NSString *why) {
  reply("fail %s", why.UTF8String);
  return code;
}

int main(int argc, char **argv) {
  @autoreleasepool {
    if (argc != 2) return fail(2, @"usage: ios-host-touch <udid>");
    mach_timebase_info(&timebase);
    pthread_set_qos_class_self_np(QOS_CLASS_USER_INTERACTIVE, 0);
    realTime();

    const char *env = getenv("DEVELOPER_DIR");
    NSString *developer = @(env && *env ? env : DEVELOPER_DIR);
    if (!dlopen(CORE_SIMULATOR, RTLD_NOW)) return fail(3, @(dlerror()));
    NSString *kit = [developer stringByAppendingString:@SIMULATOR_KIT];
    void *simulatorKit = dlopen(kit.UTF8String, RTLD_NOW);
    if (!simulatorKit) return fail(3, @(dlerror()));
    indigoMouse = (IndigoMouseFn)dlsym(simulatorKit, "IndigoHIDMessageForMouseNSEvent");
    if (!indigoMouse) return fail(3, @"SimulatorKit has no IndigoHIDMessageForMouseNSEvent");

    NSError *err = nil;
    id context = ((id (*)(id, SEL, id, NSError **))objc_msgSend)(
        NSClassFromString(@"SimServiceContext"),
        NSSelectorFromString(@"sharedServiceContextForDeveloperDir:error:"), developer, &err);
    id deviceSet = context ? ((id (*)(id, SEL, NSError **))objc_msgSend)(
        context, NSSelectorFromString(@"defaultDeviceSetWithError:"), &err) : nil;
    if (!deviceSet) return fail(4, [NSString stringWithFormat:@"no CoreSimulator device set: %@", err]);
    NSUUID *udid = [[NSUUID alloc] initWithUUIDString:@(argv[1])];
    id device = udid ? [deviceSet valueForKey:@"devicesByUDID"][udid] : nil;
    if (!device) return fail(4, [NSString stringWithFormat:@"no simulator %s", argv[1]]);
    if ([[device valueForKey:@"state"] integerValue] != 3 /* Booted */)
      return fail(4, [NSString stringWithFormat:@"simulator %s is not booted", argv[1]]);

    id deviceType = [device valueForKey:@"deviceType"];
    CGSize pixels = [[deviceType valueForKey:@"mainScreenSize"] sizeValue];
    double scale = [[deviceType valueForKey:@"mainScreenScale"] doubleValue];
    if (pixels.width <= 0 || pixels.height <= 0 || scale <= 0)
      return fail(4, @"cannot read the simulator's screen size");

    hidClient = ((id (*)(id, SEL, id, NSError **))objc_msgSend)(
        [NSClassFromString(@"SimulatorKit.SimDeviceLegacyHIDClient") alloc],
        NSSelectorFromString(@"initWithDevice:error:"), device, &err);
    if (!hidClient) return fail(5, [NSString stringWithFormat:@"no HID client: %@", err]);

    ackQueue = dispatch_queue_create("ios-host-touch.ack",
        dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_USER_INTERACTIVE, 0));
    acks = dispatch_semaphore_create(0);

    reply("ready %.0f %.0f %.0f %s", pixels.width / scale, pixels.height / scale, scale,
          [[device valueForKey:@"name"] UTF8String]);

    char *line = NULL;
    size_t cap = 0;
    while (getline(&line, &cap, stdin) > 0) {
      char cmd[16];
      double a[6];
      int n = sscanf(line, "%15s %lf %lf %lf %lf %lf %lf", cmd, &a[0], &a[1], &a[2], &a[3], &a[4], &a[5]);
      if (n < 1) continue;
      if (!strcmp(cmd, "quit")) break;
      if (!strcmp(cmd, "ping")) { reply("ok 0 0"); continue; }
      int args = n - 1;
      int inside = 1;
      if (!strcmp(cmd, "tap") && args == 3) {
        for (int i = 0; i < 2; i++) inside &= a[i] >= 0 && a[i] <= 1;
        if (!inside || a[2] < 0) { reply("refused tap outside the screen or negative hold"); continue; }
        gesture(a[0], a[1], a[0], a[1], a[2], 0);
      } else if (!strcmp(cmd, "swipe") && args == 6) {
        for (int i = 0; i < 4; i++) inside &= a[i] >= 0 && a[i] <= 1;
        if (!inside || a[4] < 0 || a[5] < 0) { reply("refused swipe outside the screen or negative time"); continue; }
        gesture(a[0], a[1], a[2], a[3], a[4], a[5]);
      } else {
        reply("refused unknown command");
      }
    }
    free(line);
  }
  return 0;
}
