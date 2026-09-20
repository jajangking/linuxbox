const { test, expect } = require('@playwright/test');
const { setupTerminal } = require('./terminal-fixture.cjs');

const history = {
  one: 'ONE_saved_output\r\nuser@ubuntu:~$ ',
  two: 'TWO_other_work\r\nuser@ubuntu:~$ '
};

// Real xterm, controlled session API and socket event ordering. In particular,
// killing a PTY leaves its WebSocket OPEN, just like the current Java server.
async function serverFor(page) {
  const state = {
    items: [{ id: 'one', name: 'Satu', alive: true }, { id: 'two', name: 'Dua', alive: true }],
    created: 0, killed: [], failList: false, holdList: false, held: null
  };
  await page.route('http://linuxbox.test/api/sessions**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/kill')) {
      const id = path.split('/')[3];
      state.killed.push(id);
      state.items = state.items.filter(s => s.id !== id);
      return route.fulfill({ json: { ok: true } });
    }
    if (route.request().method() === 'POST') {
      const id = 'new' + (++state.created);
      state.items.push({ id, name: 'Baru', alive: true });
      return route.fulfill({ json: { id } });
    }
    if (state.holdList) {
      state.holdList = false;
      const snapshot = state.items.slice();
      state.held = () => route.fulfill({ json: { sessions: snapshot } });
      return;
    }
    if (state.failList) {
      state.failList = false;
      return route.fulfill({ status: 503, json: { error: 'temporary failure' } });
    }
    return route.fulfill({ json: { sessions: state.items } });
  });
  return state;
}
async function screen(page) {
  return page.evaluate(() => {
    const buffer = window.testTerm.buffer.active;
    return Array.from({ length: buffer.length }, (_, i) => buffer.getLine(i).translateToString(true)).join('\n');
  });
}
async function openSession(page, id, text = history[id] || 'NEW_prompt$ ') {
  await expect.poll(() => page.evaluate(id => window.testSockets.filter(s => {
    const url = new URL(s.url);
    return url.pathname === '/ws' && url.searchParams.get('session') === id && s.readyState === 0;
  }).length, id)).toBe(1);
  const index = await page.evaluate(({ id, text }) => new Promise(resolve => {
    const socket = window.testSockets.find(s => {
      const url = new URL(s.url);
      return url.pathname === '/ws' && url.searchParams.get('session') === id && s.readyState === 0;
    });
    socket.readyState = WebSocket.OPEN;
    socket.onopen();
    for (const ctl of window.testSockets) {
      if (new URL(ctl.url).pathname === '/ctl' && ctl.readyState === 0) {
        ctl.readyState = WebSocket.OPEN;
        ctl.onopen();
      }
    }
    // Match binary scrollback replay emitted by WebTerminalServer.doWebSocket.
    socket.onmessage({ data: new TextEncoder().encode(text).buffer });
    window.testTerm.write('', () => resolve(window.testSockets.indexOf(socket)));
  }), { id, text });
  await expect(page.locator('#state')).toHaveText('terhubung');
  return index;
}
async function active(page, name) {
  await expect(page.locator('.tab.active')).toContainText(name);
}
async function closeTab(page, name) {
  await page.locator('.tab').filter({ hasText: name }).locator('.close').tap();
}

test.beforeEach(async ({ page }) => {
  await setupTerminal(page);
  await page.evaluate(() => window.testTerm.reset());
});

test('closing active second session reconnects the first and replays its history', async ({ page }) => {
  const server = await serverFor(page);
  await openSession(page, 'one');
  await page.locator('.tab').filter({ hasText: 'Dua' }).tap();
  const oldSecond = await openSession(page, 'two');
  await expect.poll(() => screen(page)).toContain('TWO_other_work');

  await closeTab(page, 'Dua');
  await active(page, 'Satu');
  const first = await openSession(page, 'one');
  await expect.poll(() => screen(page)).toContain('ONE_saved_output');
  expect(await screen(page)).not.toContain('TWO_other_work');
  expect(await page.evaluate(i => window.testSockets[i].readyState, oldSecond)).toBe(3);
  await page.evaluate(() => window.testTerm.paste('pwd'));
  expect(await page.evaluate(i => window.testSockets[i].sent, first)).toEqual(['pwd']);
  expect(await page.evaluate(i => window.testSockets[i].sent, oldSecond)).toEqual([]);
  expect(server.killed).toEqual(['two']);
  expect(server.created).toBe(0);
});

test('closing an inactive second session does not clear or reconnect the first', async ({ page }) => {
  await serverFor(page);
  const first = await openSession(page, 'one');
  const before = await screen(page);
  const sockets = await page.evaluate(() => window.testSockets.length);
  await closeTab(page, 'Dua');
  await expect(page.locator('.tab:not(.add)')).toHaveCount(1);
  await active(page, 'Satu');
  expect(await screen(page)).toBe(before);
  expect(await page.evaluate(() => window.testSockets.length)).toBe(sockets);
  expect(await page.evaluate(i => window.testSockets[i].readyState, first)).toBe(1);
});

test('refresh discovering a removed active session also closes old sockets before switching', async ({ page }) => {
  const server = await serverFor(page);
  await openSession(page, 'one');
  await page.locator('.tab').filter({ hasText: 'Dua' }).tap();
  await expect.poll(() => page.evaluate(() => window.testSockets.some(s =>
    new URL(s.url).pathname === '/ws' && new URL(s.url).searchParams.get('session') === 'two' && s.readyState === 0))).toBe(true);
  server.items = server.items.filter(s => s.id !== 'two');
  // Opening this socket triggers a refresh which discovers the other client's deletion.
  await page.evaluate(() => {
    const socket = window.testSockets.find(s => new URL(s.url).pathname === '/ws' && s.readyState === 0);
    socket.readyState = WebSocket.OPEN;
    socket.onopen();
  });
  await active(page, 'Satu');
  await openSession(page, 'one');
  await expect.poll(() => screen(page)).toContain('ONE_saved_output');
  expect(await page.evaluate(() => window.testSockets.filter(s => new URL(s.url).searchParams.get('session') === 'two').every(s => s.readyState === 3))).toBe(true);
});

