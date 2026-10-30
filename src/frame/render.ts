import { applyBodyTransform, computeLayout, Corners, Layout, Rotation, screenCornersOnCanvas } from './geometry';
import { DeviceProfile, EdgePart } from './profiles';

export type Background = 'transparent' | 'light' | 'dark' | string;

export interface FrameOptions {
  background: Background;
  shadow: boolean;
  /** Fraction of the screenshot's short side. */
  padding: number;
  radiusScale: number;
}

export const DEFAULT_OPTIONS: FrameOptions = {
  background: 'light',
  shadow: true,
  padding: 0.12,
  radiusScale: 1,
};

const BACKGROUNDS: Record<string, string> = { light: '#F4F5F5', dark: '#1B1C1E' };

// Graphite finish of the black model, sampled from the official product shots.
const METAL_STOPS: [number, string][] = [
  [0, '#5d5f63'],
  [0.18, '#2f3033'],
  [0.35, '#505256'],
  [0.55, '#262729'],
  [0.72, '#616367'],
  [0.88, '#3e4043'],
  [1, '#5d5f63'],
];

/** Body corners ([tl, tr, br, bl] indices) touching each edge. */
const SPINE_CORNERS: Record<string, number[]> = { top: [0, 1], right: [1, 2], bottom: [2, 3], left: [3, 0] };

type Source = CanvasImageSource & { width: number; height: number };

/** Anything shaped like an HTML canvas: lets Node (@napi-rs/canvas) reuse this file. */
export interface CanvasLike {
  width: number;
  height: number;
  getContext(type: '2d'): CanvasRenderingContext2D | null;
}
export type CanvasFactory = (w: number, h: number) => CanvasLike;

const domCanvas: CanvasFactory = (w, h) => {
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  return c;
};

/**
 * Draw `img` inside the device frame. `outScale` < 1 renders a cheaper preview;
 * export uses 1 so the screenshot keeps its original pixels.
 */
export function renderFramed(
  img: Source,
  profile: DeviceProfile,
  rotation: Rotation,
  opts: FrameOptions,
  outScale = 1,
): HTMLCanvasElement {
  return paint({ w: img.width, h: img.height, img }, profile, rotation, opts, outScale, domCanvas) as HTMLCanvasElement;
}

/**
 * The bare frame at the panel's native resolution with a transparent screen
 * area — for compositing screenshots in other tools.
 */
export function renderTemplate(
  profile: DeviceProfile,
  rotation: Rotation,
  opts: FrameOptions,
  createCanvas: CanvasFactory = domCanvas,
): CanvasLike {
  const landscape = rotation === 90 || rotation === 270;
  const { w, h } = profile.screen;
  return paint({ w: landscape ? h : w, h: landscape ? w : h, img: null }, profile, rotation, opts, 1, createCanvas);
}

