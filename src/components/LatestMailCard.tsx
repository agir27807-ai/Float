import React from 'react';
import { AlertCircle, Check, Copy, MailOpen, RefreshCw } from 'lucide-react';
import {
  MAIL_CONNECTION_MODES,
  MAIL_SERVICE_TYPES,
  type MailboxAccount,
  type MailConnectionMode,
  type MailServiceType,
} from '../types/mail';

interface LatestMailCardProps {
  activeMailbox: MailboxAccount | null;
  loading: boolean;
  error: string | null;
  copiedValue: string | null;
  onChangeConnectionMode: (connectionMode: MailConnectionMode) => void;
  onChangeServiceType: (serviceType: MailServiceType) => void;
  onRefresh: () => void;
  onCopyOtp: (code: string) => void;
}

function formatConnectionModeLabel(mode?: MailConnectionMode): string {
  if (mode === 'graph') return 'Graph API';
  if (mode === 'oauth') return 'OAuth2 API';
  return 'Auto';
}

export const LatestMailCard: React.FC<LatestMailCardProps> = ({
  activeMailbox,
  loading,
  error,
  copiedValue,
  onChangeConnectionMode,
  onChangeServiceType,
  onRefresh,
  onCopyOtp,
}) => {
  const code = activeMailbox?.lastCode || '';
  const hasCode = Boolean(code);
  const isCopied = hasCode && copiedValue === `otp:${code}`;
  const displayError = error || (!hasCode && !activeMailbox?.lastContent ? activeMailbox?.lastError || null : null);

  return (
    <section
      id="latest-mail-section"
      aria-labelledby="latest-mail-heading"
      className="bg-white border border-slate-200 rounded-2xl p-5 sm:p-6"
    >
      <div className="flex flex-wrap items-center justify-between gap-3 pb-4 border-b border-slate-100">
        <div>
          <h2 id="latest-mail-heading" className="text-lg font-bold text-slate-900">
            Latest Verification Mail
          </h2>
          {activeMailbox ? (
            <div className="flex flex-wrap items-center gap-2 text-xs text-slate-500 mt-0.5">
              <span className="font-medium text-slate-700">{activeMailbox.email}</span>
              <span aria-hidden="true">·</span>
              <span>{formatConnectionModeLabel(activeMailbox.connectionMode)}</span>
              <span aria-hidden="true">·</span>
              <span className="capitalize">{activeMailbox.serviceType}</span>
              {activeMailbox.lastDate && (
                <>
                  <span aria-hidden="true">·</span>
                  <span className="font-mono tabular-nums">{activeMailbox.lastDate}</span>
                </>
              )}
            </div>
          ) : (
            <p className="text-xs text-slate-500 mt-0.5">Select or save a mailbox to read incoming OTP mail.</p>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {activeMailbox && (
            <>
              <select
                aria-label="Connection API mode"
                value={activeMailbox.connectionMode ?? 'auto'}
                onChange={(e) => onChangeConnectionMode(e.target.value as MailConnectionMode)}
                className="h-9 rounded-xl border border-slate-200 bg-white px-2.5 text-xs font-medium text-slate-700 focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-600/15"
              >
                {MAIL_CONNECTION_MODES.map((m) => (
                  <option key={m} value={m}>
                    {m === 'auto' ? 'Auto' : m === 'graph' ? 'Graph API' : 'OAuth2 API'}
                  </option>
                ))}
              </select>

              <select
                aria-label="Filter mail by service"
                value={activeMailbox.serviceType}
                onChange={(e) => onChangeServiceType(e.target.value as MailServiceType)}
                className="h-9 rounded-xl border border-slate-200 bg-white px-2.5 text-xs font-medium text-slate-700 capitalize focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-600/15"
              >
                {MAIL_SERVICE_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {type}
                  </option>
                ))}
              </select>
            </>
          )}

          <button
            type="button"
            onClick={onRefresh}
            disabled={!activeMailbox || loading}
            className="h-9 inline-flex items-center gap-1.5 px-3.5 rounded-xl bg-blue-600 hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed text-white text-xs font-semibold whitespace-nowrap transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-600 focus-visible:ring-offset-2"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} aria-hidden="true" />
            <span>{loading ? 'Reading...' : 'Read Latest Mail'}</span>
          </button>
        </div>
      </div>

      <div className="pt-5">
        {!activeMailbox ? (
          <div className="py-10 px-4 text-center rounded-xl bg-slate-50 border border-dashed border-slate-200">
            <MailOpen className="w-7 h-7 text-slate-400 mx-auto mb-2" aria-hidden="true" />
            <p className="text-sm font-semibold text-slate-800">No active Hotmail account</p>
            <p className="text-xs text-slate-500 mt-1 max-w-sm mx-auto">
              Add one or more OAuth2 or Graph mailboxes in the Mail Box panel on the left to start 3-second auto-reading.
            </p>
          </div>
        ) : loading ? (
          <div className="space-y-4 animate-pulse" aria-busy="true" aria-label="Loading latest mail">
            <div className="h-20 rounded-xl bg-slate-100" />
            <div className="h-24 rounded-xl bg-slate-100" />
          </div>
        ) : displayError ? (
          <div
            role="alert"
            className="flex items-start gap-3 p-4 rounded-xl bg-rose-50 border border-rose-200 text-rose-800"
          >
            <AlertCircle className="w-5 h-5 shrink-0 mt-0.5 text-rose-600" aria-hidden="true" />
            <div className="flex-1 min-w-0">
              <p className="text-sm font-semibold">Could not read verification mail</p>
              <p className="text-xs text-rose-700 mt-1 break-words">{displayError}</p>
            </div>
          </div>
        ) : activeMailbox.lastContent || hasCode ? (
          <div className="space-y-4" data-testid="latest-mail-result">
            {/* Click-to-copy OTP Box */}
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 p-4 rounded-xl bg-slate-950 text-white">
              <div>
                <div className="text-xs text-slate-400">
                  Latest <span className="capitalize">{activeMailbox.serviceType}</span> code (click to copy)
                </div>
                <div className="font-mono text-2xl sm:text-3xl font-bold tracking-wider text-emerald-400 tabular-nums mt-0.5">
                  {hasCode ? code : '----'}
                </div>
              </div>

              {hasCode && (
                <button
                  type="button"
                  onClick={() => onCopyOtp(code)}
                  className="h-10 inline-flex items-center justify-center gap-2 px-4 rounded-xl bg-emerald-500 hover:bg-emerald-400 text-slate-950 text-xs font-bold whitespace-nowrap transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-300"
                >
                  {isCopied ? (
                    <>
                      <Check className="w-4 h-4" aria-hidden="true" />
                      <span>Copied {code}</span>
                    </>
                  ) : (
                    <>
                      <Copy className="w-4 h-4" aria-hidden="true" />
                      <span>Copy Code</span>
                    </>
                  )}
                </button>
              )}
            </div>

            {/* Latest Email Body */}
            <div className="p-4 rounded-xl bg-slate-50 border border-slate-200/80">
              <div className="flex items-center justify-between gap-2 text-xs text-slate-500 mb-2">
                <span className="font-semibold text-slate-700">Email Content</span>
                {activeMailbox.lastDate && (
                  <span className="font-mono tabular-nums">{activeMailbox.lastDate}</span>
                )}
              </div>
              <p className="text-sm leading-relaxed text-slate-800 whitespace-pre-wrap break-words">
                {activeMailbox.lastContent || 'No message body returned.'}
              </p>
            </div>
          </div>
        ) : (
          <div className="py-10 px-4 text-center rounded-xl bg-slate-50 border border-dashed border-slate-200">
            <MailOpen className="w-7 h-7 text-slate-400 mx-auto mb-2" aria-hidden="true" />
            <p className="text-sm font-semibold text-slate-800">Ready to read {activeMailbox.email}</p>
            <p className="text-xs text-slate-500 mt-1 max-w-sm mx-auto">
              Auto-read checks continuously every 3 seconds, or click Read Latest Mail above.
            </p>
          </div>
        )}
      </div>
    </section>
  );
};
