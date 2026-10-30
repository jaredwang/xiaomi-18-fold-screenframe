import './style.css';
import { detectProfile, detectRotation } from './frame/geometry';
import { cameraAtBottom, SAMPLE_WIDTH, statusBand, statusBarMargins } from './frame/orientation';
import { DeviceProfile, INNER, OUTER } from './frame/profiles';
import { DEFAULT_OPTIONS, FrameOptions, renderFramed } from './frame/render';
import { canShareFiles, canvasToBlob, download, downloadZip, shareFiles, takeSharedFiles } from './share';

interface Item {
  id: number;
  name: string;
  bitmap: ImageBitmap;
  /** null = aspect ratio matched no profile; user must pick one. */
  profile: DeviceProfile | null;
  flipped: boolean;
  card: HTMLElement;
}

const PREVIEW_MAX = 900;
const STORE_KEY = 'fold18-options';

const items: Item[] = [];
let nextId = 1;
let options: FrameOptions = loadOptions();

const $ = <T extends HTMLElement>(sel: string) => document.querySelector(sel) as T;
const grid = $<HTMLDivElement>('#grid');
const empty = $<HTMLDivElement>('#empty');
const fileInput = $<HTMLInputElement>('#file');
const status = $<HTMLDivElement>('#status');

function loadOptions(): FrameOptions {
  try {
    return { ...DEFAULT_OPTIONS, ...JSON.parse(localStorage.getItem(STORE_KEY) ?? '{}') };
  } catch {
    return { ...DEFAULT_OPTIONS };
  }
}

function saveOptions() {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify(options));
  } catch {
    /* private mode etc. — options just won't persist */
  }
}

function setStatus(text: string) {
  status.textContent = text;
  status.hidden = !text;
}

function outName(item: Item) {
  return item.name.replace(/\.[^.]+$/, '') + '_framed.png';
}

/** Guess from the status bar whether the inner screen was held camera-down. */
function guessFlipped(bitmap: ImageBitmap): boolean {
  const w = SAMPLE_WIDTH;
  const h = Math.round((bitmap.height * w) / bitmap.width);
  const { y0, y1 } = statusBand(w, h);
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = y1;
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
  ctx.drawImage(bitmap, 0, 0, w, h);
  const rgba = ctx.getImageData(0, y0, w, y1 - y0).data;
  const gray = new Uint8Array(w * (y1 - y0));
  for (let i = 0; i < gray.length; i++) gray[i] = (rgba[i * 4] + rgba[i * 4 + 1] + rgba[i * 4 + 2]) / 3;
  const margins = statusBarMargins({ width: w, height: y1 - y0, data: gray });
  return cameraAtBottom(margins, bitmap.width > bitmap.height) ?? false;
}

function rotationOf(item: Item) {
  return detectRotation(item.bitmap.width, item.bitmap.height, item.flipped);
}

function renderPreview(item: Item) {
  const box = item.card.querySelector('.preview') as HTMLDivElement;
  box.replaceChildren();
  if (!item.profile) {
    box.textContent = `无法识别 ${item.bitmap.width}×${item.bitmap.height}，请选择屏幕`;
    box.classList.add('unknown');
    return;
  }
  box.classList.remove('unknown');
  const scale = Math.min(1, PREVIEW_MAX / Math.max(item.bitmap.width, item.bitmap.height));
  box.append(renderFramed(item.bitmap, item.profile, rotationOf(item), options, scale));
}

async function exportItem(item: Item): Promise<File | null> {
  if (!item.profile) return null;
  const canvas = renderFramed(item.bitmap, item.profile, rotationOf(item), options);
  const blob = await canvasToBlob(canvas);
  return new File([blob], outName(item), { type: 'image/png' });
}

function buildCard(item: Item) {
  const card = document.createElement('article');
  card.className = 'card';
  card.innerHTML = `
    <div class="preview"></div>
    <div class="meta">
      <span class="name"></span>
      <select class="profile" aria-label="屏幕">
        <option value="">屏幕?</option>
        <option value="inner">内屏</option>
        <option value="outer">外屏</option>
      </select>
    </div>
    <div class="actions">
      <button class="flip" title="镜头换到另一侧">旋转 180°</button>
      <button class="save primary">保存</button>
      <button class="share">分享</button>
      <button class="remove" aria-label="移除">✕</button>
    </div>`;
  (card.querySelector('.name') as HTMLElement).textContent = item.name;
  const select = card.querySelector('.profile') as HTMLSelectElement;
  select.value = item.profile?.id ?? '';
  select.addEventListener('change', () => {
    item.profile = select.value === 'inner' ? INNER : select.value === 'outer' ? OUTER : null;
    renderPreview(item);
  });
  card.querySelector('.flip')!.addEventListener('click', () => {
    item.flipped = !item.flipped;
    renderPreview(item);
  });
  card.querySelector('.save')!.addEventListener('click', async () => {
    const f = await exportItem(item);
    if (f) download(f, f.name);
  });
  const shareBtn = card.querySelector('.share') as HTMLButtonElement;
  shareBtn.hidden = typeof navigator.share !== 'function';
  shareBtn.addEventListener('click', async () => {
    const f = await exportItem(item);
    if (f) await shareFiles([f]);
  });
  card.querySelector('.remove')!.addEventListener('click', () => {
    items.splice(items.indexOf(item), 1);
    item.bitmap.close();
    card.remove();
    refreshEmpty();
  });
  return card;
}

