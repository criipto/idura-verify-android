// uhid_touch — a minimal scriptable touchscreen for Android, built on the
// kernel UHID interface (the same hardware-input mechanism scrcpy uses).
//
// Why this exists: the BankID PIN keypad rejects *injected* input events
// (UiAutomator / `adb shell input tap`) — they register a digit but the app
// detects the injection and fails the flow. A UHID device re-enters through the
// kernel HID layer, so its touches look like a real digitizer and are
// indistinguishable from a finger. `/dev/uhid` is writable by the `shell` user
// (no root needed), and `UiAutomation.executeShellCommandRw` runs as shell, so
// the instrumented test drives this over a pipe. See LoginTest#runSEBankID and
// the :example deployUhidTouch Gradle task that compiles and pushes this.
//
// It declares a Windows-8-style multitouch *touch screen*: the "Contact Count
// Maximum" feature report makes the HID core tag the device HID_GROUP_MULTITOUCH
// and bind hid-multitouch, which sets INPUT_PROP_DIRECT — so Android classifies it
// as a real touchscreen and maps absolute coordinates straight to screen pixels.
// (A plain single-touch digitizer binds hid-generic instead and ends up a
// touchpad/pointer, which does not map to pixels.)
//
// Usage:
//   uhid_touch <screen_w> <screen_h>
// It prints "ready <w>x<h>" to stdout once the touchscreen is live, then reads
// tap commands on stdin, one per line:
//   "<x> <y>"            tap at pixel (x,y) with a default ~80ms press
//   "<x> <y> <hold_ms>"  tap with an explicit press duration
// EOF (or process exit) destroys the virtual device.

#include <errno.h>
#include <fcntl.h>
#include <linux/uhid.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

// Win8 single-contact multitouch touch screen, absolute X/Y in 0..32767.
// Input report (7 bytes): [tip:1|pad:7][contactId][X lo][X hi][Y lo][Y hi][count]
// Feature report (1 byte): contact count maximum.
static unsigned char rdesc[] = {
    0x05, 0x0D,        // Usage Page (Digitizer)
    0x09, 0x04,        // Usage (Touch Screen)
    0xA1, 0x01,        // Collection (Application)
    0x09, 0x22,        //   Usage (Finger)
    0xA1, 0x02,        //   Collection (Logical)
    0x09, 0x42,        //     Usage (Tip Switch)
    0x15, 0x00,        //     Logical Minimum (0)
    0x25, 0x01,        //     Logical Maximum (1)
    0x75, 0x01,        //     Report Size (1)
    0x95, 0x01,        //     Report Count (1)
    0x81, 0x02,        //     Input (Data,Var,Abs)
    0x75, 0x07,        //     Report Size (7)
    0x95, 0x01,        //     Report Count (1)
    0x81, 0x03,        //     Input (Cnst,Var,Abs)        ; padding
    0x09, 0x51,        //     Usage (Contact Identifier)
    0x15, 0x00,        //     Logical Minimum (0)
    0x25, 0x7F,        //     Logical Maximum (127)
    0x75, 0x08,        //     Report Size (8)
    0x95, 0x01,        //     Report Count (1)
    0x81, 0x02,        //     Input (Data,Var,Abs)
    0x05, 0x01,        //     Usage Page (Generic Desktop)
    0x09, 0x30,        //     Usage (X)
    0x09, 0x31,        //     Usage (Y)
    0x16, 0x00, 0x00,  //     Logical Minimum (0)
    0x26, 0xFF, 0x7F,  //     Logical Maximum (32767)
    0x75, 0x10,        //     Report Size (16)
    0x95, 0x02,        //     Report Count (2)
    0x81, 0x02,        //     Input (Data,Var,Abs)
    0xC0,              //   End Collection (Logical)
    0x05, 0x0D,        //   Usage Page (Digitizer)
    0x09, 0x54,        //   Usage (Contact Count)
    0x15, 0x00,        //   Logical Minimum (0)
    0x25, 0x7F,        //   Logical Maximum (127)
    0x75, 0x08,        //   Report Size (8)
    0x95, 0x01,        //   Report Count (1)
    0x81, 0x02,        //   Input (Data,Var,Abs)
    0x09, 0x55,        //   Usage (Contact Count Maximum)
    0x25, 0x01,        //   Logical Maximum (1)
    0x75, 0x08,        //   Report Size (8)
    0x95, 0x01,        //   Report Count (1)
    0xB1, 0x02,        //   Feature (Data,Var,Abs)
    0xC0               // End Collection (Application)
};

#define CONTACT_COUNT_MAX 1

static int uhid_write(int fd, const struct uhid_event *ev) {
  ssize_t n = write(fd, ev, sizeof(*ev));
  if (n < 0) {
    fprintf(stderr, "uhid write failed: %s\n", strerror(errno));
    return -errno;
  }
  return 0;
}

static int create_device(int fd) {
  struct uhid_event ev;
  memset(&ev, 0, sizeof(ev));
  ev.type = UHID_CREATE2;
  strcpy((char *)ev.u.create2.name, "uhid-touch");
  memcpy(ev.u.create2.rd_data, rdesc, sizeof(rdesc));
  ev.u.create2.rd_size = sizeof(rdesc);
  ev.u.create2.bus = BUS_USB;
  ev.u.create2.vendor = 0x18d1;
  ev.u.create2.product = 0x4ee7;
  ev.u.create2.version = 1;
  ev.u.create2.country = 0;
  return uhid_write(fd, &ev);
}

static void destroy_device(int fd) {
  struct uhid_event ev;
  memset(&ev, 0, sizeof(ev));
  ev.type = UHID_DESTROY;
  uhid_write(fd, &ev);
}

