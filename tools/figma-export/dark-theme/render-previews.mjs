import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");
const here = path.dirname(fileURLToPath(import.meta.url));
const output = path.join(here, "previews");

await fs.mkdir(output, { recursive: true });
const browser = await chromium.launch({
  headless: true,
  executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe",
  args: ["--allow-file-access-from-files"]
});
try {
  const page = await browser.newPage({ viewport: { width: 1920, height: 1200 }, deviceScaleFactor: 1 });
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.stack || error.message));
  page.on("console", (message) => { if (message.type() === "error") pageErrors.push(message.text()); });
  await page.goto(pathToFileURL(path.join(here, "preview.html")).href, { waitUntil: "domcontentloaded" });
  await page.waitForFunction(() => document.body.dataset.ready === "true", { timeout: 3000 }).catch(() => {
    throw new Error("Preview page did not become ready: " + pageErrors.join(" | "));
  });
  const frames = await page.locator("[data-name]").all();
  const manifest = [];
  for (const frame of frames) {
    const name = await frame.getAttribute("data-name");
    const file = `dark-${name}.png`;
    await frame.screenshot({ path: path.join(output, file), scale: "css" });
    const box = await frame.boundingBox();
    manifest.push({ name, file, width: Math.round(box.width), height: Math.round(box.height) });
  }
  const kit = page.locator("#kit");
  await kit.screenshot({ path: path.join(output, "dark-ui-kit.png"), scale: "css" });
  const kitBox = await kit.boundingBox();
  manifest.push({ name: "ui-kit", file: "dark-ui-kit.png", width: Math.round(kitBox.width), height: Math.round(kitBox.height) });
  await fs.writeFile(path.join(output, "manifest.json"), JSON.stringify(manifest, null, 2));
  console.log(JSON.stringify(manifest, null, 2));
} finally {
  await browser.close();
}
