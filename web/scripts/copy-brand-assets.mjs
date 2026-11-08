import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = path.resolve(webRoot, "..");
const source = path.join(repositoryRoot, "app/src/main/res/drawable-nodpi/vinz_logo.png");
const destinationDirectory = path.join(webRoot, "public");
const destination = path.join(destinationDirectory, "vinz-logo.png");

if (!fs.existsSync(source)) {
  throw new Error("Canonical Vinz logo is missing: app/src/main/res/drawable-nodpi/vinz_logo.png");
}
const signature = fs.readFileSync(source).subarray(0, 8);
if (!signature.equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) {
  throw new Error("Canonical Vinz logo is not a valid PNG.");
}
fs.mkdirSync(destinationDirectory, { recursive: true });
fs.copyFileSync(source, destination);
console.log("Staged the canonical Vinz logo for the web account experience.");
