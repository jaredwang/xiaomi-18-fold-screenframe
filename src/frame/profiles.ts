// Device geometry in "design units" = the panel's native pixels, always
// described in the device's natural portrait orientation (camera at the
// top-left for the inner screen). Every value scales with the screenshot's
// short side. Screen sizes, corner radii and cutouts come from the device
// (`dumpsys display` / `dumpsys window`); the bezel and rim were measured from
// reference renders.

export type Edge = 'top' | 'right' | 'bottom' | 'left';

/** Uniform radius, or [topLeft, topRight, bottomRight, bottomLeft]. */
export type Radii = number | [number, number, number, number];

export interface EdgePart {
  edge: Edge;
  /** Distance from the body's top/left corner along the edge. */
  start: number;
  length: number;
}

export interface DeviceProfile {
  id: 'inner' | 'outer';
  name: string;
  /** Screen size in design units (portrait). */
  screen: { w: number; h: number; radius: Radii };
  /** Total width of metal rim + black bezel around the screen. */
  band: number;
  /** Width of the outer metal rim (part of `band`). */
  rim: number;
  /** Punch-hole centre in screen coordinates. */
  camera: { x: number; y: number; r: number };
  buttons: EdgePart[];
  /** How far buttons stick out of the body. */
  buttonDepth: number;
  /** Dark slots on the rim where the hinge meets the frame. */
  hingeMarks: EdgePart[];
  /** Folded only: the hinge barrel standing proud of one edge. */
  spine?: { edge: Edge; width: number; overhang: number };
}

export const INNER: DeviceProfile = {
  id: 'inner',
  name: '内屏',
  screen: { w: 1672, h: 2364, radius: 142 },
  band: 50,
  rim: 12,
  camera: { x: 95, y: 96, r: 36 },
  buttons: [
    { edge: 'top', start: 355, length: 251 },
    { edge: 'top', start: 666, length: 123 },
  ],
  buttonDepth: 7,
  hingeMarks: [
    { edge: 'left', start: 1232 - 28, length: 56 },
    { edge: 'right', start: 1232 - 28, length: 56 },
  ],
};

// Cover screen sits on the back of the left half: the hinge side (left) has
// tight corners, the free side carries the same buttons as the open device.
export const OUTER: DeviceProfile = {
  id: 'outer',
  name: '外屏',
  screen: { w: 1168, h: 1712, radius: [28, 154, 154, 28] },
  band: 50,
  rim: 12,
  camera: { x: 584, y: 63, r: 26 },
  buttons: [
    { edge: 'right', start: 355, length: 251 },
    { edge: 'right', start: 666, length: 123 },
  ],
  buttonDepth: 7,
  hingeMarks: [],
  // Traced from the official black-model cover shots.
  spine: { edge: 'left', width: 26, overhang: 9 },
};

export const PROFILES: DeviceProfile[] = [INNER, OUTER];

export function aspectOf(p: DeviceProfile): number {
  return p.screen.h / p.screen.w;
}
