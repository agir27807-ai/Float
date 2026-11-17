import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Download, Eye, EyeOff, Smartphone } from 'lucide-react';
import { FloatingReaderBar } from './components/FloatingReaderBar';
import { LatestMailCard } from './components/LatestMailCard';
import { MailboxForm, type SaveMailboxPayload } from './components/MailboxForm';
import { SavedMailboxesList } from './components/SavedMailboxesList';
import type { FloatingPosition, MailboxAccount, MailReadResponse, MailServiceType } from './types/mail';
import { copyTextToClipboard } from './utils/clipboard';
import {
  AUTO_POLL_INTERVAL_MS,
  TWO_HOURS_MS,
  clampFloatingPosition,
  extractOtpCode,
  formatRemainingTime,
  hasNewMailCode,
  isAutoReadActive,
} from './utils/mailParser';

const STORAGE_MAILBOXES_KEY = 'hotmail_reader_mailboxes_v1';
const STORAGE_ACTIVE_ID_KEY = 'hotmail_reader_active_id_v1';
const STORAGE_FLOAT_POS_KEY = 'hotmail_reader_float_pos_v1';
const STORAGE_AUTOREAD_START_KEY = 'hotmail_reader_autoread_start_v1';

function getInitialFloatingPosition(): FloatingPosition {
  if (typeof window === 'undefined') return { x: 24, y: 84 };
  try {
    const raw = localStorage.getItem(STORAGE_FLOAT_POS_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as FloatingPosition;
      if (typeof parsed.x === 'number' && typeof parsed.y === 'number') {
        return clampFloatingPosition(
          parsed,
          { width: 340, height: 56 },
          { width: window.innerWidth, height: window.innerHeight },
          8
        );
      }
    }
  } catch {
    // Ignore invalid storage
  }
  const isMobile = window.innerWidth < 640;
  return isMobile
    ? { x: 10, y: Math.max(76, window.innerHeight - 72) }
    : { x: Math.max(24, window.innerWidth - 460), y: 82 };
}

function loadStoredMailboxes(): MailboxAccount[] {
  try {
    const raw = localStorage.getItem(STORAGE_MAILBOXES_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as MailboxAccount[]) : [];
  } catch {
    return [];
  }
}

function loadAutoReadStartedAt(hasMailboxes: boolean): number | null {
  try {
    const raw = localStorage.getItem(STORAGE_AUTOREAD_START_KEY);
    if (raw) {
      const num = Number(raw);
      if (Number.isFinite(num) && num > 0) return num;
    }
  } catch {
    // Ignore invalid storage
  }
  return hasMailboxes ? Date.now() : null;
}

