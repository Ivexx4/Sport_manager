import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    host: process.env.VITE_HOST || "0.0.0.0",
    port: parseInt(process.env.VITE_PORT || "5174", 10),
    proxy: {
      "/api": {
        target: process.env.VITE_API_TARGET || "http://localhost:8080",
        changeOrigin: true,
        configure: proxy => {
          proxy.on("proxyReq", (proxyRequest) => {
            // Inject the club subdomain into the Host header so the backend
            // TenantFilter can resolve the current tenant.
            proxyRequest.setHeader("host", "clube-piloto.localhost");
          });
        }
      }
    }
  }
});