test('older list response cannot resurrect the closed tab or replace active session', async ({ page }) => {
  const server = await serverFor(page);
  await openSession(page, 'one');
  await page.locator('.tab').filter({ hasText: 'Dua' }).tap();
  server.holdList = true;
  await openSession(page, 'two');
  await expect.poll(() => !!server.held).toBe(true);
  await closeTab(page, 'Dua');
  await active(page, 'Satu');
  await openSession(page, 'one');
  await server.held();
  // Flush delivery of the late HTTP response before examining tabs.
  await page.waitForTimeout(100);
  await expect(page.locator('.tab:not(.add)')).toHaveCount(1);
  await active(page, 'Satu');
  expect(await screen(page)).toContain('ONE_saved_output');
  expect(server.created).toBe(0);
});

test('queued output is drained before reset, and late callbacks cannot corrupt the next session', async ({ page }) => {
  await serverFor(page);
  const first = await openSession(page, 'one');
  await page.evaluate(index => {
    const socket = window.testSockets[index];
    socket.onmessage({ data: 'QUEUED_OLD\r\n'.repeat(1000) });
    Array.from(document.querySelectorAll('.tab')).find(t => t.textContent.includes('Dua')).click();
    socket.onmessage({ data: 'LATE_OLD' });
    socket.onclose();
    socket.onerror();
    socket.onopen();
  }, first);
  await openSession(page, 'two');
  const contents = await screen(page);
  expect(contents).toContain('TWO_other_work');
  expect(contents).not.toContain('QUEUED_OLD');
  expect(contents).not.toContain('LATE_OLD');
  await expect(page.locator('#overlay')).not.toHaveClass('show');
  await expect(page.locator('#state')).toHaveText('terhubung');
});

test('old reconnect/control timers are cancelled when switching sessions', async ({ page }) => {
  await serverFor(page);
  const first = await openSession(page, 'one');
  await page.clock.install();
  const oldCtl = await page.evaluate(index => {
    const socket = window.testSockets[index];
    socket.readyState = 3;
    socket.onclose();
    const ctl = window.testSockets.find(s => new URL(s.url).pathname === '/ctl');
    ctl.readyState = 3;
    ctl.onclose();
    return window.testSockets.indexOf(ctl);
  }, first);
  await page.clock.runFor(1000); // starts reconnect + its 8s timeout
  // Overlay from the dropped connection covers tabs; direct click models a switch
  // initiated before the close notification or through session reconciliation.
  await page.locator('.tab').filter({ hasText: 'Dua' }).evaluate(el => el.click());
  await page.clock.runFor(20); // flush xterm write/reset barrier
  await openSession(page, 'two');
  const count = await page.evaluate(() => window.testSockets.length);
  const sizes = await page.evaluate(() => window.testSockets.flatMap(s => s.sent));
  await page.evaluate(({ first, oldCtl }) => {
    window.testSockets[first].onclose();
    window.testSockets[first].onerror();
    window.testSockets[oldCtl].onmessage({ data: 'need-size' });
    window.testSockets[oldCtl].onclose();
  }, { first, oldCtl });
  await page.clock.runFor(9000);
  expect(await page.evaluate(() => window.testSockets.length)).toBe(count);
  expect(await page.evaluate(() => window.testSockets.flatMap(s => s.sent))).toEqual(sizes);
  await expect(page.locator('#overlay')).not.toHaveClass('show');
  await expect(page.locator('#state')).toHaveText('terhubung');
});

test('closing the last tab creates exactly one replacement session', async ({ page }) => {
  const server = await serverFor(page);
  await openSession(page, 'one');
  await closeTab(page, 'Dua');
  await expect(page.locator('.tab:not(.add)')).toHaveCount(1);
  await closeTab(page, 'Satu');
  await active(page, 'Baru');
  await openSession(page, 'new1');
  expect(await screen(page)).toContain('NEW_prompt');
  expect(await screen(page)).not.toContain('ONE_saved_output');
  expect(server.created).toBe(1);
  await expect(page.locator('.tab:not(.add)')).toHaveCount(1);
});

test('failed list refresh after close is not interpreted as zero sessions', async ({ page }) => {
  const server = await serverFor(page);
  await openSession(page, 'one');
  await closeTab(page, 'Dua');
  await expect(page.locator('.tab:not(.add)')).toHaveCount(1);
  // Only the successful close is applied locally; a failed GET must not POST a new shell.
  server.failList = true;
  await closeTab(page, 'Satu');
  await expect(page.locator('#overlay')).toHaveClass('show');
  expect(server.created).toBe(0);
  expect(await page.evaluate(() => window.testSockets.every(s => s.readyState === 3))).toBe(true);
});


test('failure to refresh the tab list does not block reconnection to the surviving session', async ({ page }) => {
  const server = await serverFor(page);
  await openSession(page, 'one');
  await page.locator('.tab').filter({ hasText: 'Dua' }).tap();
  await openSession(page, 'two');
  server.failList = true;
  await closeTab(page, 'Dua');
  await active(page, 'Satu');
  await openSession(page, 'one');
  await expect.poll(() => screen(page)).toContain('ONE_saved_output');
  expect(server.created).toBe(0);
  await expect(page.locator('#overlay')).not.toHaveClass('show');
});
