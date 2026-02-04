<template>
  <router-view />
</template>

<style>
/*
 * GLOBAL STYLES: Minimalist Zen / 禅意极简
 */

* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

:root {
  --black: #1a1a1a;
  --white: #ffffff;
  --gray-100: #fafafa;
  --gray-200: #f0f0f0;
  --gray-400: #a0a0a0;
  --gray-600: #666666;
  --radius: 24px;
  --transition: 0.2s ease;
}

#app {
  width: 100%;
  height: 100vh;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}
</style>