function paint(
  shot: { w: number; h: number; img: Source | null },
  profile: DeviceProfile,
  rotation: Rotation,
  opts: FrameOptions,
  outScale: number,
  createCanvas: CanvasFactory,
): CanvasLike {
  const l = computeLayout({
    imgW: shot.w,
    imgH: shot.h,
    profile,
    rotation,
    padding: opts.padding,
    radiusScale: opts.radiusScale,
  });
  const canvas = createCanvas(Math.round(l.canvasW * outScale), Math.round(l.canvasH * outScale));
  const ctx = canvas.getContext('2d')!;
  ctx.scale(outScale, outScale);

  if (opts.background !== 'transparent') {
    ctx.fillStyle = BACKGROUNDS[opts.background] ?? opts.background;
    ctx.fillRect(0, 0, l.canvasW, l.canvasH);
  }

  const s = l.scale;
  const outerR = grow(l.screenRadius, l.band);
  if (profile.spine) {
    // The body butts squarely against the hinge barrel.
    const tight = profile.spine.width * 0.6 * s;
    for (const i of SPINE_CORNERS[profile.spine.edge]) outerR[i] = Math.min(outerR[i], tight);
  }

  ctx.save();
  applyBodyTransform(ctx, l);

  if (opts.shadow) {
    // Shadow offsets are in canvas space, so they stay "downwards" when rotated.
    ctx.save();
    ctx.shadowColor = 'rgba(0, 0, 0, 0.22)';
    ctx.shadowBlur = 70 * s * outScale;
    ctx.shadowOffsetY = 22 * s * outScale;
    ctx.fillStyle = '#000';
    roundRect(ctx, 0, 0, l.bodyW, l.bodyH, outerR);
    ctx.fill();
    ctx.restore();
  }

  if (profile.spine) drawSpine(ctx, l, profile.spine);
  for (const b of profile.buttons) drawButton(ctx, l, b, profile.buttonDepth * s);
  drawRim(ctx, l, profile, outerR);
  for (const m of profile.hingeMarks) drawHingeMark(ctx, l, m, profile.rim * s);

  // Screen area, built in canvas space (some canvas backends mis-bound
  // composited fills under rotation): the unrotated screenshot, or a hole.
  ctx.setTransform(outScale, 0, 0, outScale, 0, 0);
  roundRect(ctx, l.screen.x, l.screen.y, l.screen.w, l.screen.h, screenCornersOnCanvas(l));
  if (shot.img) {
    ctx.clip();
    ctx.drawImage(shot.img, l.screen.x, l.screen.y, l.screen.w, l.screen.h);
  } else {
    ctx.globalCompositeOperation = 'destination-out';
    ctx.fill();
    ctx.globalCompositeOperation = 'source-over';
  }
  ctx.restore();

  ctx.save();
  applyBodyTransform(ctx, l);
  drawCamera(ctx, l.band + profile.camera.x * s, l.band + profile.camera.y * s, profile.camera.r * s);
  ctx.restore();

  return canvas;
}

function grow(r: Corners, d: number): Corners {
  return r.map((v) => v + d) as Corners;
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number | Corners) {
  const clamp = (v: number) => Math.max(0, Math.min(v, w / 2, h / 2));
  ctx.beginPath();
  ctx.roundRect(x, y, w, h, typeof r === 'number' ? clamp(r) : r.map(clamp));
}

/** Concentric layers measured from the reference: metal rim → dark lips → glass edge → bezel. */
function drawRim(ctx: CanvasRenderingContext2D, l: Layout, p: DeviceProfile, outerR: Corners) {
  const s = l.scale;
  const inset = (d: number) => {
    const v = d * s;
    roundRect(ctx, v, v, l.bodyW - 2 * v, l.bodyH - 2 * v, grow(outerR, -v));
  };

  // Brushed graphite rim with light falling unevenly around the perimeter.
  const metal = ctx.createConicGradient(-Math.PI / 4, l.bodyW / 2, l.bodyH / 2);
  for (const [at, color] of METAL_STOPS) metal.addColorStop(at, color);
  inset(0);
  ctx.fillStyle = metal;
  ctx.fill();

  ctx.lineWidth = 2.5 * s;
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.26)';
  inset(4.5);
  ctx.stroke();

  inset(p.rim);
  ctx.fillStyle = '#141516';
  ctx.fill();

  ctx.lineWidth = 2 * s;
  ctx.strokeStyle = '#4f5053';
  inset(p.rim + 5);
  ctx.stroke();

  inset(p.rim + 13);
  ctx.fillStyle = '#070707';
  ctx.fill();

  // Thin grey glint where the cover glass ends.
  ctx.lineWidth = 3 * s;
  ctx.strokeStyle = '#262729';
  inset(p.rim + 19);
  ctx.stroke();
}

