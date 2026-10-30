// Files shared into the installed PWA are stashed in this cache by sw.js.
const SHARE_CACHE = 'shared-files';

export async function takeSharedFiles(): Promise<File[]> {
  const params = new URLSearchParams(location.search);
  if (!params.has('shared') || !('caches' in window)) return [];
  history.replaceState(null, '', location.pathname);
  const cache = await caches.open(SHARE_CACHE);
  const files: File[] = [];
  for (const req of await cache.keys()) {
    const res = await cache.match(req);
    if (!res) continue;
    const blob = await res.blob();
    const name = decodeURIComponent(res.headers.get('X-File-Name') ?? 'shared.png');
    files.push(new File([blob], name, { type: blob.type }));
    await cache.delete(req);
  }
  return files;
}

export function canvasToBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('导出失败'))), 'image/png'),
  );
}

export function download(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

export function canShareFiles(files: File[]): boolean {
  return typeof navigator.canShare === 'function' && navigator.canShare({ files });
}

export async function shareFiles(files: File[]) {
  try {
    await navigator.share({ files });
  } catch (e) {
    // Dismissing the share sheet is not an error worth reporting.
    if ((e as DOMException).name !== 'AbortError') throw e;
  }
}

export async function downloadZip(files: File[], name: string) {
  const { default: JSZip } = await import('jszip');
  const zip = new JSZip();
  for (const f of files) zip.file(f.name, f);
  download(await zip.generateAsync({ type: 'blob' }), name);
}
