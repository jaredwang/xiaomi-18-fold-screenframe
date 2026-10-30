// Renders the bare frames (transparent screen + background) to public/templates/.
// Run with: npm run templates
import { Canvas, createCanvas } from '@napi-rs/canvas';
import { mkdirSync, writeFileSync } from 'node:fs';
import { INNER, OUTER } from '../src/frame/profiles';
import { CanvasFactory, renderTemplate } from '../src/frame/render';

const OUT = new URL('../public/templates/', import.meta.url);
mkdirSync(OUT, { recursive: true });

const factory = createCanvas as unknown as CanvasFactory;
const opts = { background: 'transparent', shadow: false, padding: 0, radiusScale: 1 };

const jobs = [
  { name: 'fold18-folded.png', profile: OUTER, rotation: 0 },
  { name: 'fold18-unfolded-portrait.png', profile: INNER, rotation: 0 },
  { name: 'fold18-unfolded-landscape.png', profile: INNER, rotation: 90 },
] as const;

for (const job of jobs) {
  const canvas = renderTemplate(job.profile, job.rotation, opts, factory) as unknown as Canvas;
  writeFileSync(new URL(job.name, OUT), canvas.toBuffer('image/png'));
  console.log(`${job.name}  ${canvas.width}x${canvas.height}`);
}