/** Hinge barrel: a cylinder half-tucked under the body, slightly longer than it. */
function drawSpine(ctx: CanvasRenderingContext2D, l: Layout, spine: NonNullable<DeviceProfile['spine']>) {
  const s = l.scale;
  const width = spine.width * s;
  const tuck = 8 * s;
  const over = spine.overhang * s;
  const r = (width - tuck) * 0.9;
  let x: number, y: number, w: number, h: number, g: CanvasGradient;
  if (spine.edge === 'left' || spine.edge === 'right') {
    x = spine.edge === 'left' ? -(width - tuck) : l.bodyW - tuck;
    y = -over;
    w = width;
    h = l.bodyH + 2 * over;
    g = ctx.createLinearGradient(x, 0, x + w, 0);
  } else {
    x = -over;
    y = spine.edge === 'top' ? -(width - tuck) : l.bodyH - tuck;
    w = l.bodyW + 2 * over;
    h = width;
    g = ctx.createLinearGradient(0, y, 0, y + h);
  }
  const outerFirst = spine.edge === 'left' || spine.edge === 'top';
  const stops: [number, string][] = [
    [0, '#1a1b1d'],
    [0.18, '#55575b'],
    [0.32, '#6e7074'],
    [0.5, '#3a3b3e'],
    [1, '#202123'],
  ];
  for (const [at, c] of stops) g.addColorStop(outerFirst ? at : 1 - at, c);
  ctx.fillStyle = g;
  roundRect(ctx, x, y, w, h, r);
  ctx.fill();
}

function edgeRect(l: Layout, part: EdgePart, outward: number, inward: number) {
  const { bodyW: W, bodyH: H } = l;
  const s = l.scale;
  const start = part.start * s;
  const len = part.length * s;
  const depth = outward + inward;
  switch (part.edge) {
    case 'top':
      return { x: start, y: -outward, w: len, h: depth };
    case 'bottom':
      return { x: start, y: H - inward, w: len, h: depth };
    case 'left':
      return { x: -outward, y: start, w: depth, h: len };
    case 'right':
      return { x: W - inward, y: start, w: depth, h: len };
  }
}

function drawButton(ctx: CanvasRenderingContext2D, l: Layout, b: EdgePart, depth: number) {
  const r = edgeRect(l, b, depth, depth);
  const horizontal = b.edge === 'top' || b.edge === 'bottom';
  const g = horizontal
    ? ctx.createLinearGradient(0, r.y, 0, r.y + r.h)
    : ctx.createLinearGradient(r.x, 0, r.x + r.w, 0);
  g.addColorStop(0, '#232426');
  g.addColorStop(0.5, '#4a4c50');
  g.addColorStop(1, '#232426');
  ctx.fillStyle = g;
  roundRect(ctx, r.x, r.y, r.w, r.h, depth * 0.6);
  ctx.fill();
}

function drawHingeMark(ctx: CanvasRenderingContext2D, l: Layout, m: EdgePart, rimWidth: number) {
  const s = l.scale;
  const r = edgeRect(l, m, 2 * s, rimWidth * 0.9);
  ctx.fillStyle = '#46484c';
  roundRect(ctx, r.x, r.y, r.w, r.h, rimWidth * 0.45);
  ctx.fill();
  const b = 2.5 * s;
  ctx.fillStyle = '#0b0c0d';
  roundRect(ctx, r.x + b, r.y + b, r.w - 2 * b, r.h - 2 * b, rimWidth * 0.3);
  ctx.fill();
}

function drawCamera(ctx: CanvasRenderingContext2D, x: number, y: number, r: number) {
  ctx.beginPath();
  ctx.arc(x, y, r, 0, Math.PI * 2);
  ctx.fillStyle = '#18191c';
  ctx.fill();
  ctx.lineWidth = r * 0.07;
  ctx.strokeStyle = '#3a3c41';
  ctx.stroke();

  const lens = ctx.createRadialGradient(x, y, 0, x, y, r * 0.66);
  lens.addColorStop(0, '#3d4b60');
  lens.addColorStop(0.55, '#1c2433');
  lens.addColorStop(1, '#0b0d12');
  ctx.beginPath();
  ctx.arc(x, y, r * 0.66, 0, Math.PI * 2);
  ctx.fillStyle = lens;
  ctx.fill();

  ctx.beginPath();
  ctx.arc(x - r * 0.2, y - r * 0.2, r * 0.08, 0, Math.PI * 2);
  ctx.fillStyle = 'rgba(255, 255, 255, 0.18)';
  ctx.fill();
}
