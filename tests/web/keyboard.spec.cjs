const { test, expect } = require('@playwright/test');
const { setupTerminal } = require('./terminal-fixture.cjs');

test.beforeEach(async ({ page }) => {
  await setupTerminal(page);
  await page.evaluate(() => {
    window.testSockets.forEach(s => { s.readyState = WebSocket.OPEN; });
    window.testTerm.focus();
    window.inputBlurs = 0;
    window.testTerm.textarea.addEventListener('blur', () => window.inputBlurs++);
  });
});
const sent = page => page.evaluate(() => window.testSockets.flatMap(s => s.sent));

async function swipe(page, cols, vertical = false, hold = false) {
  const points = await page.evaluate(({ cols, vertical }) => {
    const t = window.testTerm, r = document.querySelector('.xterm-screen').getBoundingClientRect();
    const cw = r.width / t.cols, lh = r.height / t.rows;
    const start = { x: r.left + cw * 20, y: r.top + lh * 5.5 };
    return { start, end: { x: start.x + (vertical ? 0 : cols * Math.max(8, cw)), y: start.y + (vertical ? cols * lh : 0) } };
  }, { cols, vertical });
  const client = await page.context().newCDPSession(page);
  await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ ...points.start, id: 1 }] });
  if (hold) await page.waitForTimeout(450);
  await client.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ ...points.end, id: 1 }] });
  await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await client.detach();
}

test('toolbar buttons preserve existing keyboard focus without a blur/refocus cycle', async ({ page }) => {
  for (const key of ['esc', 'tab', 'ctrl', 'ctrl', 'backspace', 'left', 'right', 'home', 'end']) {
    await page.locator(`[data-key="${key}"]`).tap();
    await expect(page.locator('.xterm-helper-textarea')).toBeFocused();
  }
  expect(await page.evaluate(() => window.inputBlurs)).toBe(0);
  expect(await sent(page)).toEqual(['\x1b', '\t', '\x7f', '\x1b[D', '\x1b[C', '\x1b[H', '\x1b[F']);
});

test('direct paste keeps open keyboard, then IME backspace sends exactly one delete', async ({ page }) => {
  await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { value: { readText: () => Promise.resolve('echo abc') }, configurable: true }));
  await page.locator('#pasteBtn').tap();
  await expect(page.locator('.xterm-helper-textarea')).toBeFocused();
  const cancelled = await page.evaluate(() => !window.testTerm.textarea.dispatchEvent(new InputEvent('beforeinput', {
    inputType: 'deleteContentBackward', bubbles: true, cancelable: true
  })));
  expect(cancelled).toBe(true);
  await page.waitForTimeout(30);
  expect(await sent(page)).toEqual(['echo abc', '\x7f']);
  expect(await page.evaluate(() => window.inputBlurs)).toBe(0);
});

test('IME 229 deletion does not re-send cached helper text or duplicate backspace', async ({ page }) => {
  await page.evaluate(() => {
    const ta = window.testTerm.textarea;
    ta.value = 'cached command';
    ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Unidentified', keyCode: 229, bubbles: true, cancelable: true }));
    ta.dispatchEvent(new InputEvent('beforeinput', { inputType: 'deleteContentBackward', bubbles: true, cancelable: true }));
    ta.dispatchEvent(new KeyboardEvent('keyup', { key: 'Unidentified', keyCode: 229, bubbles: true }));
  });
  await page.waitForTimeout(40);
  expect(await sent(page)).toEqual(['\x7f']);
  await expect(page.locator('.xterm-helper-textarea')).toHaveValue('');
});

test('hardware backspace is not doubled and composing IME deletion is left alone', async ({ page }) => {
  await page.keyboard.press('Backspace');
  expect(await sent(page)).toEqual(['\x7f']);
  const cancelled = await page.evaluate(() => {
    const ta = window.testTerm.textarea;
    ta.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
    return !ta.dispatchEvent(new InputEvent('beforeinput', {
      inputType: 'deleteContentBackward', isComposing: true, bubbles: true, cancelable: true
    }));
  });
  expect(cancelled).toBe(false);
  expect(await sent(page)).toEqual(['\x7f']);
});

