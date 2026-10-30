import { aspectOf, DeviceProfile, PROFILES, Radii } from './profiles';

/** Clockwise device rotation relative to natural portrait. */
export type Rotation = 0 | 90 | 180 | 270;

export type Corners = [number, number, number, number];

// Inner (1.414) and cover (1.466) ratios are only ~3.7% apart.
const ASPECT_TOLERANCE = 0.015;

/**
 * Exact native resolution first, then closest aspect ratio (for resized
 * screenshots); null when nothing is within tolerance.
 */
export function detectProfile(w: number, h: number): DeviceProfile | null {
  const short = Math.min(w, h);
  const long = Math.max(w, h);
  const exact = PROFILES.find((p) => p.screen.w === short && p.screen.h === long);
  if (exact) return exact;
  const aspect = long / short;
  let best: DeviceProfile | null = null;
  let bestErr = Infinity;
  for (const p of PROFILES) {
    const err = Math.abs(aspect - aspectOf(p)) / aspectOf(p);
    if (err < bestErr) {
      best = p;
      bestErr = err;
    }
  }
  return bestErr <= ASPECT_TOLERANCE ? best : null;
}

/** Default rotation for an image; `flipped` picks the 180°-opposite pose. */
export function detectRotation(w: number, h: number, flipped = false): Rotation {
  const base: Rotation = w > h ? 90 : 0;
  return (flipped ? (base + 180) % 360 : base) as Rotation;
}

export interface LayoutInput {
  imgW: number;
  imgH: number;
  profile: DeviceProfile;
  rotation: Rotation;
  /** Outer margin as a fraction of the screenshot's short side. */
  padding: number;
  /** Multiplier on the screen corner radius. */
  radiusScale: number;
}

export interface Layout {
  canvasW: number;
  canvasH: number;
  /** Design units → pixels. */
  scale: number;
  rotation: Rotation;
  pad: number;
  /** Natural (portrait) body size in pixels. */
  bodyW: number;
  bodyH: number;
  band: number;
  /** Screen corner radii in body space: [topLeft, topRight, bottomRight, bottomLeft]. */
  screenRadius: Corners;
  /** Where the screenshot lands on the canvas (never rotated or resized). */
  screen: { x: number; y: number; w: number; h: number };
}

export function computeLayout(i: LayoutInput): Layout {
  const short = Math.min(i.imgW, i.imgH);
  const long = Math.max(i.imgW, i.imgH);
  const scale = short / i.profile.screen.w;
  const band = i.profile.band * scale;
  const bodyW = short + 2 * band;
  const bodyH = long + 2 * band;
  // Always leave room for buttons and the hinge spine, even with zero padding.
  const proud = Math.max(i.profile.buttonDepth, i.profile.spine?.width ?? 0);
  const pad = Math.max(i.padding * short, proud * scale + 2);
  const landscape = i.rotation === 90 || i.rotation === 270;
  const outW = landscape ? bodyH : bodyW;
  const outH = landscape ? bodyW : bodyH;
  return {
    canvasW: Math.round(outW + 2 * pad),
    canvasH: Math.round(outH + 2 * pad),
    scale,
    rotation: i.rotation,
    pad,
    bodyW,
    bodyH,
    band,
    screenRadius: corners(i.profile.screen.radius).map((r) => r * i.radiusScale * scale) as Corners,
    screen: { x: pad + band, y: pad + band, w: i.imgW, h: i.imgH },
  };
}

export function corners(r: Radii): Corners {
  return typeof r === 'number' ? [r, r, r, r] : r;
}

/** Screen corner radii as they land on the canvas (body corners rotate with the device). */
export function screenCornersOnCanvas(l: Layout): Corners {
  const k = l.rotation / 90;
  const out = [0, 0, 0, 0] as Corners;
  l.screenRadius.forEach((r, i) => (out[(i + k) % 4] = r));
  return out;
}

/** Map a point in natural body space (pixels) to canvas pixels. */
export function toCanvas(l: Layout, x: number, y: number): [number, number] {
  const { bodyW: W, bodyH: H, pad } = l;
  switch (l.rotation) {
    case 0:
      return [pad + x, pad + y];
    case 90:
      return [pad + H - y, pad + x];
    case 180:
      return [pad + W - x, pad + H - y];
    case 270:
      return [pad + y, pad + W - x];
  }
}

/**
 * Apply the transform that makes natural body space draw correctly rotated:
 * after this call, (0,0)..(bodyW,bodyH) covers the body on the canvas.
 */
export function applyBodyTransform(ctx: CanvasRenderingContext2D, l: Layout): void {
  const [ox, oy] = toCanvas(l, 0, 0);
  ctx.translate(ox, oy);
  ctx.rotate((l.rotation * Math.PI) / 180);
}

/** Camera centre on the canvas — handy for tests and debugging. */
export function cameraOnCanvas(l: Layout, p: DeviceProfile): [number, number] {
  return toCanvas(l, l.band + p.camera.x * l.scale, l.band + p.camera.y * l.scale);
}
