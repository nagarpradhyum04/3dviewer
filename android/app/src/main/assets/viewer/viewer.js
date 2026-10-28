// Scan3D AI interactive model viewer (three.js, bundled offline in the APK).
//
// Native -> JS:  window.viewer.<command>(...) via WebView.evaluateJavascript
// JS -> native:  window.Scan3DBridge.postMessage(JSON) (Android @JavascriptInterface);
//                in a desktop browser messages are logged to the console instead.
//
// Distances are reported in MODEL UNITS. The app converts them with the scan's calibration
// (metres when calibrated, otherwise relative only) — the viewer never invents a scale.
import * as THREE from 'three';
import { OrbitControls } from './jsm/controls/OrbitControls.js';
import { GLTFLoader } from './jsm/loaders/GLTFLoader.js';
import { PLYLoader } from './jsm/loaders/PLYLoader.js';

const msg = document.getElementById('msg');
const send = (type, data = {}) => {
  const payload = JSON.stringify({ type, ...data });
  if (window.Scan3DBridge) window.Scan3DBridge.postMessage(payload); else console.log('[viewer]', payload);
};

let renderer;
try {
  renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
} catch (e) {
  msg.textContent = 'This device cannot display 3D graphics (WebGL unavailable).';
  send('error', { code: 'webgl', message: 'WebGL is not available on this device: ' + e.message });
  throw e;
}
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.outputColorSpace = THREE.SRGBColorSpace;
document.body.appendChild(renderer.domElement);

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(45, window.innerWidth / window.innerHeight, 0.001, 1000);
const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.dampingFactor = 0.12;
controls.screenSpacePanning = true;

scene.add(new THREE.HemisphereLight(0xffffff, 0x445566, 2.2));
const key = new THREE.DirectionalLight(0xffffff, 1.2);
scene.add(key);
scene.add(camera);
camera.add(key);
key.position.set(1, 1, 2);

const root = new THREE.Group();
scene.add(root);
const helpers = new THREE.Group();
scene.add(helpers);
const measureGroup = new THREE.Group();
scene.add(measureGroup);

let model = null;         // Object3D currently shown
let meshes = [];          // raycast targets
let bounds = null;        // Box3 in model units
let home = null;          // initial camera state
let wireframe = false;
let measureMode = false;
let picks = [];

function setTheme(dark) {
  const bg = dark ? 0x0e1420 : 0xf3f6fb;
  scene.background = new THREE.Color(bg);
  document.documentElement.style.setProperty('--bg', dark ? '#0e1420' : '#f3f6fb');
}
setTheme(true);

function clear() {
  root.clear(); helpers.clear(); clearMeasurements();
  model = null; meshes = []; bounds = null;
}

function frame(obj) {
  bounds = new THREE.Box3().setFromObject(obj);
  const size = bounds.getSize(new THREE.Vector3());
  const centre = bounds.getCenter(new THREE.Vector3());
  const radius = Math.max(size.length() / 2, 1e-6);
  const vfov = THREE.MathUtils.degToRad(camera.fov / 2);
  const hfov = Math.atan(Math.tan(vfov) * camera.aspect);      // portrait screens are narrower
  const dist = radius / Math.sin(Math.min(vfov, hfov)) * 1.05;
  camera.near = radius / 200; camera.far = radius * 200; camera.updateProjectionMatrix();
  camera.position.copy(centre).add(new THREE.Vector3(0.9, 0.7, 1.2).normalize().multiplyScalar(dist));
  controls.target.copy(centre);
  controls.minDistance = radius * 0.05; controls.maxDistance = radius * 20;
  controls.update();
  home = { pos: camera.position.clone(), target: controls.target.clone() };
  // reference grid on the supporting plane (y = 0 in Scan3D output)
  const grid = new THREE.GridHelper(Math.max(size.x, size.z) * 2.5, 20, 0x3b6fd8, 0x2a3a55);
  grid.position.set(centre.x, bounds.min.y, centre.z);
  grid.material.transparent = true; grid.material.opacity = 0.35;
  helpers.add(grid);
  return { size: size.toArray(), centre: centre.toArray(), radius };
}

function prepareMaterials(obj) {
  obj.traverse((o) => {
    if (o.isMesh) {
      meshes.push(o);
      const mats = Array.isArray(o.material) ? o.material : [o.material];
      for (const m of mats) {
        m.side = THREE.DoubleSide;           // reconstructed meshes may have open borders
        if ('roughness' in m) { m.roughness = 0.9; m.metalness = 0.0; }
      }
    }
  });
}

function stats(obj) {
  let vertices = 0, triangles = 0, points = 0, hasColor = false, hasTexture = false;
  obj.traverse((o) => {
    const g = o.geometry; if (!g) return;
    const n = g.attributes.position ? g.attributes.position.count : 0;
    if (o.isPoints) points += n; else { vertices += n; triangles += g.index ? g.index.count / 3 : n / 3; }
    if (g.attributes.color) hasColor = true;
    const m = o.material; if (m && m.map) hasTexture = true;
  });
  return { vertices, triangles, points, hasColor, hasTexture };
}

