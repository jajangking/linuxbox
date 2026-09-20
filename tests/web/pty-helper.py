#!/usr/bin/env python3
"""Test-only JSON-line bridge to a real bash PTY (no server/network dependencies)."""
import base64
import fcntl
import json
import os
import pty
import select
import signal
import struct
import sys
import termios

rows, cols = map(int, sys.argv[1:3])
pid, master = pty.fork()
if pid == 0:
    fcntl.ioctl(0, termios.TIOCSWINSZ, struct.pack('HHHH', rows, cols, 0, 0))
    os.environ.update(TERM='xterm-256color', PS1='pty> ', PROMPT_COMMAND='',
                      HISTFILE='/dev/null', INPUTRC='/dev/null', LC_ALL='C.UTF-8')
    os.execvp('bash', ['bash', '--noprofile', '--norc', '-i'])

pending = b''
try:
    while True:
        ready, _, _ = select.select([master, 0], [], [])
        if master in ready:
            try:
                output = os.read(master, 65536)
            except OSError:
                break
            if not output:
                break
            print(json.dumps(base64.b64encode(output).decode()), flush=True)
        if 0 in ready:
            chunk = os.read(0, 65536)
            if not chunk:
                break
            pending += chunk
            while b'\n' in pending:
                line, pending = pending.split(b'\n', 1)
                command = json.loads(line)
                if command[0] == 'input':
                    data = base64.b64decode(command[1])
                    while data:
                        data = data[os.write(master, data):]
                elif command[0] == 'resize':
                    fcntl.ioctl(master, termios.TIOCSWINSZ,
                                struct.pack('HHHH', command[1], command[2], 0, 0))
finally:
    os.close(master)
    try:
        os.kill(pid, signal.SIGHUP)
    except ProcessLookupError:
        pass
    os.waitpid(pid, 0)
