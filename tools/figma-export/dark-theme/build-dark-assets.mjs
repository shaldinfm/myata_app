import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const sharp = require("sharp");

const here = path.dirname(fileURLToPath(import.meta.url));
const output = path.resolve(here, "../assets/real-artwork");
const source = "D:/Downloads/Telegram Desktop";

const crops = [
  { file: "PLAYER.png", name: "player-what-you-know.png", left: 75, top: 94, width: 242, height: 242 },
  { file: "HOME.png", name: "playlist-seasonal-topping.png", left: 16, top: 416, width: 160, height: 160 },
  { file: "HOME.png", name: "playlist-xtra.png", left: 192, top: 416, width: 160, height: 160 },
  { file: "COLLECTION.png", name: "track-homewrecker.png", left: 33, top: 149, width: 64, height: 64 },
  { file: "COLLECTION.png", name: "track-leila.png", left: 33, top: 263, width: 64, height: 64 },
  { file: "COLLECTION.png", name: "track-forever.png", left: 33, top: 377, width: 64, height: 64 }
];

await fs.mkdir(output, { recursive: true });
const results = [];
for (const crop of crops) {
  const input = path.join(source, crop.file);
  try {
    await sharp(input).extract({ left: crop.left, top: crop.top, width: crop.width, height: crop.height }).png().toFile(path.join(output, crop.name));
    results.push({ name: crop.name, source: `${crop.file} (${crop.left},${crop.top},${crop.width}×${crop.height})` });
  } catch (error) {
    results.push({ name: crop.name, error: error.message });
  }
}

await fs.writeFile(path.join(output, "sources.json"), JSON.stringify({
  note: "Crops are separate raster image fills extracted from supplied visual-reference screenshots; they are not full-screen images.",
  crops: results
}, null, 2));
console.log(JSON.stringify(results, null, 2));
