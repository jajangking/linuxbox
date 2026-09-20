const { test, expect } = require('@playwright/test');
const { setupTerminal, html, xterm, fit, css } = require('./terminal-fixture.cjs');

test.beforeEach(async ({ page }) => { await setupTerminal(page); });

async function cell(page, col, row) {
  return page.evaluate(({ col, row }) => {
    const t = window.testTerm, r = document.querySelector('.xterm-screen').getBoundingClientRect();
    return { x: r.left + (col + 0.5) * r.width / t.cols,
      y: r.top + (row + 0.5) * r.height / t.rows };
  }, { col, row });
}
async function touch(client, type, point) {
  await client.send('Input.dispatchTouchEvent', {
    type, touchPoints: point ? [{ ...point, id: 1, radiusX: 1, radiusY: 1 }] : []
  });
}
async function select(page, from = [6, 0], to = [10, 0]) {
  const client = await page.context().newCDPSession(page);
  await touch(client, 'touchStart', await cell(page, ...from));
  await page.waitForTimeout(450);
  await touch(client, 'touchMove', await cell(page, ...to));
  await touch(client, 'touchEnd');
  await client.detach();
  await expect(page.locator('#selectionActions')).toHaveClass('show');
}
async function selected(page) {
  return page.evaluate(() => window.testTerm.getSelection());
}
async function dragHandle(page, selector, cols, rows = 0) {
  const box = await page.locator(selector).boundingBox();
  const dims = await page.evaluate(() => {
    const r = document.querySelector('.xterm-screen').getBoundingClientRect();
    return { cw: r.width / window.testTerm.cols, lh: r.height / window.testTerm.rows };
  });
  const start = { x: box.x + box.width / 2, y: box.y + 19 };
  const client = await page.context().newCDPSession(page);
  await touch(client, 'touchStart', start);
  await touch(client, 'touchMove', { x: start.x + cols * dims.cw, y: start.y + rows * dims.lh });
  await touch(client, 'touchEnd');
  await client.detach();
}
async function expectNoKeyboardFocus(page) {
  expect(await page.evaluate(() => document.activeElement === window.testTerm.textarea)).toBe(false);
}

test('long press selects part of a line; both markers can adjust it without focusing input', async ({ page }) => {
  await select(page);
  expect(await selected(page)).toBe('bravo');
  await expect(page.locator('#selectionStart')).toBeVisible();
  await expect(page.locator('#selectionEnd')).toBeVisible();
  await expect(page.locator('.xterm-helper-textarea')).toHaveAttribute('readonly', '');
  await dragHandle(page, '#selectionStart', 1);
  expect(await selected(page)).toBe('ravo');
  await dragHandle(page, '#selectionEnd', -1);
  expect(await selected(page)).toBe('rav');
  await expectNoKeyboardFocus(page);
  await page.locator('#copyBtn').tap();
  expect(await page.evaluate(() => window.copiedText)).toBe('rav');
  await expect(page.locator('#selectionActions')).not.toHaveClass('show');
  await expectNoKeyboardFocus(page);
});

test('selection survives new touches and synthetic mouse clicks; Ketik explicitly enables input', async ({ page }) => {
  await select(page);
  const p = await cell(page, 15, 0);
  await page.touchscreen.tap(p.x, p.y);
  await page.locator('.xterm-screen').dispatchEvent('mousedown');
  await page.locator('.xterm-screen').dispatchEvent('click');
  expect(await selected(page)).toBe('bravo');
  await expectNoKeyboardFocus(page);
  await page.locator('#typeBtn').tap();
  expect(await selected(page)).toBe('');
  await expect(page.locator('.xterm-helper-textarea')).toBeFocused();
  expect(await page.locator('.xterm-helper-textarea').evaluate(el => el.readOnly)).toBe(false);
});

test('tap focuses input, scroll does not; horizontal movement cancels long press', async ({ page }) => {
  const p = await cell(page, 3, 1);
  await page.touchscreen.tap(p.x, p.y);
  await expect(page.locator('.xterm-helper-textarea')).toBeFocused();
  const client = await page.context().newCDPSession(page);
  await touch(client, 'touchStart', p);
  await touch(client, 'touchMove', { x: p.x + 40, y: p.y });
  await page.waitForTimeout(450);
  await touch(client, 'touchEnd');
  expect(await selected(page)).toBe('');
  await expectNoKeyboardFocus(page);
  await client.detach();
});

