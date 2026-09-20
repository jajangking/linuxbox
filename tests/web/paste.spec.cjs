const { test, expect } = require('@playwright/test');
const { setupTerminal } = require('./terminal-fixture.cjs');

test.beforeEach(async ({ page }) => {
  await setupTerminal(page);
  await page.evaluate(() => {
    window.testSockets.forEach(socket => { socket.readyState = WebSocket.OPEN; });
    window.clipboardReads = 0;
  });
});

async function clipboard(page, text, error = false) {
  await page.evaluate(({ text, error }) => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
      readText: () => {
        window.clipboardReads++;
        return error ? Promise.reject(new Error('not allowed')) : Promise.resolve(text);
      }
    } });
  }, { text, error });
}
async function sent(page) {
  return page.evaluate(() => window.testSockets.flatMap(socket => socket.sent));
}
async function expectNoTerminalFocus(page) {
  expect(await page.evaluate(() => document.activeElement === window.testTerm.textarea)).toBe(false);
}
async function touchSelection(page) {
  const p = await page.evaluate(() => {
    const r = document.querySelector('.xterm-screen').getBoundingClientRect();
    return { x: r.left + 7 * r.width / window.testTerm.cols, y: r.top + r.height / window.testTerm.rows / 2 };
  });
  const client = await page.context().newCDPSession(page);
  await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ ...p, id: 1 }] });
  await page.waitForTimeout(450);
  await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await client.detach();
  await expect(page.locator('#selectionStart')).toBeVisible();
}

test('Tempel reads only on tap, preserves Unicode/whitespace and adds no Enter', async ({ page }) => {
  const text = '  echo "Halo 👋 日本"  ';
  await clipboard(page, text);
  expect(await page.evaluate(() => window.clipboardReads)).toBe(0);
  // Ctrl lock must not alter pasted text.
  await page.locator('[data-key="ctrl"]').tap();
  await page.locator('#pasteBtn').tap();
  await expect.poll(() => sent(page)).toEqual([text]);
  expect(await page.evaluate(() => window.clipboardReads)).toBe(1);
  await expectNoTerminalFocus(page);
  await expect(page.locator('#pasteDialog')).toBeHidden();
});

test('pasting from selection unlocks input without reopening keyboard', async ({ page }) => {
  await touchSelection(page);
  await clipboard(page, 'pwd');
  await page.locator('#pasteBtn').tap();
  await expect.poll(() => sent(page)).toEqual(['pwd']);
  await expect(page.locator('#selectionStart')).toBeHidden();
  expect(await page.evaluate(() => window.testTerm.getSelection())).toBe('');
  expect(await page.locator('.xterm-helper-textarea').evaluate(el => el.readOnly)).toBe(false);
  await expectNoTerminalFocus(page);
});

test('multiline paste requires review and uses xterm bracketed paste/CRLF normalization', async ({ page }) => {
  await page.evaluate(() => new Promise(resolve => window.testTerm.write('\x1b[?2004h', resolve)));
  await clipboard(page, 'echo satu\r\necho dua\n');
  await page.locator('#pasteBtn').tap();
  await expect(page.locator('#pasteDialog')).toBeVisible();
  expect(await sent(page)).toEqual([]);
  await expect(page.locator('#pasteSend')).toBeFocused();
  await expectNoTerminalFocus(page);
  await page.locator('#pasteSend').tap();
  await expect.poll(() => sent(page)).toEqual(['\x1b[200~echo satu\recho dua\r\x1b[201~']);
  await expect(page.locator('#pasteDialog')).toBeHidden();
  await expect(page.locator('#pasteInput')).toHaveValue('');
});

test('control characters also require review; cancel sends nothing and retains selection', async ({ page }) => {
  await touchSelection(page);
  const original = await page.evaluate(() => window.testTerm.getSelection());
  await clipboard(page, 'abc\x1b[201~def');
  await page.locator('#pasteBtn').tap();
  await expect(page.locator('#pasteDialog')).toBeVisible();
  await page.locator('#pasteCancel').tap();
  expect(await sent(page)).toEqual([]);
  expect(await page.evaluate(() => window.testTerm.getSelection())).toBe(original);
  await expect(page.locator('#pasteInput')).toHaveValue('');
  await expectNoTerminalFocus(page);
});

