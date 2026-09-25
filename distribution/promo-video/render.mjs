// Renders index.html frame-by-frame with headless Chromium and encodes it with ffmpeg.
//
//   node render.mjs stills 1.5 10.2 14   -> out/stills/t-<sec>.png
//   node render.mjs video                -> out/video.mp4 (silent) + out/sfx.json
//
// Environment: FFMPEG (ffmpeg binary, default "ffmpeg"), WORKERS (default 4), FPS override.
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
let chromium;
try { ({ chromium } = require('playwright')); } catch { ({ chromium } = require('/opt/node22/lib/node_modules/playwright')); }

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..'); // serve distribution/ so ../google-play resolves
const out = path.join(here, 'out');
const TYPES = { '.html': 'text/html', '.png': 'image/png', '.woff2': 'font/woff2', '.svg': 'image/svg+xml', '.js': 'text/javascript' };

const server = createServer(async (req, res) => {
  try {
    const p = path.join(root, decodeURIComponent(new URL(req.url, 'http://x').pathname));
    if (!p.startsWith(root)) throw new Error('outside root');
    res.writeHead(200, { 'content-type': TYPES[path.extname(p)] || 'application/octet-stream' });
    res.end(await readFile(p));
  } catch { res.writeHead(404); res.end(); }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const url = `http://127.0.0.1:${server.address().port}/promo-video/index.html?capture`;

const browser = await chromium.launch({ args: ['--force-color-profile=srgb', '--disable-lcd-text', '--font-render-hinting=none'] });
async function newPage() {
  const page = await browser.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 1 });
  page.on('pageerror', e => console.error('pageerror:', e.message));
  await page.goto(url);
  await page.evaluate(() => window.ready);
  return page;
}
async function frame(page, t, type = 'png') {
  await page.evaluate(t => window.render(t), t);
  return page.screenshot({ type, ...(type === 'jpeg' ? { quality: 94 } : {}), clip: { x: 0, y: 0, width: 1080, height: 1920 } });
}

const [mode = 'video', ...args] = process.argv.slice(2);
await mkdir(out, { recursive: true });

if (mode === 'stills') {
  const page = await newPage();
  await mkdir(path.join(out, 'stills'), { recursive: true });
  for (const a of args) {
    const f = path.join(out, 'stills', `t-${(+a).toFixed(2)}.png`);
    await writeFile(f, await frame(page, +a));
    console.log(f);
  }
} else {
  const workers = +(process.env.WORKERS || 4);
  const pages = await Promise.all(Array.from({ length: workers }, newPage));
  const { DURATION, FPS, SFX } = await pages[0].evaluate(() => ({ DURATION: window.DURATION, FPS: window.FPS, SFX: window.SFX }));
  const fps = +(process.env.FPS || FPS);
  await writeFile(path.join(out, 'sfx.json'), JSON.stringify({ duration: DURATION, sfx: SFX }, null, 1));
  const total = Math.round(DURATION * fps);
  const ff = spawn(process.env.FFMPEG || 'ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(fps), '-c:v', 'mjpeg', '-i', '-',
    '-c:v', 'libx264', '-preset', 'slow', '-crf', '17', '-pix_fmt', 'yuv420p', '-profile:v', 'high', '-movflags', '+faststart', path.join(out, 'video.mp4')],
    { stdio: ['pipe', 'inherit', 'inherit'] });
  const done = new Promise((res, rej) => ff.on('close', c => c ? rej(new Error('ffmpeg ' + c)) : res()));
  const t0 = Date.now();
  for (let i = 0; i < total; i += workers) {
    const bufs = await Promise.all(pages.map((p, w) => i + w < total ? frame(p, (i + w) / fps, 'jpeg') : null));
    for (const b of bufs) if (b && !ff.stdin.write(b)) await new Promise(r => ff.stdin.once('drain', r));
    if (i % (fps * 2) < workers) process.stdout.write(`\rframe ${i}/${total}  ${((Date.now() - t0) / 1000).toFixed(0)}s`);
  }
  ff.stdin.end();
  await done;
  console.log(`\nwrote ${path.join(out, 'video.mp4')}`);
}
await browser.close();
server.close();