test('scrollback selection uses absolute buffer rows, including reversed multiline ranges', async ({ page }) => {
  await page.evaluate(() => new Promise(resolve => {
    window.testTerm.reset();
    window.testTerm.write(Array.from({ length: 100 }, (_, i) => 'line' + String(i).padStart(3, '0') + ' text').join('\r\n'), resolve);
  }));
  await page.evaluate(() => window.testTerm.scrollToLine(30));
  expect(await page.evaluate(() => window.testTerm.buffer.active.viewportY)).toBe(30);
  await select(page, [6, 2], [0, 1]);
  expect(await selected(page)).toBe('line031 text\nline032');
  await dragHandle(page, '#selectionEnd', 5);
  expect(await selected(page)).toBe('line031 text\nline032 text');
  await page.locator('#copyBtn').tap();
  expect(await page.evaluate(() => window.copiedText)).toBe('line031 text\nline032 text');
});

test('failed copy retains selection and markers for retry, cancel never opens keyboard', async ({ page }) => {
  await select(page);
  await page.evaluate(() => { window.copyAllowed = false; });
  await page.locator('#copyBtn').tap();
  expect(await selected(page)).toBe('bravo');
  await expect(page.locator('#selectionStart')).toBeVisible();
  await expect(page.locator('#flash')).toHaveText('gagal menyalin');
  await expectNoKeyboardFocus(page);
  await page.locator('#cancelSelection').tap();
  expect(await selected(page)).toBe('');
  await expect(page.locator('#selectionStart')).toBeHidden();
  await expectNoKeyboardFocus(page);
});

test('markers follow resize/font changes; clear and switching sessions release selection', async ({ page }) => {
  await select(page);
  await page.setViewportSize({ width: 412, height: 640 });
  expect(await selected(page)).toBe('bravo');
  await page.locator('#btnFontIn').tap();
  await expect(page.locator('#selectionStart')).toBeVisible();
  await dragHandle(page, '#selectionEnd', -1);
  expect(await selected(page)).toBe('brav');
  await page.locator('#btnClear').tap();
  await expect(page.locator('#selectionActions')).not.toHaveClass('show');
  await page.evaluate(() => new Promise(resolve => window.testTerm.write('\r\nalpha bravo charlie', resolve)));
  await select(page, [6, 1], [10, 1]);
  await page.locator('.tab').filter({ hasText: 'Dua' }).tap();
  await expect(page.locator('#selectionStart')).toBeHidden();
  expect(await selected(page)).toBe('');
  expect(await page.locator('.xterm-helper-textarea').evaluate(el => el.readOnly)).toBe(false);
});

test('search field retains focus rather than redirecting it to terminal', async ({ page }) => {
  await select(page);
  await page.locator('#btnSearch').tap();
  await page.locator('#searchInput').tap();
  await expect(page.locator('#searchInput')).toBeFocused();
  expect(await selected(page)).toBe('');
});

test('touchcancel aborts a pending hold without selection or keyboard', async ({ page }) => {
  const client = await page.context().newCDPSession(page);
  await touch(client, 'touchStart', await cell(page, 6, 0));
  await touch(client, 'touchCancel');
  await page.waitForTimeout(450);
  expect(await selected(page)).toBe('');
  await expectNoKeyboardFocus(page);
  await client.detach();
});

