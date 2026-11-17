import React, { useState } from 'react';
import { Check, Copy, Pause, Play, RefreshCw, RotateCcw, Trash2 } from 'lucide-react';
import type { MailboxAccount } from '../types/mail';

interface SavedMailboxesListProps {
  mailboxes: MailboxAccount[];
  activeId: string | null;
  loadingId: string | null;
  bulkLoading: boolean;
  autoReading: boolean;
  autoReadExpired: boolean;
  remainingTimeLabel: string;
  copiedValue: string | null;
  onSelect: (id: string) => void;
  onRefresh: (id: string) => void;
  onReadAll: () => void;
  onToggleAutoRead: () => void;
  onRestartAutoRead: () => void;
  onDelete: (id: string) => void;
  onClearAll: () => void;
  onCopyOtp: (code: string) => void;
}

export const SavedMailboxesList: React.FC<SavedMailboxesListProps> = ({
  mailboxes,
  activeId,
  loadingId,
  bulkLoading,
  autoReading,
  autoReadExpired,
  remainingTimeLabel,
  copiedValue,
  onSelect,
  onRefresh,
  onReadAll,
  onToggleAutoRead,
  onRestartAutoRead,
  onDelete,
  onClearAll,
  onCopyOtp,
}) => {
  const [confirmClear, setConfirmClear] = useState(false);

  return (
    <section
      id="saved-accounts-section"
      aria-labelledby="saved-accounts-heading"
      className="bg-white border border-slate-200 rounded-2xl p-5 sm:p-6"
    >
      <div className="flex flex-wrap items-center justify-between gap-3 mb-3">
        <div>
          <h2 id="saved-accounts-heading" className="text-lg font-bold text-slate-900">
            Saved Mailboxes
          </h2>
          <p className="text-xs text-slate-500 mt-0.5 tabular-nums">
            {mailboxes.length} {mailboxes.length === 1 ? 'account' : 'accounts'} saved locally
          </p>
        </div>

        {mailboxes.length > 0 && (
          <div className="flex items-center gap-1.5">
            <button
              type="button"
              onClick={onReadAll}
              disabled={bulkLoading || Boolean(loadingId)}
              className="h-8 inline-flex items-center gap-1.5 px-2.5 rounded-lg bg-blue-50 hover:bg-blue-100 text-blue-700 text-xs font-semibold whitespace-nowrap disabled:opacity-50 transition-colors"
            >
              <RefreshCw className={`w-3 h-3 ${bulkLoading ? 'animate-spin' : ''}`} aria-hidden="true" />
              <span>{bulkLoading ? 'Reading...' : 'Read All'}</span>
            </button>

            {confirmClear ? (
              <div className="flex items-center gap-1">
                <button
                  type="button"
                  onClick={() => {
                    onClearAll();
                    setConfirmClear(false);
                  }}
                  className="h-8 px-2.5 rounded-lg bg-rose-600 hover:bg-rose-700 text-white text-xs font-semibold whitespace-nowrap transition-colors"
                >
                  Confirm Clear
                </button>
                <button
                  type="button"
                  onClick={() => setConfirmClear(false)}
                  className="h-8 px-2 rounded-lg border border-slate-200 text-slate-600 hover:bg-slate-50 text-xs font-medium whitespace-nowrap transition-colors"
                >
                  Cancel
                </button>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => setConfirmClear(true)}
                className="h-8 px-2.5 rounded-lg border border-slate-200 hover:bg-rose-50 hover:border-rose-200 text-slate-600 hover:text-rose-700 text-xs font-semibold whitespace-nowrap transition-colors"
              >
                Clear All
              </button>
            )}
          </div>
        )}
      </div>

      {mailboxes.length > 0 && (
        <div
          data-testid="auto-read-status"
          className="flex flex-wrap items-center justify-between gap-2 pb-3 mb-3 border-b border-slate-100 text-xs"
        >
          <div className="flex items-center gap-1.5 text-slate-600">
            {autoReadExpired ? (
              <span className="font-medium text-amber-700">2h auto-read completed</span>
            ) : autoReading ? (
              <>
                <span className="font-semibold text-emerald-700">Auto-read every 3s</span>
                <span aria-hidden="true">·</span>
                <span className="font-mono tabular-nums text-slate-600">{remainingTimeLabel} left</span>
              </>
            ) : (
              <>
                <span className="font-medium text-slate-700">Auto-read paused</span>
                <span aria-hidden="true">·</span>
                <span className="font-mono tabular-nums text-slate-500">{remainingTimeLabel} left</span>
              </>
            )}
          </div>

          {autoReadExpired ? (
            <button
              type="button"
              onClick={onRestartAutoRead}
              className="inline-flex items-center gap-1 px-2.5 h-7 rounded-lg bg-slate-900 hover:bg-slate-800 text-white text-xs font-semibold whitespace-nowrap transition-colors"
            >
              <RotateCcw className="w-3 h-3" aria-hidden="true" />
              <span>Restart 2h</span>
            </button>
          ) : (
            <button
              type="button"
              onClick={onToggleAutoRead}
              className="inline-flex items-center gap-1 px-2.5 h-7 rounded-lg border border-slate-200 bg-slate-50 hover:bg-slate-100 text-slate-700 text-xs font-semibold whitespace-nowrap transition-colors"
            >
              {autoReading ? (
                <>
                  <Pause className="w-3 h-3" aria-hidden="true" />
                  <span>Pause</span>
                </>
              ) : (
                <>
                  <Play className="w-3 h-3" aria-hidden="true" />
                  <span>Resume</span>
                </>
              )}
            </button>
          )}
        </div>
      )}

      {mailboxes.length === 0 ? (
        <div className="py-8 px-4 text-center border border-dashed border-slate-200 rounded-xl bg-slate-50/50">
          <p className="text-sm font-medium text-slate-700">No mailboxes saved yet</p>
          <p className="text-xs text-slate-500 mt-1 max-w-xs mx-auto">
            Paste a Hotmail OAuth2 string (or switch to Bulk Mode) above and click Save.
          </p>
        </div>
      ) : (
        <ul className="divide-y divide-slate-100 max-h-[380px] overflow-y-auto pr-1" data-testid="saved-mailboxes-list">
          {mailboxes.map((mailbox) => {
            const isActive = mailbox.id === activeId;
            const isLoading = mailbox.id === loadingId;
            const hasCode = Boolean(mailbox.lastCode);
            const isCopied = hasCode && copiedValue === `otp:${mailbox.lastCode}`;

            return (
              <li
                key={mailbox.id}
                className={`py-3 first:pt-1 last:pb-1 flex items-center justify-between gap-3 ${
                  isActive ? 'bg-blue-50/40 -mx-2 px-2 rounded-xl' : ''
                }`}
              >
                <div className="min-w-0 flex-1">
                  <button
                    type="button"
                    onClick={() => onSelect(mailbox.id)}
                    aria-label={`Select ${mailbox.email}`}
                    className="text-left w-full group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-600 rounded-md"
                  >
                    <div className="flex items-center gap-2">
                      <span
                        className={`text-sm font-semibold truncate ${
                          isActive ? 'text-blue-700' : 'text-slate-900 group-hover:text-blue-700'
                        }`}
                      >
                        {mailbox.email}
                      </span>
                      {isActive && (
                        <span className="text-[11px] font-semibold text-blue-700 shrink-0">Active</span>
                      )}
                    </div>
                    <div className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5 text-xs text-slate-500 mt-0.5">
                      <span className="capitalize whitespace-nowrap">{mailbox.serviceType}</span>
                      {mailbox.lastDate && (
                        <>
                          <span aria-hidden="true">·</span>
                          <span className="font-mono tabular-nums whitespace-nowrap">{mailbox.lastDate}</span>
                        </>
                      )}
                      {mailbox.isDemo && (
                        <>
                          <span aria-hidden="true">·</span>
                          <span className="whitespace-nowrap">Sample</span>
                        </>
                      )}
                    </div>
                  </button>
                </div>

                <div className="flex items-center gap-1.5 shrink-0">
                  {hasCode && mailbox.lastCode && (
                    <button
                      type="button"
                      onClick={() => onCopyOtp(mailbox.lastCode!)}
                      aria-label={`Copy code ${mailbox.lastCode} for ${mailbox.email}`}
                      className="inline-flex items-center gap-1 px-2.5 h-8 rounded-lg bg-emerald-50 hover:bg-emerald-100 text-emerald-700 font-mono text-xs font-semibold tabular-nums whitespace-nowrap transition-colors"
                    >
                      <span>{isCopied ? 'Copied' : mailbox.lastCode}</span>
                      {isCopied ? (
                        <Check className="w-3 h-3" aria-hidden="true" />
                      ) : (
                        <Copy className="w-3 h-3" aria-hidden="true" />
                      )}
                    </button>
                  )}

                  <button
                    type="button"
                    onClick={() => onRefresh(mailbox.id)}
                    disabled={isLoading}
                    aria-label={`Read latest mail for ${mailbox.email}`}
                    title="Read latest mail"
                    className="inline-flex items-center justify-center w-9 h-9 rounded-lg border border-slate-200 bg-white hover:bg-slate-50 text-slate-700 disabled:opacity-50 transition-colors"
                  >
                    <RefreshCw className={`w-3.5 h-3.5 ${isLoading ? 'animate-spin' : ''}`} aria-hidden="true" />
                  </button>

                  <button
                    type="button"
                    onClick={() => onDelete(mailbox.id)}
                    aria-label={`Delete ${mailbox.email}`}
                    title={`Delete ${mailbox.email}`}
                    className="inline-flex items-center justify-center w-9 h-9 rounded-lg border border-slate-200 bg-white hover:bg-rose-50 hover:border-rose-200 text-slate-500 hover:text-rose-600 transition-colors"
                  >
                    <Trash2 className="w-3.5 h-3.5" aria-hidden="true" />
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
};