static int send_report(int fd, int tip, int id, int x, int y, int count) {
  struct uhid_event ev;
  memset(&ev, 0, sizeof(ev));
  ev.type = UHID_INPUT2;
  ev.u.input2.size = 7;
  ev.u.input2.data[0] = tip ? 0x01 : 0x00;
  ev.u.input2.data[1] = id & 0xff;
  ev.u.input2.data[2] = x & 0xff;
  ev.u.input2.data[3] = (x >> 8) & 0xff;
  ev.u.input2.data[4] = y & 0xff;
  ev.u.input2.data[5] = (y >> 8) & 0xff;
  ev.u.input2.data[6] = count & 0xff;
  return uhid_write(fd, &ev);
}

// Service one event from the kernel. Returns the event type, or -1 on error.
static int handle_uhid_event(int fd) {
  struct uhid_event ev;
  ssize_t n = read(fd, &ev, sizeof(ev));
  if (n < 0) return -1;
  switch (ev.type) {
    case UHID_GET_REPORT: {
      // The only feature report we expose is Contact Count Maximum.
      struct uhid_event out;
      memset(&out, 0, sizeof(out));
      out.type = UHID_GET_REPORT_REPLY;
      out.u.get_report_reply.id = ev.u.get_report.id;
      out.u.get_report_reply.err = 0;
      out.u.get_report_reply.size = 1;
      out.u.get_report_reply.data[0] = CONTACT_COUNT_MAX;
      uhid_write(fd, &out);
      break;
    }
    case UHID_SET_REPORT: {
      struct uhid_event out;
      memset(&out, 0, sizeof(out));
      out.type = UHID_SET_REPORT_REPLY;
      out.u.set_report_reply.id = ev.u.set_report.id;
      out.u.set_report_reply.err = 0;
      uhid_write(fd, &out);
      break;
    }
    default:
      break;
  }
  return (int)ev.type;
}

static void do_tap(int fd, int px, int py, int hold_ms, int sw, int sh) {
  // The kernel input core suppresses an ABS_MT_POSITION_X/Y event whose value
  // equals the slot's stored value — even across a lift + new contact. So two
  // consecutive taps that share an X or Y coordinate would emit an incomplete
  // position frame for the second contact, and Android drops it as a tap. Real
  // fingers avoid this through natural jitter; we reproduce that by toggling a
  // 1-logical-unit offset (~0.03px, lands on the same pixel) so both axes always
  // differ from the previous tap and are always re-emitted.
  static int jitter = 0;
  int lx = (int)((long)px * 32767 / (sw - 1)) + jitter;
  int ly = (int)((long)py * 32767 / (sh - 1)) + jitter;
  jitter ^= 1;
  if (lx > 32767) lx = 32767;
  if (ly > 32767) ly = 32767;
  send_report(fd, 1, 0, lx, ly, 1);  // finger down
  usleep(hold_ms * 1000);
  send_report(fd, 0, 0, lx, ly, 1);  // finger up (tip clears, contact released)
  usleep(40 * 1000);
  fprintf(stderr, "tap %d,%d\n", px, py);
  fflush(stderr);
}

int main(int argc, char **argv) {
  if (argc < 3) {
    fprintf(stderr, "usage: %s <screen_w> <screen_h>\n", argv[0]);
    return 2;
  }
  int sw = atoi(argv[1]);
  int sh = atoi(argv[2]);
  if (sw <= 1 || sh <= 1) {
    fprintf(stderr, "bad screen size %dx%d\n", sw, sh);
    return 2;
  }

  int fd = open("/dev/uhid", O_RDWR | O_CLOEXEC);
  if (fd < 0) {
    fprintf(stderr, "open /dev/uhid: %s\n", strerror(errno));
    return 1;
  }
  if (create_device(fd) < 0) return 1;

  // Service kernel requests until the device is opened by the input subsystem
  // (UHID_OPEN), so the first tap isn't dropped. Bounded so we never hang.
  struct pollfd pfd = {.fd = fd, .events = POLLIN};
  for (int waited = 0; waited < 3000; waited += 50) {
    if (poll(&pfd, 1, 50) > 0 && (pfd.revents & POLLIN)) {
      if (handle_uhid_event(fd) == UHID_OPEN) break;
    }
  }
  // Signal readiness on stdout so a driving process can sync before tapping.
  printf("ready %dx%d\n", sw, sh);
  fflush(stdout);

  // Main loop: service kernel feature requests and read tap commands from stdin.
  struct pollfd pfds[2];
  pfds[0].fd = fd;
  pfds[0].events = POLLIN;
  pfds[1].fd = STDIN_FILENO;
  pfds[1].events = POLLIN;

  char buf[256];
  size_t len = 0;
  for (;;) {
    if (poll(pfds, 2, -1) < 0) {
      if (errno == EINTR) continue;
      break;
    }
    if (pfds[0].revents & POLLIN) handle_uhid_event(fd);
    if (pfds[1].revents & POLLIN) {
      char chunk[256];
      ssize_t n = read(STDIN_FILENO, chunk, sizeof(chunk));
      if (n <= 0) break;  // EOF
      for (ssize_t i = 0; i < n; i++) {
        if (chunk[i] == '\n' || len == sizeof(buf) - 1) {
          buf[len] = '\0';
          int px = -1, py = -1, hold = 80;
          if (sscanf(buf, "%d %d %d", &px, &py, &hold) >= 2) {
            if (px >= 0 && py >= 0 && px < sw && py < sh) {
              do_tap(fd, px, py, hold, sw, sh);
            } else {
              fprintf(stderr, "skip out-of-range tap %d %d\n", px, py);
              fflush(stderr);
            }
          }
          len = 0;
        } else {
          buf[len++] = chunk[i];
        }
      }
    }
  }

  destroy_device(fd);
  close(fd);
  return 0;
}