for (const mode of ['missing', 'denied']) {
  test(`clipboard ${mode}: manual fallback allows review/paste without leaking keyboard input to PTY`, async ({ page }) => {
    if (mode === 'denied') await clipboard(page, '', true);
    else await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true }));
    await page.locator('#pasteBtn').tap();
    await expect(page.locator('#pasteDialog')).toBeVisible();
    await expect(page.locator('#pasteInput')).toBeFocused();
    await page.locator('#pasteInput').fill('echo manual\necho lanjut');
    expect(await sent(page)).toEqual([]);
    await page.locator('#pasteSend').tap();
    await expect.poll(() => sent(page)).toEqual(['echo manual\recho lanjut']);
    await expectNoTerminalFocus(page);
    await expect(page.locator('#pasteInput')).toHaveValue('');
  });
}

test('empty clipboard does not clear selection or open a keyboard', async ({ page }) => {
  await touchSelection(page);
  await clipboard(page, '');
  await page.locator('#pasteBtn').tap();
  await expect(page.locator('#flash')).toContainText('kosong');
  await expect(page.locator('#selectionStart')).toBeVisible();
  expect(await sent(page)).toEqual([]);
  await expectNoTerminalFocus(page);
});

test('disconnected terminal does not read or discard clipboard text', async ({ page }) => {
  await clipboard(page, 'echo test');
  await page.evaluate(() => window.testSockets.forEach(socket => { socket.readyState = 3; }));
  await page.locator('#pasteBtn').tap();
  await expect(page.locator('#flash')).toContainText('belum terhubung');
  expect(await sent(page)).toEqual([]);
  expect(await page.evaluate(() => window.clipboardReads)).toBe(0);
});

test('pending permission cannot send clipboard to a different session or duplicate paste', async ({ page }) => {
  await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
    readText: () => { window.clipboardReads++; return new Promise(resolve => { window.resolveRead = resolve; }); }
  } }));
  await page.locator('#pasteBtn').tap();
  await expect(page.locator('#pasteBtn')).toBeDisabled();
  await page.locator('#pasteBtn').dispatchEvent('click');
  expect(await page.evaluate(() => window.clipboardReads)).toBe(1);
  await page.locator('.tab').filter({ hasText: 'Dua' }).tap();
  await page.evaluate(() => {
    window.testSockets.forEach(socket => { socket.readyState = WebSocket.OPEN; });
    window.resolveRead('secret');
  });
  await expect(page.locator('#flash')).toContainText('Sesi berubah');
  await expect(page.locator('#pasteBtn')).toBeEnabled();
  expect(await sent(page)).toEqual([]);
});

test('disconnect during review preserves text for user without sending it', async ({ page }) => {
  await clipboard(page, 'echo satu\necho dua');
  await page.locator('#pasteBtn').tap();
  await expect(page.locator('#pasteDialog')).toBeVisible();
  await page.evaluate(() => window.testSockets.forEach(socket => { socket.readyState = 3; }));
  await page.locator('#pasteSend').tap();
  expect(await sent(page)).toEqual([]);
  await expect(page.locator('#pasteInput')).toHaveValue('echo satu\necho dua');
  await page.keyboard.press('Escape');
  await expect(page.locator('#pasteDialog')).toBeHidden();
  await expect(page.locator('#pasteInput')).toHaveValue('');
});

test('Android WebView marker uses native clipboard channel (mock), not browser clipboard', async ({ page }) => {
  await clipboard(page, 'must not read');
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'userAgent', { value: navigator.userAgent + ' LinuxBoxWebView/1' });
    window.nativeReads = [];
    window.prompt = message => {
      window.nativeReads.push(message);
      return JSON.stringify({ status: 'ok', text: 'native "teks" 👋' });
    };
  });
  await page.locator('#pasteBtn').tap();
  await expect.poll(() => sent(page)).toEqual(['native "teks" 👋']);
  expect(await page.evaluate(() => window.nativeReads)).toEqual(['linuxbox:clipboard-read']);
  expect(await page.evaluate(() => window.clipboardReads)).toBe(0);
  await expectNoTerminalFocus(page);
});

test('unavailable native clipboard falls back to manual input', async ({ page }) => {
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'userAgent', { value: navigator.userAgent + ' LinuxBoxWebView/1' });
    window.prompt = () => JSON.stringify({ status: 'unavailable' });
  });
  await page.locator('#pasteBtn').tap();
  await expect(page.locator('#pasteDialog')).toBeVisible();
  await expect(page.locator('#pasteInput')).toBeFocused();
  expect(await sent(page)).toEqual([]);
});
