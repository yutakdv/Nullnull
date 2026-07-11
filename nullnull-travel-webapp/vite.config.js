import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 로컬 개발: /api 요청을 백엔드(8000)로 프록시 → CORS 없이 same-origin으로 동작
// (docker-compose에서는 nginx가, Vercel에서는 vercel.json rewrites가 같은 역할을 한다)
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: process.env.VITE_PROXY_TARGET || 'http://127.0.0.1:8000',
        changeOrigin: true,
      },
    },
  },
});