function load(url, opts = {}) {
  clear();
  msg.textContent = 'Loading model…'; msg.style.display = 'flex';
  const ext = (opts.format || url.split('?')[0].split('.').pop()).toLowerCase();
  const onProgress = (e) => { if (e.total) send('progress', { loaded: e.loaded, total: e.total }); };
  const onError = (e) => {
    const text = (e && e.message) ? e.message : String(e);
    msg.textContent = 'The model could not be loaded.';
    send('error', { code: 'load', message: text });
  };
  const done = (obj, kind) => {
    model = obj; root.add(obj); prepareMaterials(obj);
    const f = frame(obj);
    msg.style.display = 'none';
    send('loaded', { kind, ...stats(obj), ...f });
  };
  try {
    if (ext === 'glb' || ext === 'gltf') {
      new GLTFLoader().load(url, (g) => done(g.scene, 'mesh'), onProgress, onError);
    } else if (ext === 'ply') {
      new PLYLoader().load(url, (geom) => {
        let obj;
        if (geom.index || (geom.attributes.position.count % 3 === 0 && opts.mesh)) {
          geom.computeVertexNormals();
          obj = new THREE.Mesh(geom, new THREE.MeshStandardMaterial({ vertexColors: !!geom.attributes.color }));
        } else {
          const size = (opts.pointSize || 0) || 0.0;
          obj = new THREE.Points(geom, new THREE.PointsMaterial({ size, sizeAttenuation: size > 0, vertexColors: !!geom.attributes.color }));
        }
        done(obj, obj.isPoints ? 'points' : 'mesh');
        if (obj.isPoints && !(opts.pointSize > 0)) {   // pick a point size relative to the model
          obj.material.size = bounds.getSize(new THREE.Vector3()).length() / 600;
          obj.material.sizeAttenuation = true; obj.material.needsUpdate = true;
        }
      }, onProgress, onError);
    } else {
      onError(new Error('Unsupported file format: .' + ext));
    }
  } catch (e) { onError(e); }
}

function setWireframe(on) {
  wireframe = !!on;
  for (const m of meshes) {
    const mats = Array.isArray(m.material) ? m.material : [m.material];
    for (const mat of mats) mat.wireframe = wireframe;
  }
  send('state', { wireframe });
}

function resetView() {
  if (!home) return;
  camera.position.copy(home.pos); controls.target.copy(home.target); controls.update();
}

// ---------------------------------------------------------------- measuring
const raycaster = new THREE.Raycaster();
const ndc = new THREE.Vector2();
let downAt = null;

function markerSize() { return bounds ? bounds.getSize(new THREE.Vector3()).length() / 150 : 0.01; }

function addMarker(p) {
  const s = new THREE.Mesh(new THREE.SphereGeometry(markerSize(), 16, 12),
    new THREE.MeshBasicMaterial({ color: 0xff8a00, depthTest: false }));
  s.renderOrder = 10; s.position.copy(p); measureGroup.add(s);
}

function clearMeasurements() { measureGroup.clear(); picks = []; }

function setMeasureMode(on) {
  measureMode = !!on; picks = [];
  if (!measureMode) clearMeasurements();
  send('state', { measureMode });
}

function pick(clientX, clientY) {
  const r = renderer.domElement.getBoundingClientRect();
  ndc.set(((clientX - r.left) / r.width) * 2 - 1, -((clientY - r.top) / r.height) * 2 + 1);
  raycaster.setFromCamera(ndc, camera);
  raycaster.params.Points = { threshold: markerSize() };
  const hit = raycaster.intersectObjects(model ? [model] : [], true)[0];
  if (!hit) { send('pickMissed'); return; }
  if (picks.length === 2) clearMeasurements();
  picks.push(hit.point.clone()); addMarker(hit.point);
  if (picks.length === 2) {
    const [a, b] = picks;
    const line = new THREE.Line(new THREE.BufferGeometry().setFromPoints([a, b]),
      new THREE.LineBasicMaterial({ color: 0xff8a00, depthTest: false }));
    line.renderOrder = 10; measureGroup.add(line);
    send('measure', { a: a.toArray(), b: b.toArray(), distance: a.distanceTo(b) });
  } else {
    send('picked', { count: 1, point: hit.point.toArray() });
  }
}

renderer.domElement.addEventListener('pointerdown', (e) => { downAt = { x: e.clientX, y: e.clientY, t: Date.now() }; });
renderer.domElement.addEventListener('pointerup', (e) => {
  if (!measureMode || !downAt) return;
  const moved = Math.hypot(e.clientX - downAt.x, e.clientY - downAt.y);
  if (moved < 8 && Date.now() - downAt.t < 500) pick(e.clientX, e.clientY);   // a tap, not a drag
  downAt = null;
});

function snapshot(maxSide = 512) {
  renderer.render(scene, camera);
  const src = renderer.domElement;
  const s = Math.min(1, maxSide / Math.max(src.width, src.height));
  const c = document.createElement('canvas');
  c.width = Math.round(src.width * s); c.height = Math.round(src.height * s);
  c.getContext('2d').drawImage(src, 0, 0, c.width, c.height);
  const data = c.toDataURL('image/jpeg', 0.85);
  send('snapshot', { data });
  return data;
}

window.addEventListener('resize', () => {
  camera.aspect = window.innerWidth / window.innerHeight; camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
});

renderer.domElement.addEventListener('webglcontextlost', (e) => {
  e.preventDefault();
  msg.textContent = 'The graphics memory was exhausted. The model is too large for this device.';
  msg.style.display = 'flex';
  send('error', { code: 'context_lost', message: 'WebGL context lost' });
});

renderer.setAnimationLoop(() => { controls.update(); renderer.render(scene, camera); });

window.viewer = { load, setWireframe, resetView, setMeasureMode, clearMeasurements, snapshot, setTheme };
msg.textContent = '';
send('ready');
