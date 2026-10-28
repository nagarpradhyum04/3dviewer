// Render a synthetic multi-view capture of an object with KNOWN dimensions,
// used to measure reconstruction / measurement accuracy of the backend
// pipeline (Phase 7). This is a test fixture, never an app output.
//
//   node render.js --marker marker_plain.png --out ./dataset [--views 14] [--rings 20,40,60]
//
// Writes dataset/images/*.jpg and dataset/ground_truth.json.
const fs = require('fs');
const path = require('path');
const http = require('http');
const { chromium } = require('playwright');

const args = Object.fromEntries(process.argv.slice(2).reduce((a, v, i, arr) =>
  (v.startsWith('--') ? a.concat([[v.slice(2), arr[i + 1]]]) : a), []));
const out = path.resolve(args.out || 'dataset');
const views = parseInt(args.views || '14', 10);
const rings = (args.rings || '20,38,58').split(',').map(Number);
const radius = parseFloat(args.radius || '0.42');

const spec = {
  width: 1600, height: 1200, fovY: 52,
  ground: 0.9,
  box: [0.120, 0.080, 0.060],          // length (x), width (z), height (y) in metres
  boxYaw: 0.35,
  markerSize: 0.050,                    // black square side
  markerImageScale: 8 / 6,              // marker png = 6 cells + 1 white cell each side
  markerPos: [0.13, 0.0005, 0.10],
};

const mime = { '.html': 'text/html', '.js': 'text/javascript' };
const server = http.createServer((req, res) => {
  const p = req.url.startsWith('/three/')
    ? path.join(__dirname, 'node_modules/three/build', req.url.slice(7))
    : path.join(__dirname, 'scene.html');
  fs.readFile(p, (err, data) => {
    if (err) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'Content-Type': mime[path.extname(p)] || 'application/octet-stream' });
    res.end(data);
  });
});

(async () => {
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const port = server.address().port;
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  const page = await browser.newPage();
  page.on('console', m => console.log('[page]', m.text()));
  await page.goto(`http://127.0.0.1:${port}/`);
  await page.waitForFunction('window.sceneReady === true');
  spec.markerDataUrl = 'data:image/png;base64,' + fs.readFileSync(args.marker).toString('base64');
  await page.evaluate(s => window.buildScene(s), spec);

  fs.mkdirSync(path.join(out, 'images'), { recursive: true });
  const target = [0, spec.box[2] / 2, 0];
  const cams = [];
  let n = 0;
  for (const elev of rings) {
    for (let i = 0; i < views; i++) {
      const az = (i / views) * 2 * Math.PI + (elev / 90) * 0.4;
      const e = elev * Math.PI / 180;
      const pos = [radius * Math.cos(e) * Math.cos(az), target[1] + radius * Math.sin(e), radius * Math.cos(e) * Math.sin(az)];
      const url = await page.evaluate(([p, t]) => window.renderView(p, t), [pos, target]);
      const name = `IMG_${String(++n).padStart(4, '0')}.jpg`;
      fs.writeFileSync(path.join(out, 'images', name), Buffer.from(url.split(',')[1], 'base64'));
      cams.push({ name, position: pos, target });
    }
  }
  const fy = (spec.height / 2) / Math.tan(spec.fovY * Math.PI / 360);
  fs.writeFileSync(path.join(out, 'ground_truth.json'), JSON.stringify({
    note: 'Synthetic scene rendered with three.js. Units: metres.',
    object: { length: spec.box[0], width: spec.box[1], height: spec.box[2] },
    marker: { dictionary: 'DICT_4X4_50', id: 0, size_m: spec.markerSize },
    intrinsics: { width: spec.width, height: spec.height, fx: fy, fy, cx: spec.width / 2, cy: spec.height / 2 },
    cameras: cams,
  }, null, 2));
  console.log(`rendered ${n} views to ${out}`);
  await browser.close();
  server.close();
})().catch(e => { console.error(e); process.exit(1); });
