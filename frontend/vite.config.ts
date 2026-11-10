import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  server: {
    // During development the API is the Spring Boot server
    proxy: { '/api': 'http://localhost:8080' },
  },
})
