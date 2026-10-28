// Headless check of the bundled 3D viewer (app/src/main/assets/viewer) with a real model file.
//   node tools/viewer-test.js <model.glb|.ply> [outDir]
// Requires Playwright + Chromium. Loads the model, toggles wireframe, performs a two-point
// measurement by tapping on the model, and writes screenshots.
const fs = require('fs');
const path = require('path');
const http = require('http');
const { chromium } = require('playwright');

const model = path.resolve(process.argv[2]);
const out = path.resolve(process.argv[3] || '.');
const viewerDir = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'viewer');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.glb': 'model/gltf-binary', '.ply': 'application/octet-stream' };

const server = http.createServer((req, res) => {
  const u = decodeURIComponent(req.url.split('?')[0]);
  const file = u.startsWith('/model/') ? model : path.join(viewerDir, u === '/' ? 'index.html' : u);
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream' });
    res.end(data);
  });
});

(async () => {
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await browser.newPage({ viewport: { width: 412, height: 700 }, deviceScaleFactor: 2 });
  const events = [];
  page.on('console', (m) => {
    const t = m.text();
    if (t.startsWith('[viewer]')) events.push(JSON.parse(t.slice(9)));
    else if (m.type() === 'error') console.error('page error:', t);
  });
  page.on('pageerror', (e) => console.error('page exception:', e.message));
  await page.goto(base + '/index.html');
  await page.waitForFunction(() => window.viewer);
  await page.evaluate((u) => window.viewer.load(u), base + '/model/' + path.basename(model));
  const wait = async (type, ms = 60000) => {
    const t0 = Date.now();
    while (Date.now() - t0 < ms) {
      const e = events.find((x) => x.type === type || x.type === 'error');
      if (e) return e;
      await page.waitForTimeout(100);
    }
    throw new Error('timeout waiting for ' + type);
  };
  const loaded = await wait('loaded');
  if (loaded.type === 'error') throw new Error('viewer error: ' + loaded.message);
  console.log('loaded', JSON.stringify({ ...loaded, type: undefined }));
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(out, 'viewer-solid.png') });
  await page.evaluate(() => window.viewer.setWireframe(true));
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(out, 'viewer-wireframe.png') });
  await page.evaluate(() => { window.viewer.setWireframe(false); window.viewer.setMeasureMode(true); });
  // tap two points on the model near the centre of the screen
  await page.mouse.click(170, 360); await page.waitForTimeout(200);
  await page.mouse.click(250, 380); await page.waitForTimeout(300);
  const m = events.find((x) => x.type === 'measure');
  console.log('measure', m ? m.distance.toFixed(5) : 'no hit', 'missed taps:', events.filter((x) => x.type === 'pickMissed').length);
  await page.screenshot({ path: path.join(out, 'viewer-measure.png') });
  const snap = await page.evaluate(() => window.viewer.snapshot(256));
  console.log('snapshot bytes', snap.length);
  // unsupported format must produce a clean error
  events.length = 0;
  await page.evaluate(() => window.viewer.load('/model/x.fbx', { format: 'fbx' }));
  console.log('unsupported ->', JSON.stringify(await wait('error', 5000)));
  await browser.close(); server.close();
})().catch((e) => { console.error(e); process.exit(1); });
