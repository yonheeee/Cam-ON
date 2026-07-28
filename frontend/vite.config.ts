import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // 같은 와이파이의 다른 기기(폰 카메라 테스트 등)에서 접속할 수 있게 0.0.0.0에 바인딩.
  server: {
    host: true,
  },
})