export default function App() {
  const [mailboxes, setMailboxes] = useState<MailboxAccount[]>(loadStoredMailboxes);
  const [activeId, setActiveId] = useState<string | null>(() => {
    const stored = localStorage.getItem(STORAGE_ACTIVE_ID_KEY);
    return stored || null;
  });
  const [floatingVisible, setFloatingVisible] = useState(true);
  const [floatingPos, setFloatingPos] = useState<FloatingPosition>(getInitialFloatingPosition);
  const [loadingId, setLoadingId] = useState<string | null>(null);
  const [bulkLoading, setBulkLoading] = useState(false);
  const [apiError, setApiError] = useState<string | null>(null);
  const [copiedValue, setCopiedValue] = useState<string | null>(null);

  const [autoReadEnabled, setAutoReadEnabled] = useState(true);
  const [autoReadStartedAt, setAutoReadStartedAt] = useState<number | null>(() =>
    loadAutoReadStartedAt(loadStoredMailboxes().length > 0)
  );
  const [nowMs, setNowMs] = useState(() => Date.now());

  const comboInputRef = useRef<HTMLTextAreaElement | null>(null);
  const copyTimerRef = useRef<number | null>(null);
  const mailboxesRef = useRef<MailboxAccount[]>(mailboxes);
  const pollInFlightRef = useRef(false);
  const arrivalSeqRef = useRef<number>(Date.now());

  useEffect(() => {
    mailboxesRef.current = mailboxes;
  }, [mailboxes]);

  const activeMailbox = useMemo(() => {
    if (mailboxes.length === 0) return null;
    return mailboxes.find((m) => m.id === activeId) ?? mailboxes[0];
  }, [mailboxes, activeId]);

  const autoReading = isAutoReadActive(
    autoReadStartedAt,
    nowMs,
    autoReadEnabled,
    mailboxes.length
  );
  const autoReadExpired =
    mailboxes.length > 0 &&
    autoReadStartedAt !== null &&
    nowMs - autoReadStartedAt >= TWO_HOURS_MS;
  const remainingTimeLabel = formatRemainingTime(autoReadStartedAt, nowMs);

  useEffect(() => {
    localStorage.setItem(STORAGE_MAILBOXES_KEY, JSON.stringify(mailboxes));
  }, [mailboxes]);

  useEffect(() => {
    if (activeMailbox) {
      localStorage.setItem(STORAGE_ACTIVE_ID_KEY, activeMailbox.id);
      if (activeId !== activeMailbox.id) {
        setActiveId(activeMailbox.id);
      }
    } else {
      localStorage.removeItem(STORAGE_ACTIVE_ID_KEY);
    }
  }, [activeMailbox, activeId]);

  useEffect(() => {
    localStorage.setItem(STORAGE_FLOAT_POS_KEY, JSON.stringify(floatingPos));
  }, [floatingPos]);

  useEffect(() => {
    if (autoReadStartedAt !== null) {
      localStorage.setItem(STORAGE_AUTOREAD_START_KEY, String(autoReadStartedAt));
    } else {
      localStorage.removeItem(STORAGE_AUTOREAD_START_KEY);
    }
  }, [autoReadStartedAt]);

  const triggerCopyFeedback = useCallback(async (key: string, text: string) => {
    const ok = await copyTextToClipboard(text);
    if (!ok) return;
    setCopiedValue(key);
    if (copyTimerRef.current) window.clearTimeout(copyTimerRef.current);
    copyTimerRef.current = window.setTimeout(() => {
      setCopiedValue(null);
    }, 1800);
  }, []);

  const handleCopyOtp = useCallback(
    (code: string) => {
      void triggerCopyFeedback(`otp:${code}`, code);
    },
    [triggerCopyFeedback]
  );

  const handleCopyEmail = useCallback(
    (email: string) => {
      void triggerCopyFeedback(`email:${email}`, email);
    },
    [triggerCopyFeedback]
  );

  const readSingleAccount = useCallback(
    async (target: MailboxAccount, autoFocusOnNewCode = true): Promise<string | null> => {
      const res = await fetch('/api/get-code', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: target.email,
          refresh_token: target.refresh_token,
          client_id: target.client_id,
          type: target.serviceType,
        }),
      });

      const data = (await res.json()) as MailReadResponse;
      if (!res.ok || data.status === false) {
        return data.error || data.message || 'No matching verification mail found yet.';
      }

      const extractedCode = extractOtpCode(data.code, data.content);
      const currentSnapshot = mailboxesRef.current.find((m) => m.id === target.id) ?? target;
      const isNewCode = hasNewMailCode(currentSnapshot, {
        code: extractedCode,
        content: data.content,
        date: data.date,
      });

      const receivedAt = isNewCode ? ++arrivalSeqRef.current : currentSnapshot.lastCodeReceivedAt;

      setMailboxes((prev) =>
        prev.map((m) =>
          m.id === target.id
            ? {
                ...m,
                lastCode: extractedCode || m.lastCode,
                lastContent:
                  data.content ||
                  (extractedCode ? `${extractedCode} confirmation code` : m.lastContent),
                lastDate: data.date || m.lastDate,
                lastFetchedAt: Date.now(),
                lastCodeReceivedAt: receivedAt,
              }
            : m
        )
      );

      // Auto-show whichever mailbox receives the latest code in the floating bar
      if (autoFocusOnNewCode && isNewCode && extractedCode) {
        setActiveId(target.id);
        setApiError(null);
      }

      return null;
    },
    []
  );

  const pollAllMailboxesOnce = useCallback(async () => {
    const list = mailboxesRef.current;
    if (list.length === 0 || pollInFlightRef.current) return;

    pollInFlightRef.current = true;
    const batchSize = 3;

    try {
      for (let i = 0; i < list.length; i += batchSize) {
        const batch = list.slice(i, i + batchSize);
        await Promise.all(
          batch.map(async (account) => {
            try {
              await readSingleAccount(account, true);
            } catch {
              // Keep polling remaining mailboxes even if one request fails transiently
            }
          })
        );
      }
    } finally {
      pollInFlightRef.current = false;
    }
  }, [readSingleAccount]);

  // 2-hour countdown clock & 3-second auto-read loop
  useEffect(() => {
    if (mailboxes.length === 0 || !autoReadEnabled || autoReadStartedAt === null) {
      return;
    }

    const clockId = window.setInterval(() => {
      setNowMs(Date.now());
    }, 1000);

    if (Date.now() - autoReadStartedAt >= TWO_HOURS_MS) {
      return () => window.clearInterval(clockId);
    }

    const pollId = window.setInterval(() => {
      const now = Date.now();
      setNowMs(now);
      if (now - autoReadStartedAt >= TWO_HOURS_MS) {
        window.clearInterval(pollId);
        return;
      }
      void pollAllMailboxesOnce();
    }, AUTO_POLL_INTERVAL_MS);

    return () => {
      window.clearInterval(clockId);
      window.clearInterval(pollId);
    };
  }, [mailboxes.length, autoReadEnabled, autoReadStartedAt, pollAllMailboxesOnce]);

  const handleSaveMailbox = useCallback(
    (data: SaveMailboxPayload) => {
      setApiError(null);
      const now = Date.now();
      setAutoReadStartedAt(now);
      setNowMs(now);
      setAutoReadEnabled(true);

      const existing = mailboxesRef.current.find(
        (m) => m.email.toLowerCase() === data.email.toLowerCase()
      );
      if (existing) {
        const updated: MailboxAccount = {
          ...existing,
          ...data,
        };
        setMailboxes((prev) => prev.map((m) => (m.id === existing.id ? updated : m)));
        setActiveId(existing.id);
        window.setTimeout(() => void readSingleAccount(updated, true), 50);
        return;
      }

      const created: MailboxAccount = {
        id: `${now}-${Math.random().toString(36).slice(2, 7)}`,
        email: data.email,
        password: data.password,
        refresh_token: data.refresh_token,
        client_id: data.client_id,
        serviceType: data.serviceType,
        createdAt: now,
        isDemo: data.isDemo,
      };
      setMailboxes((prev) => [created, ...prev]);
      setActiveId(created.id);
      window.setTimeout(() => void readSingleAccount(created, true), 50);
    },
    [readSingleAccount]
  );

  const handleSaveBulkMailboxes = useCallback(
    (items: SaveMailboxPayload[]) => {
      if (items.length === 0) return;
      setApiError(null);
      const now = Date.now();
      setAutoReadStartedAt(now);
      setNowMs(now);
      setAutoReadEnabled(true);

      let firstCreatedOrUpdatedId: string | null = null;
      const nextList = [...mailboxesRef.current];

      for (let i = items.length - 1; i >= 0; i--) {
        const item = items[i];
        const existingIdx = nextList.findIndex(
          (m) => m.email.toLowerCase() === item.email.toLowerCase()
        );
        if (existingIdx >= 0) {
          nextList[existingIdx] = {
            ...nextList[existingIdx],
            ...item,
          };
          firstCreatedOrUpdatedId = nextList[existingIdx].id;
        } else {
          const created: MailboxAccount = {
            id: `${now}-${i}-${Math.random().toString(36).slice(2, 7)}`,
            email: item.email,
            password: item.password,
            refresh_token: item.refresh_token,
            client_id: item.client_id,
            serviceType: item.serviceType,
            createdAt: now,
            isDemo: item.isDemo,
          };
          nextList.unshift(created);
          firstCreatedOrUpdatedId = created.id;
        }
      }

      mailboxesRef.current = nextList;
      setMailboxes(nextList);
      if (firstCreatedOrUpdatedId) {
        setActiveId(firstCreatedOrUpdatedId);
      }

      // Trigger immediate first poll across the saved bulk accounts
      window.setTimeout(() => void pollAllMailboxesOnce(), 50);
    },
    [pollAllMailboxesOnce]
  );

  const fetchLatestForMailbox = useCallback(
    async (targetId?: string) => {
      const target = targetId
        ? mailboxesRef.current.find((m) => m.id === targetId)
        : activeMailbox;
      if (!target) return;

      setLoadingId(target.id);
      setActiveId(target.id);
      setApiError(null);

      try {
        const errMsg = await readSingleAccount(target, true);
        if (errMsg) {
          setApiError(errMsg);
        }
      } catch (err) {
        setApiError(err instanceof Error ? err.message : 'Network error reading Hotmail API.');
      } finally {
        setLoadingId(null);
      }
    },
    [activeMailbox, readSingleAccount]
  );

  const handleRefreshAllMailboxes = useCallback(async () => {
    if (mailboxesRef.current.length === 0) return;
    setBulkLoading(true);
    setApiError(null);
    try {
      await pollAllMailboxesOnce();
    } finally {
      setBulkLoading(false);
    }
  }, [pollAllMailboxesOnce]);

  const handleToggleAutoRead = useCallback(() => {
    setAutoReadEnabled((prev) => !prev);
  }, []);

  const handleRestartAutoRead = useCallback(() => {
    const now = Date.now();
    setAutoReadStartedAt(now);
    setNowMs(now);
    setAutoReadEnabled(true);
    void pollAllMailboxesOnce();
  }, [pollAllMailboxesOnce]);

  const handleChangeServiceType = useCallback(
    (serviceType: MailServiceType) => {
      if (!activeMailbox) return;
      setMailboxes((prev) =>
        prev.map((m) => (m.id === activeMailbox.id ? { ...m, serviceType } : m))
      );
    },
    [activeMailbox]
  );

  const handleDeleteMailbox = useCallback(
    (id: string) => {
      setMailboxes((prev) => prev.filter((m) => m.id !== id));
      if (activeId === id) {
        setApiError(null);
      }
    },
    [activeId]
  );

  const handleClearAllMailboxes = useCallback(() => {
    setMailboxes([]);
    setActiveId(null);
    setApiError(null);
    setAutoReadStartedAt(null);
  }, []);

  return (
    <div className="min-h-screen flex flex-col bg-[#f4f6f8] text-slate-900">
      {/* Top Bar Contract: 3 zones separated by gap-8 */}
      <header className="sticky top-0 z-30 bg-white/90 backdrop-blur-md border-b border-slate-200">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between gap-8">
          <a
            href="#mailbox-section"
            className="text-base sm:text-lg font-display font-bold tracking-tight text-slate-900 whitespace-nowrap shrink-0"
          >
            Hotmail OTP Reader
          </a>

          <nav className="hidden md:flex items-center gap-6 text-sm font-medium text-slate-600" aria-label="Workspace sections">
            <a href="#mailbox-section" className="hover:text-slate-900 transition-colors whitespace-nowrap shrink-0">
              Mail Box
            </a>
            <a href="#saved-accounts-section" className="hover:text-slate-900 transition-colors whitespace-nowrap shrink-0">
              Saved Accounts
            </a>
            <a href="#latest-mail-section" className="hover:text-slate-900 transition-colors whitespace-nowrap shrink-0">
              Latest Mail
            </a>
            <a href="#api-reference-section" className="hover:text-slate-900 transition-colors whitespace-nowrap shrink-0">
              API Format
            </a>
            <a href="#android-export-section" className="hover:text-slate-900 transition-colors whitespace-nowrap shrink-0">
              Android App
            </a>
          </nav>

          <div className="flex items-center shrink-0">
            <button
              type="button"
              onClick={() => setFloatingVisible((prev) => !prev)}
              aria-pressed={floatingVisible}
              className="h-9 inline-flex items-center gap-1.5 px-3.5 rounded-xl bg-slate-900 hover:bg-slate-800 text-white text-xs font-semibold whitespace-nowrap shrink-0 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-slate-900 focus-visible:ring-offset-2"
            >
              {floatingVisible ? (
                <>
                  <EyeOff className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>Hide Floating Bar</span>
                </>
              ) : (
                <>
                  <Eye className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>Show Floating Bar</span>
                </>
              )}
            </button>
          </div>
        </div>
      </header>

      {/* Movable Floating Bar Overlay (auto-shows latest incoming code, no CHG/REF) */}
      <FloatingReaderBar
        visible={floatingVisible}
        activeMailbox={activeMailbox}
        autoReading={autoReading}
        error={apiError}
        copiedValue={copiedValue}
        position={floatingPos}
        onPositionChange={setFloatingPos}
        onCopyOtp={handleCopyOtp}
        onCopyEmail={handleCopyEmail}
      />

      {/* Main Workspace */}
      <main className="flex-1 max-w-6xl w-full mx-auto px-4 sm:px-6 py-6 sm:py-8 pb-24">
        <div className="mb-6">
          <h1 className="text-2xl sm:text-3xl font-bold tracking-tight text-slate-900 text-balance">
            Floating Hotmail OAuth2 Reader
          </h1>
          <p className="text-sm text-slate-600 mt-1 max-w-2xl">
            Save single or bulk Hotmail OAuth2 accounts in the Mail Box. Saved accounts auto-read every 3 seconds for 2 hours, and the floating bar automatically displays whichever mailbox receives the latest code.
          </p>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
          {/* Left Column: Mail Box Input + Saved Accounts */}
          <div className="lg:col-span-5 space-y-6">
            <MailboxForm
              inputRef={comboInputRef}
              onSaveMailbox={handleSaveMailbox}
              onSaveBulkMailboxes={handleSaveBulkMailboxes}
            />
            <SavedMailboxesList
              mailboxes={mailboxes}
              activeId={activeMailbox?.id ?? null}
              loadingId={loadingId}
              bulkLoading={bulkLoading}
              autoReading={autoReading}
              autoReadExpired={autoReadExpired}
              remainingTimeLabel={remainingTimeLabel}
              copiedValue={copiedValue}
              onSelect={(id) => {
                setActiveId(id);
                setApiError(null);
              }}
              onRefresh={(id) => void fetchLatestForMailbox(id)}
              onReadAll={() => void handleRefreshAllMailboxes()}
              onToggleAutoRead={handleToggleAutoRead}
              onRestartAutoRead={handleRestartAutoRead}
              onDelete={handleDeleteMailbox}
              onClearAll={handleClearAllMailboxes}
              onCopyOtp={handleCopyOtp}
            />
          </div>

          {/* Right Column: Latest Mail Reader + API Format */}
          <div className="lg:col-span-7 space-y-6">
            <LatestMailCard
              activeMailbox={activeMailbox}
              loading={Boolean(activeMailbox && loadingId === activeMailbox.id)}
              error={apiError}
              copiedValue={copiedValue}
              onChangeServiceType={handleChangeServiceType}
              onRefresh={() => void fetchLatestForMailbox()}
              onCopyOtp={handleCopyOtp}
            />

            <section
              id="api-reference-section"
              aria-labelledby="api-reference-heading"
              className="bg-white border border-slate-200 rounded-2xl p-5 sm:p-6"
            >
              <h2 id="api-reference-heading" className="text-base font-bold text-slate-900">
                OAuth2 API Connection
              </h2>
              <p className="text-xs text-slate-600 mt-1">
                Requests are proxied server-side to <code className="font-mono text-slate-800">tools.dongvanfb.net/api/get_code_oauth2</code> every 3 seconds for up to 2 hours.
              </p>

              <div className="mt-3 p-3.5 rounded-xl bg-slate-950 text-slate-200 font-mono text-xs overflow-x-auto leading-relaxed">
                <pre>{`POST /api/get-code -> https://tools.dongvanfb.net/api/get_code_oauth2
{
  "email": "dsfsd45t4dfg@hotmail.com",
  "refresh_token": "M.C518_BAY.0.U.-...",
  "client_id": "5464fghj-bnmm-jh56-56fh-454345sdff",
  "type": "${activeMailbox?.serviceType ?? 'facebook'}"
}`}</pre>
              </div>
            </section>

            <section
              id="android-export-section"
              aria-labelledby="android-export-heading"
              className="bg-white border border-slate-200 rounded-2xl p-5 sm:p-6"
            >
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                <div>
                  <div className="flex items-center gap-2">
                    <Smartphone className="w-4 h-4 text-emerald-700" aria-hidden="true" />
                    <h2 id="android-export-heading" className="text-base font-bold text-slate-900">
                      Native Android Kotlin Project (Overlay APK Source)
                    </h2>
                  </div>
                  <p className="text-xs text-slate-600 mt-1 max-w-xl">
                    Downloads a clean, standalone Native Android Kotlin project (<code className="font-mono text-slate-800">gradlew</code>, <code className="font-mono text-slate-800">app/</code>, <code className="font-mono text-slate-800">FloatingOverlayService.kt</code> with <code className="font-mono text-slate-800">SYSTEM_ALERT_WINDOW</code>) with zero web files—ready to push directly to GitHub or open in Android Studio.
                  </p>
                </div>

                <a
                  href="/api/download-android-project"
                  download="HotmailFloatingOtpReader-Android.zip"
                  className="h-10 inline-flex items-center justify-center gap-2 px-4 rounded-xl bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-semibold whitespace-nowrap shrink-0 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600 focus-visible:ring-offset-2"
                >
                  <Download className="w-4 h-4" aria-hidden="true" />
                  <span>Download Pure Android (.zip)</span>
                </a>
              </div>
            </section>
          </div>
        </div>
      </main>
    </div>
  );
}
