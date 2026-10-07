import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// `npm run dev` proxies the Django backend running on localhost:8000
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8000",
      "/ws": { target: "ws://localhost:8000", ws: true },
    },
  },
});
