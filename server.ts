import express from 'express';
import { execFile } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import {
  extractOtpCode,
  pickBestSmail1sMessage,
  stripHtmlMessage,
  type Smail1sRawMessage,
} from './src/utils/mailParser.ts';

const execFileAsync = promisify(execFile);
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = Number(process.env.PORT || 3000);
const SMAIL1S_ENDPOINT = 'https://smail1s.com/get_messages';

const SAMPLE_CODES_BY_TYPE: Record<string, { code: string; content: string }> = {
  facebook: {
    code: '37589',
    content: '37589 is your Facebook confirmation code. For your security, do not share this code.',
  },
  instagram: {
    code: '648291',
    content: '648291 is your Instagram verification code. Don\'t share it with anyone.',
  },
  twitter: {
    code: '910422',
    content: 'Your X confirmation code is 910422.',
  },
  google: {
    code: '482910',
    content: 'G-482910 is your Google verification code.',
  },
  tiktok: {
    code: '731045',
    content: '[TikTok] 731045 is your verification code, valid for 5 minutes.',
  },
  all: {
    code: '37589',
    content: '37589 is your Facebook confirmation code. For your security, do not share this code.',
  },
};

function formatDemoDate(date: Date): string {
  const hh = String(date.getHours()).padStart(2, '0');
  const mm = String(date.getMinutes()).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');
  const mo = String(date.getMonth() + 1).padStart(2, '0');
  const yyyy = date.getFullYear();
  return `${hh}:${mm} - ${dd}/${mo}/${yyyy}`;
}

