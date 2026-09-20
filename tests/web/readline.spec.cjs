const { test, expect } = require('@playwright/test');
const { spawn, spawnSync } = require('node:child_process');
const { createInterface } = require('node:readline');
const path = require('node:path');
const { setupTerminal } = require('./terminal-fixture.cjs');

const havePty = process.platform !== 'win32' &&
  spawnSync('python3', ['-c', 'import pty, termios']).status === 0 &&
  spawnSync('bash', ['--version']).status === 0;

test('real bash: wrapped paste, IME backspace, keyboard resize, and cursor edit stay in sync', async ({ page }) => {
  test.skip(!havePty, 'Requires POSIX, python3 and bash for the real PTY integration test');
  await setupTerminal(page);
  const size = await page.evaluate(() => {
    window.testTerm.reset();
    window.testTerm.focus();
    return [window.testTerm.rows, window.testTerm.cols];
  });
  const helper = spawn('python3', [path.join(__dirname, 'pty-helper.py'), ...size.map(String)], { stdio: ['pipe', 'pipe', 'inherit'] });
  const exited = new Promise(resolve => helper.once('exit', resolve));
  let output = '', writes = Promise.resolve(), renderError;
  const lines = createInterface({ input: helper.stdout });
  lines.on('line', line => {
    const text = Buffer.from(JSON.parse(line), 'base64').toString('utf8');
    output += text;
    writes = writes.then(() => page.evaluate(text => new Promise(resolve => window.testTerm.write(text, resolve)), text))
      .catch(error => { renderError = error; });
  });
  const command = data => helper.stdin.write(JSON.stringify(data) + '\n');
  try {
    await page.exposeFunction('ptyInput', data => command(['input', Buffer.from(data).toString('base64')]));
    await page.exposeFunction('ptySize', (rows, cols) => command(['resize', rows, cols]));
    await page.evaluate(() => {
      for (const socket of window.testSockets) {
        socket.readyState = WebSocket.OPEN;
        socket.send = data => {
          if (new URL(socket.url).pathname === '/ctl') {
            const size = JSON.parse(data);
            if (size.type === 'resize') window.ptySize(size.rows, size.cols);
          } else window.ptyInput(data);
        };
      }
    });
    const screen = () => page.evaluate(() => {
      const b = window.testTerm.buffer.active;
      return Array.from({ length: b.length }, (_, i) => b.getLine(i).translateToString(true)).join('');
    });
    await expect.poll(screen).toBe('pty> ');
    const payload = 'abcdefghij'.repeat(12);
    const prefix = "printf '\\nRESULT:%s:END\\n' '";
    await page.evaluate(text => Object.defineProperty(navigator, 'clipboard', {
      configurable: true, value: { readText: () => Promise.resolve(text) }
    }), prefix + payload + "X'");
    await page.locator('#pasteBtn').tap();
    await expect.poll(screen).toBe('pty> ' + prefix + payload + "X'");
    for (let i = 0; i < 2; i++) {
      await page.evaluate(() => {
        const ta = window.testTerm.textarea;
        ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Unidentified', keyCode: 229, bubbles: true }));
        ta.dispatchEvent(new InputEvent('beforeinput', { inputType: 'deleteContentBackward', bubbles: true, cancelable: true }));
        ta.dispatchEvent(new KeyboardEvent('keyup', { key: 'Unidentified', keyCode: 229, bubbles: true }));
      });
      await page.waitForTimeout(20);
    }
    await page.keyboard.type("Z'");
    await expect.poll(screen).toBe('pty> ' + prefix + payload + "Z'");
    await page.setViewportSize({ width: 412, height: 520 });
    await expect.poll(() => page.evaluate(() => window.testTerm.rows)).toBeLessThan(size[0]);
    await expect.poll(screen).toBe('pty> ' + prefix + payload + "Z'");
    await page.locator('#cursorModeBtn').tap();
    const points = await page.evaluate(() => {
      const t = window.testTerm, r = document.querySelector('.xterm-screen').getBoundingClientRect();
      return { x: r.left + 200, y: r.top + 35, distance: Math.max(8, r.width / t.cols) * 3.5 };
    });
    const client = await page.context().newCDPSession(page);
    await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: points.x, y: points.y, id: 1 }] });
    await client.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: points.x - points.distance, y: points.y, id: 1 }] });
    await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await client.detach();
    await page.keyboard.press('Backspace');
    const edited = payload.slice(0, -2) + 'jZ';
    await expect.poll(screen).toBe('pty> ' + prefix + edited + "'");
    await page.locator('[data-key="end"]').tap();
    await page.keyboard.press('Enter');
    await expect.poll(() => output).toContain('\r\nRESULT:' + edited + ':END\r\n');
    await expect.poll(screen).toContain('RESULT:' + edited + ':END');
    await writes;
    expect(renderError).toBeUndefined();
  } finally {
    helper.stdin.end();
    await exited;
    lines.close();
    await writes;
  }
});
