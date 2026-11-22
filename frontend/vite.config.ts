import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
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
