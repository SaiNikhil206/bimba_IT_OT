import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev: `npm run dev` on :5174 with the gateway on :8090 (API and site agent calls are proxied, so the session and
// XSRF cookies stay same-origin). Production: the Dockerfile copies dist/ into the Spring Boot jar (static/).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      "/api": "http://localhost:8090",
      "/system": "http://localhost:8090",
    },
  },
  build: { outDir: "dist", emptyOutDir: true, sourcemap: false },
});
