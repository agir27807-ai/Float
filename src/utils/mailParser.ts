import { MAIL_SERVICE_TYPES, type FloatingPosition, type MailServiceType } from '../types/mail';

export interface ParsedMailbox {
  email: string;
  password?: string;
  refresh_token: string;
  client_id: string;
}

export type ParseResult =
  | { ok: true; value: ParsedMailbox }
  | { ok: false; error: string };

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function isValidEmail(email: string): boolean {
  return EMAIL_PATTERN.test(email.trim());
}

export function isValidServiceType(value: string): value is MailServiceType {
  return (MAIL_SERVICE_TYPES as readonly string[]).includes(value);
}

export function validateMailboxFields(input: {
  email: string;
  refresh_token: string;
  client_id: string;
  password?: string;
}): ParseResult {
  const email = input.email.trim();
  const refresh_token = input.refresh_token.trim();
  const client_id = input.client_id.trim();
  const password = input.password?.trim();

  if (!email) {
    return { ok: false, error: 'Please enter a Hotmail or Outlook email address.' };
  }
  if (!isValidEmail(email)) {
    return { ok: false, error: 'Email address format looks invalid.' };
  }
  if (!refresh_token) {
    return { ok: false, error: 'Refresh token is required for OAuth2 mail reading.' };
  }
  if (!client_id) {
    return { ok: false, error: 'Client ID is required for OAuth2 mail reading.' };
  }

  return {
    ok: true,
    value: {
      email,
      ...(password ? { password } : {}),
      refresh_token,
      client_id,
    },
  };
}

export function parseMailboxInput(raw: string): ParseResult {
  const trimmed = raw.trim();
  if (!trimmed) {
    return {
      ok: false,
      error: 'Paste your mailbox credentials (email|password|refresh_token|client_id) or enter fields manually.',
    };
  }

  if (trimmed.startsWith('{')) {
    try {
      const parsed = JSON.parse(trimmed) as Record<string, unknown>;
      return validateMailboxFields({
        email: typeof parsed.email === 'string' ? parsed.email : '',
        refresh_token: typeof parsed.refresh_token === 'string' ? parsed.refresh_token : '',
        client_id: typeof parsed.client_id === 'string' ? parsed.client_id : '',
        password: typeof parsed.password === 'string' ? parsed.password : undefined,
      });
    } catch {
      return { ok: false, error: 'Invalid JSON format in mail box.' };
    }
  }

  const firstLine = trimmed.split(/\r?\n/).find((line) => line.trim().length > 0)?.trim() ?? '';
  let parts: string[] = [];

  if (firstLine.includes('|')) {
    parts = firstLine.split('|').map((p) => p.trim());
  } else if (firstLine.includes('\t')) {
    parts = firstLine.split('\t').map((p) => p.trim());
  } else if (firstLine.includes(';')) {
    parts = firstLine.split(';').map((p) => p.trim());
  } else if (firstLine.includes(':')) {
    parts = firstLine.split(':').map((p) => p.trim());
  }

  if (parts.length === 4) {
    const [email, password, refresh_token, client_id] = parts;
    return validateMailboxFields({ email, password, refresh_token, client_id });
  }

  if (parts.length === 3) {
    const [email, refresh_token, client_id] = parts;
    return validateMailboxFields({ email, refresh_token, client_id });
  }

  if (parts.length > 4) {
    const email = parts[0] ?? '';
    const client_id = parts[parts.length - 1] ?? '';
    const refresh_token = parts[parts.length - 2] ?? '';
    const password = parts.slice(1, parts.length - 2).join('|');
    return validateMailboxFields({ email, password, refresh_token, client_id });
  }

  return {
    ok: false,
    error: 'Use format: email|password|refresh_token|client_id or email|refresh_token|client_id (or switch to Manual Fields).',
  };
}

export interface BulkLineError {
  line: number;
  error: string;
}

export interface BulkParseResult {
  valid: ParsedMailbox[];
  errors: BulkLineError[];
}

