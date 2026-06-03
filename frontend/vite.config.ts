import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The frontend talks to the API gateway. Override with VITE_GATEWAY_URL at build time.
export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
  },
  preview: {
    host: true,
    port: 4173,
  },
});
