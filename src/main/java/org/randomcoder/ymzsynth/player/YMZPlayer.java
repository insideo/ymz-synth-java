package org.randomcoder.ymzsynth.player;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiDevice.Info;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.LinkedList;

import static javax.sound.midi.ShortMessage.CONTROL_CHANGE;

@SuppressWarnings({ "javadoc", "synthetic-access", "unused" })
public class YMZPlayer {
  private static final long NS_PER_CLOCK = 125L;
  private static final int FREQ_SCALE_FACTOR = 0;

  // MIDI channels - standard
  private static final int CHANNEL_STEREO = 0;
  private static final int CHANNEL_LEFT = 1;
  private static final int CHANNEL_RIGHT = 2;

  // MIDI channels - noise
  private static final int CHANNEL_NOISE_STEREO = 3;
  private static final int CHANNEL_NOISE_LEFT = 4;
  private static final int CHANNEL_NOISE_RIGHT = 5;

  // MIDI channels - raw
  private static final int CHANNEL_RAW_STEREO = 6;
  private static final int CHANNEL_RAW_LEFT = 7;
  private static final int CHANNEL_RAW_RIGHT = 8;

  // MIDI CCs for raw mode
  private static final byte CC_CHANNEL_A_FREQ_MSB = 20;
  private static final byte CC_CHANNEL_A_FREQ_LSB = 52;
  private static final byte CC_CHANNEL_B_FREQ_MSB = 21;
  private static final byte CC_CHANNEL_B_FREQ_LSB = 53;
  private static final byte CC_CHANNEL_C_FREQ_MSB = 22;
  private static final byte CC_CHANNEL_C_FREQ_LSB = 54;
  private static final byte CC_NOISE_FREQ = 23;
  private static final byte CC_MIXER = 24;
  private static final byte CC_CHANNEL_A_LEVEL = 25;
  private static final byte CC_CHANNEL_B_LEVEL = 26;
  private static final byte CC_CHANNEL_C_LEVEL = 27;
  private static final byte CC_ENVELOPE_FREQ_HIGH = 28; // high 7 bits
  private static final byte CC_ENVELOPE_FREQ_MED = 29; // middle 7 bits
  private static final byte CC_ENVELOPE_FREQ_LOW = 30; // low 2 bits
  private static final byte CC_ENVELOPE_SHAPE = 31;
  private static final byte CC_LATCH = 80;

  private final File ymz;
  private Receiver rxCleanup = null;
  private byte[] registers = new byte[16];
  private byte[] prevRegisters = new byte[16];