export function parseBulkMailboxInput(raw: string): BulkParseResult {
  const lines = raw.split(/\r?\n/);
  const byEmail = new Map<string, ParsedMailbox>();
  const errors: BulkLineError[] = [];

  lines.forEach((line, index) => {
    const trimmed = line.trim();
    if (!trimmed) return;

    const parsed = parseMailboxInput(trimmed);
    if (parsed.ok) {
      byEmail.set(parsed.value.email.toLowerCase(), parsed.value);
    } else {
      errors.push({
        line: index + 1,
        error: parsed.error,
      });
    }
  });

  return {
    valid: Array.from(byEmail.values()),
    errors,
  };
}

export function extractOtpCode(code?: string, content?: string): string {
  const cleanCode = (code ?? '').trim();
  if (cleanCode && cleanCode !== '----') {
    return cleanCode;
  }

  const body = (content ?? '').trim();
  if (!body) return '';

  const fbMatch = body.match(/\bFB-(\d{4,8})\b/i);
  if (fbMatch?.[1]) return fbMatch[1];

  const digitMatch = body.match(/\b(\d{4,8})\b/);
  return digitMatch?.[1] ?? '';
}

export function clampFloatingPosition(
  pos: FloatingPosition,
  barSize: { width: number; height: number },
  viewport: { width: number; height: number },
  margin = 8
): FloatingPosition {
  const maxX = Math.max(margin, viewport.width - barSize.width - margin);
  const maxY = Math.max(margin, viewport.height - barSize.height - margin);
  return {
    x: Math.min(Math.max(margin, pos.x), maxX),
    y: Math.min(Math.max(margin, pos.y), maxY),
  };
}

export function getNextMailboxIndex(currentId: string | null, mailboxes: ReadonlyArray<{ id: string }>): number {
  if (mailboxes.length === 0) return -1;
  if (!currentId) return 0;
  const idx = mailboxes.findIndex((m) => m.id === currentId);
  if (idx === -1) return 0;
  return (idx + 1) % mailboxes.length;
}

export const AUTO_POLL_INTERVAL_MS = 3000;
export const TWO_HOURS_MS = 2 * 60 * 60 * 1000;

export function hasNewMailCode(
  prev: { lastCode?: string; lastContent?: string; lastDate?: string },
  next: { code?: string; content?: string; date?: string }
): boolean {
  const nextCode = extractOtpCode(next.code, next.content);
  if (!nextCode) return false;

  const prevCode = (prev.lastCode ?? '').trim();
  if (!prevCode) return true;
  if (nextCode !== prevCode) return true;

  const nextDate = (next.date ?? '').trim();
  const prevDate = (prev.lastDate ?? '').trim();
  if (nextDate && prevDate && nextDate !== prevDate) return true;

  const nextContent = (next.content ?? '').trim();
  const prevContent = (prev.lastContent ?? '').trim();
  if (nextContent && prevContent && nextContent !== prevContent) return true;

  return false;
}

export function selectLatestIncomingMailboxId(
  mailboxes: ReadonlyArray<{ id: string; lastCode?: string; lastCodeReceivedAt?: number }>,
  currentActiveId: string | null
): string | null {
  if (mailboxes.length === 0) return null;

  let latest: { id: string; ts: number } | null = null;
  for (const mailbox of mailboxes) {
    if (mailbox.lastCode && typeof mailbox.lastCodeReceivedAt === 'number') {
      if (!latest || mailbox.lastCodeReceivedAt > latest.ts) {
        latest = { id: mailbox.id, ts: mailbox.lastCodeReceivedAt };
      }
    }
  }

  if (latest) return latest.id;
  if (currentActiveId && mailboxes.some((m) => m.id === currentActiveId)) {
    return currentActiveId;
  }
  return mailboxes[0].id;
}

export function isAutoReadActive(
  startedAt: number | null,
  now: number,
  enabled: boolean,
  mailboxCount: number
): boolean {
  if (!enabled || mailboxCount <= 0 || startedAt === null) return false;
  return now - startedAt < TWO_HOURS_MS;
}

export function formatRemainingTime(startedAt: number | null, now: number): string {
  if (startedAt === null) return '0m 00s';
  const remaining = Math.max(0, TWO_HOURS_MS - Math.max(0, now - startedAt));
  const totalSeconds = Math.floor(remaining / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) {
    return `${hours}h ${String(minutes).padStart(2, '0')}m`;
  }
  return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
}
