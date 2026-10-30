import { describe, expect, it } from 'vitest';
import { cameraAtBottom, statusBarMargins } from '../src/frame/orientation';

/** A flat band with bright "glyph" blocks between the given x fractions. */
function band(blocks: [number, number][], width = 400, height = 18) {
  const data = new Uint8Array(width * height).fill(20);
  for (const [a, b] of blocks) {
    for (let y = 4; y < height - 4; y++) {
      for (let x = Math.round(a * width); x < Math.round(b * width); x += 3) data[y * width + x] = 240;
    }
  }
  return { width, height, data };
}

describe('statusBarMargins', () => {
  it('finds the outermost status bar content', () => {
    const m = statusBarMargins(band([[0.1, 0.18], [0.85, 0.945]]))!;
    expect(m.left).toBeCloseTo(0.1, 2);
    expect(m.right).toBeCloseTo(0.055, 2);
  });

  it('gives up on a featureless band', () => {
    expect(statusBarMargins(band([]))).toBeNull();
  });

  it('gives up when content starts far from the edges', () => {
    expect(statusBarMargins(band([[0.3, 0.7]]))).toBeNull();
  });
});

describe('cameraAtBottom', () => {
  // Margins measured from real 18 Fold inner-screen screenshots.
  it('portrait, camera top-left: wide left margin', () => {
    expect(cameraAtBottom({ left: 0.1, right: 0.055 }, false)).toBe(false);
  });

  it('portrait, camera bottom-right: symmetric margins', () => {
    expect(cameraAtBottom({ left: 0.05, right: 0.055 }, false)).toBe(true);
  });

  it('landscape, camera top-right: wide right margin', () => {
    expect(cameraAtBottom({ left: 0.035, right: 0.078 }, true)).toBe(false);
  });

  it('landscape, camera bottom-left: symmetric margins', () => {
    expect(cameraAtBottom({ left: 0.035, right: 0.04 }, true)).toBe(true);
  });

  it('unknown without a status bar', () => {
    expect(cameraAtBottom(null, false)).toBeNull();
  });
});