test('cursor trackpad sends relative arrows with no keyboard toggle or local screen edits', async ({ page }) => {
  await page.locator('#cursorModeBtn').tap();
  await expect(page.locator('#cursorModeBtn')).toHaveAttribute('aria-pressed', 'true');
  const before = await page.evaluate(() => window.testTerm.buffer.active.getLine(0).translateToString(true));
  await swipe(page, -3.5);
  await swipe(page, 2.5);
  expect(await sent(page)).toEqual(['\x1b[D'.repeat(3), '\x1b[C'.repeat(2)]);
  expect(await page.evaluate(() => window.testTerm.buffer.active.getLine(0).translateToString(true))).toBe(before);
  await expect(page.locator('.xterm-helper-textarea')).toBeFocused();
  expect(await page.evaluate(() => window.inputBlurs)).toBe(0);
});

test('cursor mode respects application cursor keys, vertical scroll, and long-press selection', async ({ page }) => {
  await page.evaluate(() => new Promise(resolve => window.testTerm.write('\x1b[?1h', resolve)));
  await page.locator('#cursorModeBtn').tap();
  await swipe(page, -2.5);
  expect(await sent(page)).toEqual(['\x1bOD'.repeat(2)]);
  await swipe(page, -2, true);
  expect(await sent(page)).toEqual(['\x1bOD'.repeat(2)]);
  await expect(page.locator('.xterm-helper-textarea')).toBeFocused();
  await swipe(page, 3.5, false, true);
  await expect(page.locator('#selectionStart')).toBeVisible();
  expect(await sent(page)).toEqual(['\x1bOD'.repeat(2)]);
});

test('extra keys do not open the keyboard when it was hidden', async ({ page }) => {
  await page.evaluate(() => window.testTerm.blur());
  await page.locator('[data-key="tab"]').tap();
  await page.locator('#cursorModeBtn').tap();
  await swipe(page, -1.5);
  await expect(page.locator('.xterm-helper-textarea')).not.toBeFocused();
});

test('visual viewport changes fit the terminal once after the keyboard animation', async ({ page }) => {
  await page.waitForTimeout(180);
  await page.evaluate(() => {
    window.gridResizes = [];
    window.testTerm.onResize(size => window.gridResizes.push(size));
    Object.defineProperty(window.visualViewport, 'height', { configurable: true, get: () => window.fakeHeight });
    for (const height of [700, 620, 540, 480]) {
      window.fakeHeight = height;
      window.visualViewport.dispatchEvent(new Event('resize'));
    }
  });
  await expect.poll(() => page.evaluate(() => window.gridResizes.length)).toBe(1);
  const box = await page.locator('#app').boundingBox();
  expect(box.height).toBe(480);
  const keys = await page.locator('#keys').boundingBox();
  expect(keys.y + keys.height).toBeLessThanOrEqual(480);
  await expect(page.locator('.xterm-helper-textarea')).toBeFocused();
});


test('a burst of IME 229 deletes cannot race the helper cleanup', async ({ page }) => {
  await page.evaluate(() => {
    const ta = window.testTerm.textarea;
    ta.value = 'cached';
    for (let i = 0; i < 3; i++) {
      ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Unidentified', keyCode: 229, bubbles: true }));
      ta.dispatchEvent(new InputEvent('beforeinput', { inputType: 'deleteContentBackward', bubbles: true, cancelable: true }));
      ta.dispatchEvent(new KeyboardEvent('keyup', { key: 'Unidentified', keyCode: 229, bubbles: true }));
    }
  });
  await page.waitForTimeout(40);
  expect(await sent(page)).toEqual(['\x7f', '\x7f', '\x7f']);
  await expect(page.locator('.xterm-helper-textarea')).toHaveValue('');
});

test('deletion cleanup cannot erase a newer composing value', async ({ page }) => {
  await page.evaluate(() => {
    const ta = window.testTerm.textarea;
    ta.dispatchEvent(new InputEvent('beforeinput', { inputType: 'deleteContentBackward', bubbles: true, cancelable: true }));
    ta.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
    ta.value = 'あ';
    ta.dispatchEvent(new CompositionEvent('compositionupdate', { data: 'あ', bubbles: true }));
  });
  await page.waitForTimeout(40);
  await expect(page.locator('.xterm-helper-textarea')).toHaveValue('あ');
  expect(await sent(page)).toEqual(['\x7f']);
});