async function startServer() {
  const app = express();
  app.use(express.json({ limit: '1mb' }));

  app.get('/api/health', (_req, res) => {
    res.json({ ok: true });
  });

  app.get('/api/download-android-project', async (_req, res) => {
    const gradlewPath = path.resolve(__dirname, 'gradlew');
    const appDirPath = path.resolve(__dirname, 'app');
    if (!fs.existsSync(gradlewPath) || !fs.existsSync(appDirPath)) {
      res.status(404).json({ error: 'Native Android project files not found.' });
      return;
    }
    const zipPath = path.join(os.tmpdir(), 'hotmail-floating-otp-reader-android.zip');
    try {
      await execFileAsync('python3', [
        '-c',
        `import os, stat, sys, zipfile
root_dir = sys.argv[1]
dst = sys.argv[2]
include_dirs = ['app', 'gradle']
include_files = ['gradlew', 'gradlew.bat', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'README.md']

def add_file(zf, full, rel):
    arcname = os.path.join('HotmailFloatingOtpReader', rel)
    st = os.stat(full)
    mode = 0o755 if rel == 'gradlew' else stat.S_IMODE(st.st_mode)
    zi = zipfile.ZipInfo(arcname)
    zi.external_attr = (mode & 0xFFFF) << 16
    zi.compress_type = zipfile.ZIP_DEFLATED
    with open(full, 'rb') as fp:
        zf.writestr(zi, fp.read())

with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zf:
    for fname in include_files:
        full = os.path.join(root_dir, fname)
        if os.path.isfile(full):
            add_file(zf, full, fname)
    for dname in include_dirs:
        dpath = os.path.join(root_dir, dname)
        if not os.path.isdir(dpath):
            continue
        for root, dirs, files in os.walk(dpath):
            dirs[:] = sorted([d for d in dirs if d not in ('.gradle', 'build', '.idea')])
            for f in sorted(files):
                full = os.path.join(root, f)
                rel = os.path.relpath(full, root_dir)
                add_file(zf, full, rel)
`,
        __dirname,
        zipPath,
      ]);
      res.download(zipPath, 'HotmailFloatingOtpReader-Android.zip');
    } catch (err) {
      res.status(500).json({
        error: err instanceof Error ? err.message : 'Failed to package Android project.',
      });
    }
  });

  app.post('/api/get-code', async (req, res) => {
    const body = req.body as {
      email?: string;
      password?: string;
      refresh_token?: string;
      client_id?: string;
      client_secret?: string;
      mode?: string;
      type?: string;
    };

    const email = typeof body?.email === 'string' ? body.email.trim() : '';
    const password = typeof body?.password === 'string' ? body.password.trim() : '';
    const refresh_token = typeof body?.refresh_token === 'string' ? body.refresh_token.trim() : '';
    const client_id = typeof body?.client_id === 'string' ? body.client_id.trim() : '';
    const client_secret = typeof body?.client_secret === 'string' ? body.client_secret.trim() : '';
    const mode =
      body?.mode === 'graph' || body?.mode === 'roundcube' ? body.mode : 'oauth';
    const type = typeof body?.type === 'string' && body.type.trim() ? body.type.trim() : 'facebook';

    if (mode === 'roundcube') {
      if (!email || !password) {
        res.status(400).json({
          status: false,
          email,
          error: 'Missing required fields: email and password are required for Roundcube mode.',
        });
        return;
      }
    } else if (!email || !refresh_token || !client_id) {
      res.status(400).json({
        status: false,
        email,
        error: 'Missing required fields: email, refresh_token, and client_id are required.',
      });
      return;
    }

    // Deterministic sample response only when explicitly using the built-in demo credentials
    if (refresh_token.startsWith('DEMO_REFRESH_TOKEN')) {
      const sample = SAMPLE_CODES_BY_TYPE[type] ?? {
        code: '592841',
        content: `592841 is your ${type} verification code`,
      };
      const code =
        email === 'demo.fb2@hotmail.com'
          ? '84219'
          : email === 'demo.fb3@outlook.com'
            ? '90412'
            : sample.code;
      const content =
        code === sample.code
          ? sample.content
          : `${code} is your ${type} confirmation code. For your security, do not share this code.`;

      res.json({
        email,
        password: 'demo-password',
        status: true,
        code,
        content,
        date: formatDemoDate(new Date()),
      });
      return;
    }

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 20000);

    const dataLine =
      mode === 'roundcube'
        ? `${email}|${password}`
        : mode === 'graph' && client_secret
          ? `${email}|${refresh_token}|${client_id}|${client_secret}`
          : `${email}|${refresh_token}|${client_id}`;

    try {
      const upstream = await fetch(SMAIL1S_ENDPOINT, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
        },
        body: JSON.stringify({
          mode,
          data: dataLine,
        }),
        signal: controller.signal,
      });

      const rawText = await upstream.text();
      let parsed: {
        data?: Array<{
          email?: string | null;
          error?: string | null;
          mailSource?: string;
          messages?: Smail1sRawMessage[];
        }>;
        error?: string;
        message?: string;
      };

      try {
        parsed = JSON.parse(rawText);
      } catch {
        res.status(upstream.ok ? 502 : upstream.status).json({
          status: false,
          email,
          error: rawText || `Upstream API returned HTTP ${upstream.status}`,
        });
        return;
      }

      const firstEntry =
        Array.isArray(parsed.data) && parsed.data.length > 0
          ? (parsed.data.find((entry) => entry.email?.toLowerCase() === email.toLowerCase()) ??
            parsed.data[0])
          : undefined;

      if (!upstream.ok || firstEntry?.error) {
        res.status(upstream.ok ? 200 : upstream.status).json({
          status: false,
          email,
          error:
            firstEntry?.error ||
            parsed.error ||
            parsed.message ||
            `Upstream API returned HTTP ${upstream.status}`,
        });
        return;
      }

      const messages = Array.isArray(firstEntry?.messages) ? firstEntry.messages : [];
      const best = pickBestSmail1sMessage(messages, type);
      if (!best) {
        res.json({
          email,
          status: false,
          error: `No matching ${type} verification mail found yet.`,
        });
        return;
      }

      const plainBody = stripHtmlMessage(best.message);
      const subject = (best.subject ?? '').trim();
      const content = plainBody || subject;
      const code = extractOtpCode(best.code, `${subject} ${plainBody}`);

      res.json({
        email: firstEntry?.email || email,
        status: true,
        code,
        content,
        date: (best.date ?? '').trim() || undefined,
        uid: (best.uid ?? '').trim() || undefined,
        from: (best.from ?? '').trim() || undefined,
        subject: subject || undefined,
      });
    } catch (err) {
      const message =
        err instanceof Error && err.name === 'AbortError'
          ? 'Request to smail1s API timed out after 20 seconds.'
          : err instanceof Error
            ? err.message
            : 'Failed to reach Hotmail OAuth2 API.';

      res.status(502).json({
        status: false,
        email,
        error: message,
      });
    } finally {
      clearTimeout(timeoutId);
    }
  });

  if (process.env.NODE_ENV !== 'production') {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.resolve(__dirname, 'dist');
    app.use(express.static(distPath));
    app.get('*', (_req, res) => {
      res.sendFile(path.join(distPath, 'index.html'));
    });
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`Server listening on http://0.0.0.0:${PORT}`);
  });
}

startServer();
