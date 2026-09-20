const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests/web',
  fullyParallel: true,
  use: {
    browserName: 'chromium',
    viewport: { width: 412, height: 820 },
    hasTouch: true,
    isMobile: true,
    launchOptions: process.env.CHROMIUM_PATH ? {
      executablePath: process.env.CHROMIUM_PATH,
      args: ['--no-sandbox', '--disable-dev-shm-usage']
    } : {}
  }
});