function refreshEmpty() {
  empty.hidden = items.length > 0;
  $<HTMLDivElement>('#bulk').hidden = items.length < 2;
}

async function addFiles(files: Iterable<File>) {
  for (const file of files) {
    if (!file.type.startsWith('image/')) continue;
    try {
      const bitmap = await createImageBitmap(file);
      const profile = detectProfile(bitmap.width, bitmap.height);
      const item: Item = {
        id: nextId++,
        name: file.name || `screenshot-${nextId}.png`,
        bitmap,
        profile,
        flipped: profile === INNER && guessFlipped(bitmap),
        card: null!,
      };
      item.card = buildCard(item);
      items.push(item);
      grid.append(item.card);
      renderPreview(item);
    } catch {
      setStatus(`无法读取 ${file.name}`);
    }
  }
  refreshEmpty();
}

async function exportAll(): Promise<File[]> {
  const out: File[] = [];
  for (const [i, item] of items.entries()) {
    setStatus(`正在导出 ${i + 1}/${items.length}…`);
    const f = await exportItem(item);
    if (f) out.push(f);
  }
  setStatus('');
  return out;
}

// --- option controls -------------------------------------------------------

function bindSegmented(name: keyof FrameOptions, parse: (v: string) => FrameOptions[typeof name]) {
  const group = document.querySelectorAll<HTMLInputElement>(`input[name="${name}"]`);
  for (const input of group) {
    input.checked = String(options[name]) === input.value;
    input.addEventListener('change', () => {
      options = { ...options, [name]: parse(input.value) };
      onOptionsChanged();
    });
  }
}

function onOptionsChanged() {
  saveOptions();
  items.forEach(renderPreview);
}

function initControls() {
  const bgCustom = $<HTMLInputElement>('#bg-custom');
  const presets = ['transparent', 'light', 'dark'];
  for (const input of document.querySelectorAll<HTMLInputElement>('input[name="background"]')) {
    input.checked = input.value === 'custom' ? !presets.includes(options.background) : input.value === options.background;
    input.addEventListener('change', () => {
      options = { ...options, background: input.value === 'custom' ? bgCustom.value : input.value };
      onOptionsChanged();
    });
  }
  if (!presets.includes(options.background)) bgCustom.value = options.background;
  bgCustom.addEventListener('input', () => {
    $<HTMLInputElement>('#bg-custom-radio').checked = true;
    options = { ...options, background: bgCustom.value };
    onOptionsChanged();
  });

  bindSegmented('padding', Number);

  const shadow = $<HTMLInputElement>('#shadow');
  shadow.checked = options.shadow;
  shadow.addEventListener('change', () => {
    options = { ...options, shadow: shadow.checked };
    onOptionsChanged();
  });

  const radius = $<HTMLInputElement>('#radius');
  const radiusOut = $<HTMLOutputElement>('#radius-out');
  radius.value = String(options.radiusScale);
  radiusOut.textContent = `${Math.round(options.radiusScale * 100)}%`;
  radius.addEventListener('input', () => {
    options = { ...options, radiusScale: Number(radius.value) };
    radiusOut.textContent = `${Math.round(options.radiusScale * 100)}%`;
    onOptionsChanged();
  });

  $<HTMLButtonElement>('#reset').addEventListener('click', () => {
    options = { ...DEFAULT_OPTIONS };
    saveOptions();
    location.reload();
  });
}

// --- inputs ------------------------------------------------------------------

fileInput.addEventListener('change', () => {
  if (fileInput.files) addFiles(fileInput.files);
  fileInput.value = '';
});

document.addEventListener('dragover', (e) => e.preventDefault());
document.addEventListener('drop', (e) => {
  e.preventDefault();
  if (e.dataTransfer?.files) addFiles(e.dataTransfer.files);
});
document.addEventListener('paste', (e) => {
  if (e.clipboardData?.files.length) addFiles(e.clipboardData.files);
});

$<HTMLButtonElement>('#share-all').hidden = typeof navigator.share !== 'function';
$<HTMLButtonElement>('#share-all').addEventListener('click', async () => {
  const files = await exportAll();
  if (canShareFiles(files)) await shareFiles(files);
  else await downloadZip(files, 'fold18-framed.zip');
});
$<HTMLButtonElement>('#zip-all').addEventListener('click', async () => {
  await downloadZip(await exportAll(), 'fold18-framed.zip');
});
$<HTMLButtonElement>('#clear-all').addEventListener('click', () => {
  for (const item of items.splice(0)) {
    item.bitmap.close();
    item.card.remove();
  }
  refreshEmpty();
});

initControls();
refreshEmpty();
takeSharedFiles().then(addFiles);

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('./sw.js');
}
