import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import "./styles.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);

// Register the PWA service worker in PRODUCTION BUILDS ONLY. In `vite` dev,
// (import.meta as any).env.PROD is false, so the SW is never installed and the
// dev server / HMR websocket / module graph are never intercepted. Best-effort:
// the PWA layer is progressive, so any registration failure is swallowed.
if ((import.meta as any).env?.PROD && "serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").catch(() => { /* PWA is optional — ignore */ });
  });
}
