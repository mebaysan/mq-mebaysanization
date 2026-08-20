import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Tailwind v4 is configured entirely in CSS (see src/index.css). There is deliberately no
// tailwind.config.js, no postcss.config.js and no autoprefixer in this project.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    // Stays inside the frontend root, so emptyOutDir works normally. Maven copies dist/ into
    // target/classes/static during the prepare-package phase.
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: false,
  },
  server: {
    port: 5173,
    strictPort: true,
    // Development only: `npm run dev` serves the UI while Spring Boot serves the API on 48080.
    proxy: {
      '/api': {
        target: 'http://localhost:48080',
        changeOrigin: true,
      },
    },
  },
})
