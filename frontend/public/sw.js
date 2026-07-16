/* Helix service worker — app-shell offline load.
   Registered ONLY in the production build (see the import.meta.env.PROD guard in
   main.tsx), so the Vite dev server, HMR websocket and module graph are never
   intercepted during development.

   Strategy — deliberately conservative:
     - non-GET requests (mutations)            → pass through, never cached
     - cross-origin requests (gateway API,
       Google Fonts)                           → pass through, never cached
     - any API-looking path (contains /api/ or
       a gateway service prefix), even if it
       were ever mounted same-origin           → pass through, NEVER cache-first
                                                  (authenticated API GETs must not be
                                                  served from a shared cache → leak)
     - same-origin navigations (the SPA shell) → network-first, fall back to the
                                                  cached index.html when offline
     - same-origin static assets on an EXPLICIT
       allowlist (hashed build assets, manifest,
       icons, the shell)                       → cache-first, populated on miss
     - anything else same-origin               → pass through to the network
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

// Gateway service prefixes (see gateway-service application.yml). A same-origin request
// under any of these — or containing "/api/" — is an API call and must never be cached.
const API_PREFIXES = [
  "/config", "/counterparty", "/origination", "/risk", "/decision",
  "/portfolio", "/copilot", "/limits", "/workflow", "/gateway", "/actuator",
];

function looksLikeApi(path) {
  if (path.includes("/api/")) return true;
  return API_PREFIXES.some((p) => path === p || path.startsWith(p + "/"));
}

// The ONLY paths we serve cache-first: the shell, the manifest, icons, and Vite's
// content-hashed build assets (safe to cache immutably; a new build changes the hash).
function isCacheableAsset(path) {
  if (path === "/" || path === "/index.html" || path === "/manifest.webmanifest") return true;
  if (path.startsWith("/assets/")) return true; // Vite hashed build output
  return /\.(?:js|mjs|css|svg|png|ico|webmanifest|woff2?|ttf|eot)$/i.test(path);
}

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
  if (url.origin !== self.location.origin) return; // cross-origin (API + fonts) stay on the network

  // Guard: even if the gateway were ever mounted on this same origin, an authenticated
  // API GET must NEVER be served cache-first (it would leak one user's data to another).
  if (looksLikeApi(url.pathname)) return;

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

  // Only allowlisted static assets are cached; everything else same-origin (which is
  // not a navigation and not an asset) goes straight to the network, uncached.
  if (!isCacheableAsset(url.pathname)) return;

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