test('desktop mouse selection and typing remain available', async ({ browser }) => {
  // Use this page's routes with touch emulation disabled for the input checks.
  const context = await browser.newContext({ hasTouch: false, viewport: { width: 900, height: 700 } });
  const page = await context.newPage();
  await page.route('http://linuxbox.test/**', route => {
    const name = new URL(route.request().url()).pathname;
    const data = name === '/' ? html : name === '/xterm.js' ? xterm + '\nwindow.Terminal = class extends window.Terminal { constructor(...a) { super(...a); window.testTerm = this; } };' : name === '/fit.js' ? fit : name === '/xterm.css' ? css : '';
    return route.fulfill({ contentType: name === '/' ? 'text/html' : name.endsWith('.css') ? 'text/css' : name.startsWith('/api/') ? 'application/json' : 'application/javascript', body: name.startsWith('/api/') ? '{}' : data });
  });
  await page.goto('http://linuxbox.test/');
  await expect(page.locator('#overlay')).not.toHaveClass('show');
  await page.evaluate(() => new Promise(resolve => window.testTerm.write('alpha bravo charlie', resolve)));
  const a = await cell(page, 6, 0), b = await cell(page, 11, 0);
  await page.mouse.move(a.x - 3, a.y);
  await page.mouse.down();
  await page.mouse.move(b.x - 3, b.y, { steps: 10 });
  await page.mouse.up();
  expect(await selected(page)).toBe('bravo');
  await context.close();
});

test('single-character selection at left edge has two independently draggable markers', async ({ page }) => {
  await select(page, [0, 0], [0, 0]);
  expect(await selected(page)).toBe('a');
  await dragHandle(page, '#selectionEnd', 3);
  expect(await selected(page)).toBe('alph');
  await dragHandle(page, '#selectionStart', 1);
  expect(await selected(page)).toBe('lph');
});

test('closing keyboard cannot resize away a live selection; fit resumes after cancel', async ({ page }) => {
  await page.setViewportSize({ width: 412, height: 500 });
  // Wait for window resize and FitAddon, not just setViewportSize's CDP response.
  await page.waitForTimeout(100);
  const initialRows = await page.evaluate(() => window.testTerm.rows);
  const point = await cell(page, 6, 0);
  await page.touchscreen.tap(point.x, point.y);
  await expect(page.locator('.xterm-helper-textarea')).toBeFocused();
  await select(page);
  await expectNoKeyboardFocus(page);
  await page.setViewportSize({ width: 412, height: 820 });
  await page.waitForTimeout(100);
  expect(await selected(page)).toBe('bravo');
  expect(await page.evaluate(() => window.testTerm.rows)).toBe(initialRows);
  await expect(page.locator('#selectionStart')).toBeVisible();
  await page.locator('#cancelSelection').tap();
  expect(await page.evaluate(() => window.testTerm.rows)).toBeGreaterThan(initialRows);
  await expectNoKeyboardFocus(page);
});

test('scrolling with an active selection keeps text and repositions visible handles', async ({ page }) => {
  await page.evaluate(() => new Promise(resolve => {
    window.testTerm.reset();
    window.testTerm.write(Array.from({ length: 100 }, (_, i) => 'line' + String(i).padStart(3, '0') + ' text').join('\r\n'), resolve);
  }));
  await page.evaluate(() => window.testTerm.scrollToLine(30));
  await select(page, [0, 2], [6, 2]);
  const previous = await page.locator('#selectionEnd').boundingBox();
  const client = await page.context().newCDPSession(page);
  const point = await cell(page, 20, 8);
  await touch(client, 'touchStart', point);
  await touch(client, 'touchMove', { x: point.x, y: point.y - 25 });
  await touch(client, 'touchEnd');
  await client.detach();
  expect(await selected(page)).toBe('line032');
  expect((await page.locator('#selectionEnd').boundingBox()).y).toBeLessThan(previous.y);
  await expectNoKeyboardFocus(page);
});

test('floating copy actions do not cover handles near the bottom-right', async ({ page }) => {
  const { cols, rows } = await page.evaluate(() => ({ cols: window.testTerm.cols, rows: window.testTerm.rows }));
  await page.evaluate(() => new Promise(resolve => {
    const t = window.testTerm;
    t.reset();
    t.write(Array.from({ length: t.rows }, () => 'x'.repeat(t.cols - 1)).join('\r\n'), resolve);
  }));
  await select(page, [cols - 7, rows - 4], [cols - 3, rows - 4]);
  expect(await selected(page)).toBe('xxxxx');
  await dragHandle(page, '#selectionEnd', -1);
  expect(await selected(page)).toBe('xxxx');
  await dragHandle(page, '#selectionStart', 1);
  expect(await selected(page)).toBe('xxx');
});
