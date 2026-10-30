// Network-first app cache (so dev reloads never go stale) plus the Web Share
// Target endpoint: shared images are parked in a cache for the page to pick up.
const APP_CACHE = 'app-v1';
const SHARE_CACHE = 'shared-files';

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (url.origin !== location.origin) return;

  if (e.request.method === 'POST' && url.pathname.endsWith('/share-target')) {
    e.respondWith(receiveShare(e.request));
    return;
  }
  if (e.request.method !== 'GET') return;

  e.respondWith(
    fetch(e.request)
      .then((res) => {
        if (res.ok) {
          const copy = res.clone();
          caches.open(APP_CACHE).then((c) => c.put(e.request, copy));
        }
        return res;
      })
      .catch(() => caches.match(e.request, { ignoreSearch: true })),
  );
});

async function receiveShare(request) {
  const form = await request.formData();
  const cache = await caches.open(SHARE_CACHE);
  const stamp = Date.now();
  let i = 0;
  for (const file of form.getAll('images')) {
    if (!(file instanceof File)) continue;
    await cache.put(
      `./__shared/${stamp}-${i++}`,
      new Response(file, {
        headers: { 'Content-Type': file.type, 'X-File-Name': encodeURIComponent(file.name) },
      }),
    );
  }
  return Response.redirect('./?shared=1', 303);
}
