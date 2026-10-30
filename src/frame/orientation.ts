// A screenshot does not record which way up the device was held, but the
// status bar does: Android pads the status bar on the side of the punch-hole.
// Inner screen, camera at the top-left (portrait) or top-right (landscape)
// → that margin is wider. Camera at the bottom → both margins match.

/** Grayscale samples, row-major, `width * height` values. */
export interface Gray {
  width: number;
  height: number;
  data: ArrayLike<number>;
}

export interface Margins {
  left: number;
  right: number;
}

/** Width the status band is analysed at; keeps sampling cheap. */
export const SAMPLE_WIDTH = 400;

// Status bar text sits roughly 1.5%–6% down the long side.
const BAND_TOP = 0.015;
const BAND_BOTTOM = 0.06;
const MIN_EDGE_ENERGY = 400;

/** Top rows to sample, for an image `sampleW`×`sampleH` (SAMPLE_WIDTH wide). */
export function statusBand(sampleW: number, sampleH: number) {
  const long = Math.max(sampleW, sampleH);
  return { y0: Math.floor(BAND_TOP * long), y1: Math.ceil(BAND_BOTTOM * long) };
}

/**
 * Left/right margins (fractions of width) of the status bar content found in
 * the rows of `band`, or null when the band shows no clear status bar.
 */
export function statusBarMargins(band: Gray): Margins | null {
  const { width: w, height: h, data } = band;
  if (w < 3 || h < 3) return null;
  const energy = new Float64Array(w);
  for (let y = 1; y < h; y++) {
    for (let x = 1; x < w; x++) {
      const v = data[y * w + x];
      energy[x] += Math.abs(v - data[y * w + x - 1]) + Math.abs(v - data[(y - 1) * w + x]);
    }
  }
  let max = 0;
  for (const e of energy) max = Math.max(max, e);
  if (max < MIN_EDGE_ENERGY) return null;
  const threshold = max * 0.25;
  let first = -1;
  let last = -1;
  for (let x = 0; x < w; x++) {
    if (energy[x] > threshold) {
      if (first < 0) first = x;
      last = x;
    }
  }
  const m = { left: first / w, right: 1 - last / w };
  // Real status bars keep both ends near the edges; anything else is content.
  if (m.left > 0.16 || m.right > 0.16) return null;
  return m;
}

/**
 * Whether the camera is at the bottom (true), top (false), or unknown (null),
 * for an inner-screen screenshot.
 */
export function cameraAtBottom(m: Margins | null, landscape: boolean): boolean | null {
  if (!m) return null;
  const cameraSide = landscape ? m.right : m.left;
  const otherSide = landscape ? m.left : m.right;
  return cameraSide - otherSide < 0.015;
}
