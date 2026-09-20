const { expect } = require("@playwright/test");
const fs = require('node:fs');
const path = require('node:path');

const html = fs.readFileSync(path.join(__dirname, '../../android/app/src/main/assets/web/index.html'), 'utf8');
const xterm = fs.readFileSync(require.resolve('@xterm/xterm'), 'utf8');
const fit = fs.readFileSync(require.resolve('@xterm/addon-fit'), 'utf8');
const css = fs.readFileSync(path.join(path.dirname(require.resolve('@xterm/xterm')), '../css/xterm.css'), 'utf8');

// Real xterm 6 + FitAddon; only the PTY/server and clipboard are replaced.
async function setupTerminal(page) {
  await page.addInitScript(() => {
    window.testSockets = [];
    window.WebSocket = class {
      constructor(url) { this.url = url; this.sent = []; window.testSockets.push(this); }
      static OPEN = 1;
      static CONNECTING = 0;
      readyState = 0;
      send(data) { this.sent.push(data); }
      close() { this.readyState = 3; }
    };
    document.execCommand = command => {
      if (command !== 'copy') return false;
      window.copiedText = document.activeElement.value;
      return window.copyAllowed !== false;
    };
  });
  await page.route('http://linuxbox.test/**', async route => {
    const url = new URL(route.request().url());
    const assets = {
      '/': ['text/html', html],
      '/xterm.js': ['application/javascript', xterm + '\nwindow.Terminal = class extends window.Terminal { constructor(...args) { super(...args); window.testTerm = this; } };'],
      '/fit.js': ['application/javascript', fit],
      '/xterm.css': ['text/css', css],
      '/search.js': ['application/javascript', ''],
      '/weblinks.js': ['application/javascript', '']
    };
    if (assets[url.pathname]) {
      const [contentType, body] = assets[url.pathname];
      return route.fulfill({ contentType, body });
    }
    const data = url.pathname === '/api/sessions' ? { sessions: [
      { id: 'one', name: 'Satu', alive: true }, { id: 'two', name: 'Dua', alive: true }
    ] } : {};
    return route.fulfill({ json: data });
  });
  await page.goto('http://linuxbox.test/');
  await expect(page.locator('#overlay')).not.toHaveClass('show');
  await page.evaluate(() => new Promise(resolve => window.testTerm.write('alpha bravo charlie\r\ndelta echo foxtrot', resolve)));
}

module.exports = { setupTerminal, html, xterm, fit, css };
