const { test, expect } = require('@playwright/test');
const { setupTerminal } = require('./terminal-fixture.cjs');

// Exercise the application's real WebSocket onmessage -> xterm byte path, with
// escape sequences and UTF-8 characters split across binary frames.
async function output(page, text) {
  await page.evaluate(async text => {
    const socket = window.testSockets.find(s => new URL(s.url).pathname === '/ws');
    const bytes = new TextEncoder().encode(text);
    for (let i = 0; i < bytes.length; i += 7) {
      socket.onmessage({ data: bytes.slice(i, i + 7).buffer });
    }
    await new Promise(resolve => window.testTerm.write('', resolve));
  }, text);
}
async function reset(page) {
  await page.evaluate(() => window.testTerm.reset());
}
async function state(page) {
  return page.evaluate(() => {
    const t = window.testTerm, b = t.buffer.active;
    return { cols: t.cols, row: b.baseY + b.cursorY,
      lines: Array.from({ length: b.length }, (_, i) => b.getLine(i).translateToString(true)) };
  });
}
// Reproduce print_progress from https://opencode.ai/install (checked 2026-09-20):
// a fixed 50-cell bar followed by a space, %3d, and %. CR only, not cursor-up.
function installerProgress(percent) {
  const filled = Math.floor(percent * 50 / 100);
  return '\r\x1b[38;5;214m' + '■'.repeat(filled) + '･'.repeat(50 - filled) +
    ' ' + String(percent).padStart(3, ' ') + '%\x1b[0m';
}

test.beforeEach(async ({ page }) => {
  await setupTerminal(page);
  await reset(page);
});

test('CR and erase-line progress fitting the phone stays on one line through binary frames', async ({ page }) => {
  for (let n = 0; n <= 100; n += 5) {
    await output(page, '\r\x1b[2K\x1b[32mDownload ' + n + '%\x1b[0m');
    expect((await state(page)).row).toBe(0);
  }
  const screen = await state(page);
  expect(screen.lines[0]).toBe('Download 100%');
  expect(screen.lines.slice(1).every(line => line === '')).toBe(true);
  await output(page, '\r\nSelesai');
  expect((await state(page)).row).toBe(1);
});

test('fixed-width OpenCode installer reproduces downward growth when narrower than 55 columns', async ({ page }) => {
  expect((await state(page)).cols).toBeLessThan(55);
  await output(page, installerProgress(10));
  const first = await state(page);
  expect(first.row).toBeGreaterThan(0);
  await output(page, installerProgress(20));
  expect((await state(page)).row).toBeGreaterThan(first.row);
});

test('existing A− control lets the fixed-width installer update on one line', async ({ page }) => {
  let screen = await state(page);
  for (let i = 0; screen.cols < 55 && i < 6; i++) {
    await page.locator('#btnFontOut').click();
    screen = await state(page);
  }
  expect(screen.cols).toBeGreaterThanOrEqual(55);
  await reset(page);
  for (const n of [0, 20, 50, 80, 100]) {
    await output(page, installerProgress(n));
    expect((await state(page)).row).toBe(0);
  }
  screen = await state(page);
  expect(screen.lines[0]).toBe('■'.repeat(50) + ' 100%');
  expect(screen.lines.slice(1).every(line => line === '')).toBe(true);
});

test('normal newlines and long command output must not be collapsed to hide progress wrapping', async ({ page }) => {
  const cols = (await state(page)).cols;
  await output(page, 'pertama\r\nkedua\r\n' + 'x'.repeat(cols + 3));
  const screen = await state(page);
  expect(screen.lines.slice(0, 4)).toEqual(['pertama', 'kedua', 'x'.repeat(cols), 'xxx']);
  expect(screen.row).toBe(3);
});
