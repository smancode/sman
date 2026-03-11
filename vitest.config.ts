import { defineConfig } from "vitest/config";
import { sveltekit } from "@sveltejs/kit/vite";
import path from "path";

export default defineConfig({
  plugins: [sveltekit()],
  test: {
    include: ["src/**/*.{test,spec}.{js,ts}"],
    globals: true,
    environment: "jsdom",
    setupFiles: ["./src/tests/setup.ts"],
  },
  resolve: {
    alias: {
      $lib: path.resolve("./src/lib"),
      $components: path.resolve("./src/components"),
    },
  },
});
