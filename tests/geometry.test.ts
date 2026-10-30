import { describe, expect, it } from 'vitest';
import {
  cameraOnCanvas,
  computeLayout,
  detectProfile,
  detectRotation,
  screenCornersOnCanvas,
  toCanvas,
} from '../src/frame/geometry';
import { INNER, OUTER } from '../src/frame/profiles';

describe('detectProfile', () => {
  it('matches the inner screen in either orientation', () => {
    expect(detectProfile(1672, 2364)).toBe(INNER);
    expect(detectProfile(2364, 1672)).toBe(INNER);
  });

  it('matches the cover screen, native or resized', () => {
    expect(detectProfile(1168, 1712)).toBe(OUTER);
    expect(detectProfile(1712, 1168)).toBe(OUTER);
    expect(detectProfile(584, 856)).toBe(OUTER);
  });

  it('tells inner from cover when resized', () => {
    expect(detectProfile(836, 1182)).toBe(INNER);
  });

  it('rejects unrelated ratios', () => {
    expect(detectProfile(1000, 1000)).toBeNull();
    expect(detectProfile(1080, 1920)).toBeNull();
    expect(detectProfile(1080, 2520)).toBeNull();
  });
});

describe('detectRotation', () => {
  it('uses portrait 0 / landscape 90 by default and flips by 180', () => {
    expect(detectRotation(1000, 1400)).toBe(0);
    expect(detectRotation(1400, 1000)).toBe(90);
    expect(detectRotation(1000, 1400, true)).toBe(180);
    expect(detectRotation(1400, 1000, true)).toBe(270);
  });
});

describe('computeLayout', () => {
  const base = { profile: INNER, padding: 0.1, radiusScale: 1 };

  it('keeps the screenshot at its original size', () => {
    const l = computeLayout({ ...base, imgW: 1672, imgH: 2364, rotation: 0 });
    expect(l.screen).toMatchObject({ w: 1672, h: 2364 });
    expect(l.canvasW).toBe(Math.round(1672 + 100 + 2 * l.pad));
    expect(l.canvasH).toBe(Math.round(2364 + 100 + 2 * l.pad));
  });

  it('swaps canvas dimensions for landscape', () => {
    const l = computeLayout({ ...base, imgW: 2364, imgH: 1672, rotation: 90 });
    expect(l.canvasW).toBeGreaterThan(l.canvasH);
    expect(l.screen).toMatchObject({ w: 2364, h: 1672 });
  });

  it('maps the body corners onto the canvas for every rotation', () => {
    for (const rotation of [0, 90, 180, 270] as const) {
      const portrait = rotation % 180 === 0;
      const l = computeLayout({ ...base, imgW: portrait ? 1672 : 2364, imgH: portrait ? 2364 : 1672, rotation });
      const pts = [toCanvas(l, 0, 0), toCanvas(l, l.bodyW, l.bodyH)];
      const xs = pts.map((p) => p[0]).sort((a, b) => a - b);
      const ys = pts.map((p) => p[1]).sort((a, b) => a - b);
      expect(xs[0]).toBeCloseTo(l.pad);
      expect(ys[0]).toBeCloseTo(l.pad);
      expect(xs[1]).toBeCloseTo(l.canvasW - l.pad, 0);
      expect(ys[1]).toBeCloseTo(l.canvasH - l.pad, 0);
    }
  });

  it('rotates per-corner screen radii onto the canvas', () => {
    const outer = { profile: OUTER, padding: 0, radiusScale: 1 };
    // Cover screen: tight corners on the hinge (left) side.
    expect(screenCornersOnCanvas(computeLayout({ ...outer, imgW: 1168, imgH: 1712, rotation: 0 }))).toEqual([28, 154, 154, 28]);
    expect(screenCornersOnCanvas(computeLayout({ ...outer, imgW: 1712, imgH: 1168, rotation: 90 }))).toEqual([28, 28, 154, 154]);
    expect(screenCornersOnCanvas(computeLayout({ ...outer, imgW: 1168, imgH: 1712, rotation: 180 }))).toEqual([154, 28, 28, 154]);
  });

  it('puts the camera where the reference renders have it', () => {
    const corner = (rotation: 0 | 90 | 180 | 270) => {
      const portrait = rotation % 180 === 0;
      const l = computeLayout({ ...base, imgW: portrait ? 1672 : 2364, imgH: portrait ? 2364 : 1672, rotation });
      const [x, y] = cameraOnCanvas(l, INNER);
      return `${x < l.canvasW / 2 ? 'left' : 'right'}-${y < l.canvasH / 2 ? 'top' : 'bottom'}`;
    };
    expect(corner(0)).toBe('left-top');
    expect(corner(90)).toBe('right-top');
    expect(corner(180)).toBe('right-bottom');
    expect(corner(270)).toBe('left-bottom');
  });
});
