import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createRequire } from "node:module";

// playwright-core drives an already-installed Chrome; nothing is downloaded.
// Point PLAYWRIGHT_NODE_PATH at a node_modules that has it, or install it here.
const require = createRequire(process.env.PLAYWRIGHT_REQUIRE_FROM || import.meta.url);
const { chromium } = require("playwright-core");

const here = path.dirname(fileURLToPath(import.meta.url));
const output = path.join(here, "previews");

const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "/usr/bin/google-chrome",
  "/usr/bin/chromium",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
].filter(Boolean);

async function findChrome() {
  for (const candidate of CHROME_CANDIDATES) {
    try {
      await fs.access(candidate);
      return candidate;
    } catch {
      /* try the next one */
    }
  }
  throw new Error("No Chrome found. Set CHROME_PATH to a Chrome/Chromium binary.");
}

await fs.mkdir(output, { recursive: true });
const browser = await chromium.launch({
  headless: true,
  executablePath: await findChrome(),
  args: ["--allow-file-access-from-files", "--font-render-hinting=none"]
});

try {
  const page = await browser.newPage({ viewport: { width: 1920, height: 1200 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.stack || e.message));
  page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });

  await page.goto(pathToFileURL(path.join(here, "preview.html")).href, { waitUntil: "domcontentloaded" });
  await page.waitForFunction(() => document.body.dataset.ready === "true", { timeout: 8000 })
    .catch(() => { throw new Error("Preview did not become ready: " + errors.join(" | ")); });
  // Let the webfonts settle so text metrics are stable between runs.
  await page.waitForTimeout(400);

  const manifest = [];
  for (const frame of await page.locator("[data-name]").all()) {
    const name = await frame.getAttribute("data-name");
    const file = `${name}.png`;
    await frame.screenshot({ path: path.join(output, file), scale: "css" });
    const box = await frame.boundingBox();
    manifest.push({ name, file, width: Math.round(box.width), height: Math.round(box.height) });
  }

  for (const kit of await page.locator("[data-kit]").all()) {
    const theme = await kit.getAttribute("data-kit");
    const file = `${theme}-ui-kit.png`;
    await kit.screenshot({ path: path.join(output, file), scale: "css" });
    const box = await kit.boundingBox();
    manifest.push({ name: `${theme}-ui-kit`, file, width: Math.round(box.width), height: Math.round(box.height) });
  }

  await fs.writeFile(path.join(output, "manifest.json"), JSON.stringify(manifest, null, 2));
  console.log(`rendered ${manifest.length} previews`);
  if (errors.length) console.warn("page errors:", errors.join(" | "));
} finally {
  await browser.close();
}
