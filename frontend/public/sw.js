/* Helix service worker — app-shell offline load.
   Registered ONLY in the production build (see the import.meta.env.PROD guard in
   main.tsx), so the Vite dev server, HMR websocket and module graph are never
   intercepted during development.

   Strategy — deliberately conservative:
     - non-GET requests (mutations)            → pass through, never cached
     - cross-origin requests (gateway API,
       Google Fonts)                           → pass through, never cached
     - same-origin navigations (the SPA shell) → network-first, fall back to the
                                                  cached index.html when offline
     - same-origin static assets (hashed JS/
       CSS, icons, manifest, svg)              → cache-first, populated on miss
*/
const CACHE = "helix-shell-v1";
const SHELL = [
  "/",
  "/index.html",
  "/manifest.webmanifest",
  "/icon.svg",
  "/icon-192.png",
  "/icon-512.png",
];

self.addEventListener("install", (event) => {
  // allSettled: a single missing shell asset must not fail the whole install.
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => Promise.allSettled(SHELL.map((u) => cache.add(u))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  // Drop caches from previous versions, then take control of open clients.
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return; // never cache mutations (POST/PUT/PATCH/DELETE)

  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return; // API + fonts stay on the network

  // SPA navigations: network-first so a fresh deploy is picked up, with the cached
  // shell as the offline fallback.
  if (req.mode === "navigate") {
    event.respondWith(
      fetch(req)
        .then((res) => {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put("/index.html", copy)).catch(() => {});
          return res;
        })
        .catch(() => caches.match(req).then((hit) => hit || caches.match("/index.html")))
    );
    return;
  }

  // Static assets: cache-first, populate the cache on a miss.
  event.respondWith(
    caches.match(req).then(
      (hit) =>
        hit ||
        fetch(req).then((res) => {
          if (res && res.ok && res.type === "basic") {
            const copy = res.clone();
            caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
          }
          return res;
        })
    )
  );
});
