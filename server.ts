import express from 'express';
import { execFile } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { extractOtpCode } from './src/utils/mailParser.ts';

const execFileAsync = promisify(execFile);
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = Number(process.env.PORT || 3000);
const DONGVAN_OAUTH_ENDPOINT = 'https://tools.dongvanfb.net/api/get_code_oauth2';
const DONGVAN_GRAPH_ENDPOINT = 'https://tools.dongvanfb.net/api/graph_code';

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
      refresh_token?: string;
      client_id?: string;
      mode?: string;
      type?: string;
    };

    const email = typeof body?.email === 'string' ? body.email.trim() : '';
    const refresh_token = typeof body?.refresh_token === 'string' ? body.refresh_token.trim() : '';
    const client_id = typeof body?.client_id === 'string' ? body.client_id.trim() : '';
    const mode =
      body?.mode === 'graph' || body?.mode === 'oauth' ? body.mode : 'auto';
    const type = typeof body?.type === 'string' && body.type.trim() ? body.type.trim() : 'facebook';

    if (!email || !refresh_token || !client_id) {
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

    type DongvanResult = {
      ok: boolean;
      httpStatus: number;
      email: string;
      password?: string;
      code?: string;
      content?: string;
      date?: string;
      error?: string;
    };

    async function callDongvan(endpoint: string): Promise<DongvanResult> {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 20000);
      try {
        const upstream = await fetch(endpoint, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json',
          },
          body: JSON.stringify({
            email,
            refresh_token,
            client_id,
            type,
          }),
          signal: controller.signal,
        });

        const rawText = await upstream.text();
        let parsed: {
          email?: string;
          password?: string;
          status?: boolean;
          code?: string | null;
          content?: string | null;
          date?: string | null;
          error?: string;
          message?: string;
        };

        try {
          parsed = JSON.parse(rawText);
        } catch {
          return {
            ok: false,
            httpStatus: upstream.ok ? 502 : upstream.status,
            email,
            error: rawText || `Upstream API returned HTTP ${upstream.status}`,
          };
        }

        const content = (parsed.content ?? '').trim();
        if (!upstream.ok || parsed.status === false) {
          return {
            ok: false,
            httpStatus: upstream.ok ? 200 : upstream.status,
            email: parsed.email || email,
            error:
              parsed.error ||
              parsed.message ||
              content ||
              `No matching ${type} verification mail found yet.`,
          };
        }

        const code = extractOtpCode(parsed.code, content);
        return {
          ok: true,
          httpStatus: 200,
          email: parsed.email || email,
          password: parsed.password,
          code,
          content,
          date: (parsed.date ?? '').trim() || undefined,
        };
      } catch (err) {
        const message =
          err instanceof Error && err.name === 'AbortError'
            ? 'Request to dongvanfb API timed out after 20 seconds.'
            : err instanceof Error
              ? err.message
              : 'Failed to reach Hotmail OAuth2/Graph API.';
        return {
          ok: false,
          httpStatus: 502,
          email,
          error: message,
        };
      } finally {
        clearTimeout(timeoutId);
      }
    }

    if (mode === 'graph') {
      const r = await callDongvan(DONGVAN_GRAPH_ENDPOINT);
      if (!r.ok) {
        res.status(r.httpStatus).json({ status: false, email: r.email, error: r.error });
        return;
      }
      res.json({
        email: r.email,
        password: r.password,
        status: true,
        code: r.code,
        content: r.content,
        date: r.date,
      });
      return;
    }

    if (mode === 'oauth') {
      const r = await callDongvan(DONGVAN_OAUTH_ENDPOINT);
      if (!r.ok) {
        res.status(r.httpStatus).json({ status: false, email: r.email, error: r.error });
        return;
      }
      res.json({
        email: r.email,
        password: r.password,
        status: true,
        code: r.code,
        content: r.content,
        date: r.date,
      });
      return;
    }

    // Auto mode: try OAuth2 first, then automatically fall back to Graph API
    const oauthRes = await callDongvan(DONGVAN_OAUTH_ENDPOINT);
    if (oauthRes.ok) {
      res.json({
        email: oauthRes.email,
        password: oauthRes.password,
        status: true,
        code: oauthRes.code,
        content: oauthRes.content,
        date: oauthRes.date,
      });
      return;
    }

    const graphRes = await callDongvan(DONGVAN_GRAPH_ENDPOINT);
    if (graphRes.ok) {
      res.json({
        email: graphRes.email,
        password: graphRes.password,
        status: true,
        code: graphRes.code,
        content: graphRes.content,
        date: graphRes.date,
      });
      return;
    }

    const combinedError =
      oauthRes.error === graphRes.error
        ? oauthRes.error
        : `OAuth2: ${oauthRes.error} | Graph: ${graphRes.error}`;

    res.status(graphRes.httpStatus === 200 ? 200 : graphRes.httpStatus).json({
      status: false,
      email,
      error: combinedError,
    });
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