  public YMZPlayer(File ymz) {
    this.ymz = ymz;
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override public void run() {
        if (rxCleanup != null) {
          try {
            resetController(rxCleanup, CHANNEL_RAW_STEREO, registers);
          } catch (Exception ignored) {

          }
          rxCleanup.close();
        }
        // TODO Auto-generated method stub
        super.run();
      }
    });
  }

  public void play(String name, String version, String vendor, String desc)
      throws Exception {
    Info port = null;
    for (Info info : MidiSystem.getMidiDeviceInfo()) {
      if (name == null && version == null && vendor == null && desc == null) {
        System.err.println(
            "At least one of name, version, vendor, or description must be specified.");
        break;
      }
      if (name != null && !info.getName().equals(name)) {
        continue;
      }
      if (vendor != null && !info.getVendor().equals(vendor)) {
        continue;
      }
      if (version != null && !info.getVersion().equals(version)) {
        continue;
      }
      if (desc != null && !info.getDescription().equals(desc)) {
        continue;
      }
      try (MidiDevice dev = MidiSystem.getMidiDevice(info)) {
        Receiver r = null;
        try {
          r = dev.getReceiver();
        } catch (Exception e) {
          r = null;
        }
        if (r == null) {
          continue;
        }
      }
      if (port != null) {
        System.err.println(
            "Multiple MIDI ports match the given parameters. Please be more specific.");
        port = null;
        break;
      }
      port = info;
      continue;
    }
    if (port == null) {
      System.err.println("Unable to locate MIDI port");
      System.err.println("Available ports:");
      for (Info info : MidiSystem.getMidiDeviceInfo()) {
        try (MidiDevice dev = MidiSystem.getMidiDevice(info)) {
          Receiver r = null;
          try {
            r = dev.getReceiver();
          } catch (Exception e) {
            r = null;
          }
          if (r == null) {
            continue;
          }
        }

        System.out.println(
            "Device [name=" + info.getName() + ", version=" + info.getVersion()
                + ", vendor=" + info.getVendor() + ", desc=" + info
                .getDescription() + "]");
      }
      return;
    }

    System.out.println(
        "Using device [name=" + port.getName() + ", version=" + port
            .getVersion() + ", vendor=" + port.getVendor() + ", desc=" + port
            .getDescription() + "]");

    try (MidiDevice dev = MidiSystem.getMidiDevice(port)) {
      int channel = CHANNEL_RAW_STEREO;
      dev.open();

      try (Receiver rx = dev.getReceiver()) {
        rxCleanup = rx;
        try (DataInputStream dis = new DataInputStream(
            new FileInputStream(ymz))) {
          resetController(rx, channel, registers);
          long startTime = System.nanoTime();

          // read a buffer
          long prevClock = 0;
          while (true) {
            long clock = dis.readLong();
            byte register = dis.readByte();
            byte value = dis.readByte();
            if (prevClock != clock) {
              sendAll(rx, channel, registers);
              prevClock = clock;
            }
            sleepUntil(startTime, clock);
            System.out.println(
                "Clock: " + clock + " register: " + register + ", value: " + (
                    value & 0xff));
            if (register < 0 || register > 13) {
              System.err.println(
                  "Invalid register " + register + " found, ignoring.");
              continue;
            }
            registers[register] = value;
          }
        } catch (EOFException e) {
          System.err.println("EOF reached");
        } finally {
          resetController(rx, channel, registers);
          rxCleanup = null;
        }
      }
    }

  }

  private void sendAll(Receiver rx, int channel, byte[] regs) throws Exception {
    sendLatch(rx, channel, true);
    if (prevRegisters[0] != regs[0] || prevRegisters[1] != regs[1]) {
      sendFrequency(rx, channel, regs, 0);
    }
    if (prevRegisters[2] != regs[2] || prevRegisters[3] != regs[3]) {
      sendFrequency(rx, channel, regs, 2);
    }
    if (prevRegisters[4] != regs[4] || prevRegisters[5] != regs[6]) {
      sendFrequency(rx, channel, regs, 4);
    }
    if (prevRegisters[6] != regs[6]) {
      sendValue(rx, channel, CC_NOISE_FREQ, (byte) ((regs[6] & 0x1f) << 2));
    }
    if (prevRegisters[7] != regs[7]) {
      sendValue(rx, channel, CC_MIXER, (byte) ((regs[7] & 0x3f) << 1));
    }
    if (prevRegisters[8] != regs[8]) {
      sendValue(rx, channel, CC_CHANNEL_A_LEVEL,
          (byte) ((regs[8] & 0x1f) << 2));
    }
    if (prevRegisters[8] != regs[9]) {
      sendValue(rx, channel, CC_CHANNEL_B_LEVEL,
          (byte) ((regs[9] & 0x1f) << 2));
    }
    if (prevRegisters[10] != regs[10]) {
      sendValue(rx, channel, CC_CHANNEL_C_LEVEL,
          (byte) ((regs[10] & 0x1f) << 2));
    }
    if (prevRegisters[11] != regs[11] || prevRegisters[12] != regs[12]) {
      sendEnvelopeFrequency(rx, channel, regs);
    }
    if (prevRegisters[13] != regs[13]) {
      sendValue(rx, channel, CC_ENVELOPE_SHAPE,
          (byte) ((regs[0xd] & 0x0f) << 3));
    }
    sendLatch(rx, channel, false);
  }

  private void sendLatch(Receiver rx, int channel, boolean enabled)
      throws Exception {
    byte value = enabled ? (byte) 64 : (byte) 0;
    if (!enabled) {
      // save registers
      for (int i = 0; i < registers.length; i++) {
        prevRegisters[i] = registers[i];
      }
    }
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_LATCH, value), -1);

  }

  private void sendFrequency(Receiver rx, int channel, byte[] regs,
      int startRegister) throws Exception {
    int data = (regs[startRegister] & 0xff) + ((regs[startRegister + 1] << 8)
        & 0x0f00);
    data = data >> FREQ_SCALE_FACTOR;

    byte msb = (byte) ((data >> 5) & 0x7f); // top 7 bits
    byte lsb = (byte) ((data << 2) & 0x7c); // lower 5 bits
    int cc1 = ((startRegister / 2) + CC_CHANNEL_A_FREQ_MSB);
    int cc2 = ((startRegister / 2) + CC_CHANNEL_A_FREQ_LSB);

    rx.send(new ShortMessage(CONTROL_CHANGE, channel, (byte) cc1, msb), -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, (byte) cc2, lsb), -1);
  }

  private void sendEnvelopeFrequency(Receiver rx, int channel, byte[] regs)
      throws Exception {
    int data = (regs[0xc] & 0xff) + ((regs[0xd] << 8) & 0x0f00);
    data = data >> FREQ_SCALE_FACTOR;
    // 16 bit value, divided into 7/7/2

    byte high = (byte) ((data >> 9) & 0x7f); // top 7 bits
    byte med = (byte) ((data >> 7) & 0x7f); // middle 7 bits
    byte low = (byte) (((data >> 2) & 0x03) << 5); // bottom 2 bits

    rx.send(
        new ShortMessage(CONTROL_CHANGE, channel, CC_ENVELOPE_FREQ_HIGH, high),
        -1);
    rx.send(
        new ShortMessage(CONTROL_CHANGE, channel, CC_ENVELOPE_FREQ_MED, med),
        -1);
    rx.send(
        new ShortMessage(CONTROL_CHANGE, channel, CC_ENVELOPE_FREQ_LOW, low),
        -1);
  }

  private void sendValue(Receiver rx, int channel, byte cc, byte value)
      throws Exception {
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, cc, value), -1);
  }

  private void sleepUntil(long startTime, long clock)
      throws InterruptedException {
    // convert clock to timestamp in nanos

    long targetTime = (NS_PER_CLOCK * clock) + startTime;
    long totalTime = targetTime - System.nanoTime();
    if (totalTime < 0L) {
      return;
    }
    long millis = totalTime / 1000000L;
    long nanos = totalTime % 1000000L;

    Thread.sleep(millis, (int) nanos);
  }

  private void resetController(Receiver rx, int channel, byte[] regs)
      throws Exception {
    for (int i = 0; i < 15; i++) {
      regs[i] = 0;
    }

    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_LATCH, 127), -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_CHANNEL_A_FREQ_MSB, 0),
        -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_CHANNEL_A_FREQ_LSB, 0),
        -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_CHANNEL_B_FREQ_MSB, 0),
        -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_CHANNEL_B_FREQ_LSB, 0),
        -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_CHANNEL_C_FREQ_MSB, 0),
        -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_CHANNEL_C_FREQ_LSB, 0),
        -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_NOISE_FREQ, 0), -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_MIXER, 0), -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_CHANNEL_A_LEVEL, 0),
        -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_CHANNEL_B_LEVEL, 0),
        -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_CHANNEL_C_LEVEL, 0),
        -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_ENVELOPE_FREQ_HIGH, 0),
        -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_ENVELOPE_FREQ_MED, 0),
        -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_ENVELOPE_FREQ_LOW, 0),
        -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_ENVELOPE_SHAPE, 0),
        -1);
    rx.send(new ShortMessage(CONTROL_CHANGE, channel, CC_LATCH, 0), -1);
  }

  public static void usage() {
    System.err.println("Usage: " + YMZPlayer.class.getName()
        + " [--midi-name <port-name> ] [--midi-version <port-version>] [--midi-vendor <port-vendor>] [--midi-desc <port-description>] <ymz-file>");
  }

  public static void main(String[] args) throws Exception {
    String portName = null;
    String portVersion = null;
    String portVendor = null;
    String portDesc = null;
    String ymzFilename = null;

    boolean invalid = true;
    LinkedList<String> list = new LinkedList<>(Arrays.asList(args));
    argCheck:
    while (!list.isEmpty()) {
      String arg = list.removeFirst();
      switch (arg) {
      case "--midi-name":
        if (list.isEmpty()) {
          break argCheck;
        }
        portName = list.removeFirst();
        break;
      case "--midi-version":
        if (list.isEmpty()) {
          break argCheck;
        }
        portVersion = list.removeFirst();
        break;
      case "--midi-vendor":
        if (list.isEmpty()) {
          break argCheck;
        }
        portVendor = list.removeFirst();
        break;
      case "--midi-desc":
        if (list.isEmpty()) {
          break argCheck;
        }
        portDesc = list.removeFirst();
        break;
      default:
        ymzFilename = arg;
        invalid = false;
        break argCheck;
      }
    }

    if (invalid || !(list.isEmpty())) {
      usage();
      System.exit(1);
    }

    File input = new File(ymzFilename);
    YMZPlayer player = new YMZPlayer(input);
    player.play(portName, portVersion, portVendor, portDesc);
  }
}
