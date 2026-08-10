/*
 * Scripted emulator launch for the QA harness.
 *
 *   node tools/qa/launch-emulator.mjs phone
 *   node tools/qa/launch-emulator.mjs tv
 *   node tools/qa/launch-emulator.mjs both
 *
 * The capture and verify scripts under tools/qa/ only ever attach to a device
 * that is already running (`adb -s emulator-5556 ...`). Starting that device
 * used to be an ad-hoc command line, which is how the two AVDs ended up being
 * launched with different flags. This is the one place a QA launch happens, so
 * the flags are stated once and apply to both.
 *
 * Why -no-snapshot AND -feature -QuickbootFileBacked
 * --------------------------------------------------
 * Two separate things allocate disk here, and only using both flags stops it.
 *
 * -no-snapshot-save suppresses writing state back on exit; -no-snapshot also
 * skips loading it, giving the cold boot QA wants (a restored snapshot carries
 * the previous run's process state and window stack into the capture).
 *
 * Neither stops snapshots/default_boot/ram.img appearing, sized to the AVD's
 * RAM: ~3 GB for Myata_API36, ~2 GB for Myata_TV_API36. That file is not a
 * saved snapshot - note it appears without a snapshot.pb beside it - it is the
 * emulator's file-backed guest RAM, allocated whenever the QuickbootFileBacked
 * feature is on, which it is by default in emulator/lib/advancedFeatures.ini.
 * Measured: both files were back within 30s of a boot flagged -no-snapshot.
 *
 * -feature -QuickbootFileBacked turns that off for this launch only, so the
 * guest RAM stays anonymous and the 5 GB per QA session is not spent. The SDK's
 * advancedFeatures.ini is deliberately left alone so Android Studio keeps its
 * normal quickboot behaviour.
 *
 * The ports are pinned so the serials the harness defaults to stay correct:
 * phone on 5556 (PHONE_SERIAL), TV on 5554. Nothing here writes to the AVD's
 * config.ini - these are launch flags only, so the AVDs remain usable from
 * Android Studio with their normal quickboot behaviour.
 */
import { execFileSync, spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const TARGETS = {
  phone: { avd: "Myata_API36", port: 5556 },
  tv: { avd: "Myata_TV_API36", port: 5554 },
};

const WHICH = (process.argv[2] || "").toLowerCase();
if (!["phone", "tv", "both"].includes(WHICH)) {
  console.error("usage: node launch-emulator.mjs <phone|tv|both>");
  process.exit(1);
}

const ADB = process.env.ADB || "adb";
const BOOT_TIMEOUT_MS = Number(process.env.QA_BOOT_TIMEOUT_MS || 4 * 60 * 1000);

/* Resolve the emulator binary from the SDK location rather than PATH: the SDK
 * here lives outside the default location, and `emulator` on PATH is not
 * guaranteed to be the one that owns these AVDs. */
function emulatorBinary() {
  if (process.env.EMULATOR_BIN) return process.env.EMULATOR_BIN;
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdk) throw new Error("set ANDROID_HOME (or EMULATOR_BIN) so the emulator can be found");
  const bin = path.join(sdk, "emulator", process.platform === "win32" ? "emulator.exe" : "emulator");
  if (!fs.existsSync(bin)) throw new Error(`emulator binary not found at ${bin}`);
  return bin;
}

const sleep = (ms) => execFileSync(process.execPath, ["-e", `setTimeout(()=>{},${ms})`], { timeout: ms + 5000 });

function booted(serial) {
  try {
    const out = execFileSync(ADB, ["-s", serial, "shell", "getprop", "sys.boot_completed"], {
      encoding: "utf8",
      timeout: 10000,
      /* Swallow adb's "device not found" / "device offline" chatter: it is the
       * expected reply until the device is up, not a failure worth printing. */
      stdio: ["ignore", "pipe", "ignore"],
    });
    return out.trim() === "1";
  } catch {
    return false; // device not up yet - adb exits non-zero, which is not an error here
  }
}

function launch({ avd, port }) {
  const serial = `emulator-${port}`;
  if (booted(serial)) {
    console.log(`${avd}: already running on ${serial}, leaving it alone`);
    return serial;
  }

  const args = [
    "-avd", avd,
    "-port", String(port),
    "-no-snapshot",                        // cold boot: no quickboot load, no save
    "-feature", "-QuickbootFileBacked",    // and no default_boot/ram.img allocation
    "-no-boot-anim",                       // shaves several seconds off a cold boot
  ];
  console.log(`${avd}: ${path.basename(emulatorBinary())} ${args.join(" ")}`);

  /* Detached, with stdio ignored: the emulator outlives this process so the
   * capture scripts can attach to it afterwards. */
  const child = spawn(emulatorBinary(), args, { detached: true, stdio: "ignore" });
  child.unref();

  const deadline = Date.now() + BOOT_TIMEOUT_MS;
  while (Date.now() < deadline) {
    if (booted(serial)) {
      console.log(`${avd}: boot completed on ${serial}`);
      return serial;
    }
    sleep(5000);
  }
  throw new Error(`${avd}: did not reach sys.boot_completed within ${Math.round(BOOT_TIMEOUT_MS / 1000)}s`);
}

const order = WHICH === "both" ? ["phone", "tv"] : [WHICH];
for (const key of order) launch(TARGETS[key]);

if (WHICH === "both" || WHICH === "tv") {
  /* capture-tv-baseline.mjs calls adb without -s, so it needs exactly one
   * device attached or ANDROID_SERIAL set. Say so rather than let it pick. */
  console.log(`\nTV capture: set ANDROID_SERIAL=emulator-${TARGETS.tv.port} if the phone AVD is also running.`);
}
