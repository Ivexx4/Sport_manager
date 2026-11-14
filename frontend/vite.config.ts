import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: false,
        configure: proxy => {
          proxy.on("proxyReq", (proxyRequest, request) => {
            if (request.headers.host) proxyRequest.setHeader("host", request.headers.host);
          });
        }
      }
    }
  }
});
