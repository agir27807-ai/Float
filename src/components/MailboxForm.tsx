import React, { useMemo, useState } from 'react';
import { ClipboardPaste, Mail, Save } from 'lucide-react';
import { MAIL_SERVICE_TYPES, type MailServiceType } from '../types/mail';
import { parseBulkMailboxInput, parseMailboxInput, validateMailboxFields } from '../utils/mailParser';

export interface SaveMailboxPayload {
  email: string;
  password?: string;
  refresh_token: string;
  client_id: string;
  serviceType: MailServiceType;
  isDemo?: boolean;
}

interface MailboxFormProps {
  inputRef?: React.RefObject<HTMLTextAreaElement | null>;
  onSaveMailbox: (data: SaveMailboxPayload) => void;
  onSaveBulkMailboxes: (items: SaveMailboxPayload[]) => void;
}

export const MailboxForm: React.FC<MailboxFormProps> = ({
  inputRef,
  onSaveMailbox,
  onSaveBulkMailboxes,
}) => {
  const [mode, setMode] = useState<'combo' | 'bulk' | 'fields'>('combo');
  const [comboText, setComboText] = useState('');
  const [bulkText, setBulkText] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [refreshToken, setRefreshToken] = useState('');
  const [clientId, setClientId] = useState('');
  const [serviceType, setServiceType] = useState<MailServiceType>('facebook');
  const [validationError, setValidationError] = useState<string | null>(null);
  const [savedFeedback, setSavedFeedback] = useState<string | null>(null);

  const bulkLineCount = useMemo(() => {
    return bulkText
      .split(/\r?\n/)
      .map((l) => l.trim())
      .filter(Boolean).length;
  }, [bulkText]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setValidationError(null);
    setSavedFeedback(null);

    if (mode === 'combo') {
      const parsed = parseMailboxInput(comboText);
      if (!parsed.ok) {
        setValidationError(parsed.error);
        return;
      }
      onSaveMailbox({
        ...parsed.value,
        serviceType,
        isDemo: parsed.value.refresh_token.startsWith('DEMO_REFRESH_TOKEN'),
      });
      setComboText('');
      setSavedFeedback(`Saved ${parsed.value.email}`);
      return;
    }

    if (mode === 'bulk') {
      if (!bulkText.trim()) {
        setValidationError(
          'Paste one or more Hotmail lines (email|password|refresh_token|client_id) in Bulk Mode.'
        );
        return;
      }

      const result = parseBulkMailboxInput(bulkText);
      if (result.valid.length === 0) {
        const firstErr = result.errors[0];
        setValidationError(
          firstErr
            ? `Line ${firstErr.line}: ${firstErr.error}`
            : 'No valid Hotmail OAuth2 lines found.'
        );
        return;
      }

      const payloads: SaveMailboxPayload[] = result.valid.map((item) => ({
        ...item,
        serviceType,
        isDemo: item.refresh_token.startsWith('DEMO_REFRESH_TOKEN'),
      }));

      onSaveBulkMailboxes(payloads);
      setBulkText('');

      if (result.errors.length > 0) {
        const skippedLines = result.errors.map((err) => err.line).join(', ');
        setSavedFeedback(
          `Saved ${payloads.length} ${payloads.length === 1 ? 'mailbox' : 'mailboxes'} (${result.errors.length} skipped at line ${skippedLines})`
        );
      } else {
        setSavedFeedback(
          `Saved ${payloads.length} ${payloads.length === 1 ? 'mailbox' : 'mailboxes'}`
        );
      }
      return;
    }

    const validated = validateMailboxFields({
      email,
      password,
      refresh_token: refreshToken,
      client_id: clientId,
    });
    if (!validated.ok) {
      setValidationError(validated.error);
      return;
    }
    onSaveMailbox({
      ...validated.value,
      serviceType,
      isDemo: validated.value.refresh_token.startsWith('DEMO_REFRESH_TOKEN'),
    });
    setEmail('');
    setPassword('');
    setRefreshToken('');
    setClientId('');
    setSavedFeedback(`Saved ${validated.value.email}`);
  };

  const handleLoadSample = () => {
    setValidationError(null);
    setSavedFeedback(null);
    const sampleLine1 =
      'demo.fb@hotmail.com|DemoPass99|DEMO_REFRESH_TOKEN_FB_001|5464fghj-bnmm-jh56-56fh-454345sdff';
    const sampleBulk = [
      sampleLine1,
      'demo.fb2@hotmail.com|DemoPass88|DEMO_REFRESH_TOKEN_FB_002|5464fghj-bnmm-jh56-56fh-454345sdff',
      'demo.fb3@outlook.com|DEMO_REFRESH_TOKEN_FB_003|5464fghj-bnmm-jh56-56fh-454345sdff',
    ].join('\n');

    if (mode === 'combo') {
      setComboText(sampleLine1);
    } else if (mode === 'bulk') {
      setBulkText(sampleBulk);
    } else {
      setEmail('demo.fb@hotmail.com');
      setPassword('DemoPass99');
      setRefreshToken('DEMO_REFRESH_TOKEN_FB_001');
      setClientId('5464fghj-bnmm-jh56-56fh-454345sdff');
    }
  };

  return (
    <section
      id="mailbox-section"
      aria-labelledby="mailbox-heading"
      className="bg-white border border-slate-200 rounded-2xl p-5 sm:p-6"
    >
      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <div>
          <h2 id="mailbox-heading" className="text-lg font-bold text-slate-900">
            Mail Box
          </h2>
          <p className="text-xs text-slate-500 mt-0.5">
            Paste single or bulk Hotmail OAuth2 credentials and save.
          </p>
        </div>

        <div className="flex items-center gap-1 p-1 bg-slate-100 rounded-lg" role="tablist" aria-label="Mailbox input mode">
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'combo'}
            onClick={() => {
              setMode('combo');
              setValidationError(null);
            }}
            className={`px-2.5 py-1.5 text-xs font-medium rounded-md whitespace-nowrap shrink-0 transition-colors ${
              mode === 'combo' ? 'bg-white text-slate-900 shadow-xs' : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Quick Paste
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'bulk'}
            onClick={() => {
              setMode('bulk');
              setValidationError(null);
            }}
            className={`px-2.5 py-1.5 text-xs font-medium rounded-md whitespace-nowrap shrink-0 transition-colors ${
              mode === 'bulk' ? 'bg-white text-slate-900 shadow-xs' : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Bulk Mode
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'fields'}
            onClick={() => {
              setMode('fields');
              setValidationError(null);
            }}
            className={`px-2.5 py-1.5 text-xs font-medium rounded-md whitespace-nowrap shrink-0 transition-colors ${
              mode === 'fields' ? 'bg-white text-slate-900 shadow-xs' : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            Manual Fields
          </button>
        </div>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4" noValidate>
        {mode === 'combo' ? (
          <div>
            <label htmlFor="mailbox-combo-input" className="block text-xs font-semibold text-slate-700 mb-1.5">
              Mail Box Input (email|password|refresh_token|client_id)
            </label>
            <textarea
              id="mailbox-combo-input"
              ref={inputRef}
              rows={3}
              value={comboText}
              onChange={(e) => {
                setComboText(e.target.value);
                if (validationError) setValidationError(null);
              }}
              placeholder="dsfsd45t4dfg@hotmail.com|M.C518_BAY.0.U.-CtLyFkvpx3K...|5464fghj-bnmm-jh56-56fh-454345sdff"
              className="w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2.5 text-xs sm:text-sm font-mono text-slate-900 placeholder:text-slate-400 focus:bg-white focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-600/15 resize-y"
            />
          </div>
        ) : mode === 'bulk' ? (
          <div>
            <div className="flex items-center justify-between gap-2 mb-1.5">
              <label htmlFor="mailbox-bulk-input" className="block text-xs font-semibold text-slate-700">
                Bulk Mail Box (one account per line)
              </label>
              <span className="text-xs font-mono tabular-nums text-slate-500">
                {bulkLineCount} {bulkLineCount === 1 ? 'line' : 'lines'}
              </span>
            </div>
            <textarea
              id="mailbox-bulk-input"
              rows={5}
              value={bulkText}
              onChange={(e) => {
                setBulkText(e.target.value);
                if (validationError) setValidationError(null);
              }}
              placeholder={`mail1@hotmail.com|pass1|M.C518_BAY...|client_id_1\nmail2@outlook.com|M.C518_BAY...|client_id_2`}
              className="w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3.5 py-2.5 text-xs font-mono text-slate-900 placeholder:text-slate-400 focus:bg-white focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-600/15 resize-y"
            />
          </div>
        ) : (
          <div className="space-y-3">
            <div>
              <label htmlFor="mailbox-email" className="block text-xs font-semibold text-slate-700 mb-1">
                Hotmail / Outlook Email
              </label>
              <input
                id="mailbox-email"
                type="email"
                value={email}
                onChange={(e) => {
                  setEmail(e.target.value);
                  if (validationError) setValidationError(null);
                }}
                placeholder="dsfsd45t4dfg@hotmail.com"
                className="w-full h-10 rounded-xl border border-slate-200 bg-slate-50/70 px-3 text-sm text-slate-900 placeholder:text-slate-400 focus:bg-white focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-600/15"
              />
            </div>

            <div>
              <label htmlFor="mailbox-refresh-token" className="block text-xs font-semibold text-slate-700 mb-1">
                OAuth2 Refresh Token
              </label>
              <textarea
                id="mailbox-refresh-token"
                rows={2}
                value={refreshToken}
                onChange={(e) => {
                  setRefreshToken(e.target.value);
                  if (validationError) setValidationError(null);
                }}
                placeholder="M.C518_BAY.0.U.-CtLyFkvpx3K..."
                className="w-full rounded-xl border border-slate-200 bg-slate-50/70 px-3 py-2 text-xs font-mono text-slate-900 placeholder:text-slate-400 focus:bg-white focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-600/15"
              />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label htmlFor="mailbox-client-id" className="block text-xs font-semibold text-slate-700 mb-1">
                  Client ID
                </label>
                <input
                  id="mailbox-client-id"
                  type="text"
                  value={clientId}
                  onChange={(e) => {
                    setClientId(e.target.value);
                    if (validationError) setValidationError(null);
                  }}
                  placeholder="5464fghj-bnmm-jh56-56fh-454345sdff"
                  className="w-full h-10 rounded-xl border border-slate-200 bg-slate-50/70 px-3 text-xs font-mono text-slate-900 placeholder:text-slate-400 focus:bg-white focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-600/15"
                />
              </div>
              <div>
                <label htmlFor="mailbox-password" className="block text-xs font-semibold text-slate-700 mb-1">
                  Password (Optional)
                </label>
                <input
                  id="mailbox-password"
                  type="text"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Optional reference"
                  className="w-full h-10 rounded-xl border border-slate-200 bg-slate-50/70 px-3 text-sm text-slate-900 placeholder:text-slate-400 focus:bg-white focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-600/15"
                />
              </div>
            </div>
          </div>
        )}

        <div className="flex flex-col sm:flex-row sm:items-end gap-3">
          <div className="sm:w-44">
            <label htmlFor="mailbox-service-type" className="block text-xs font-semibold text-slate-700 mb-1">
              Mail Filter Type
            </label>
            <select
              id="mailbox-service-type"
              value={serviceType}
              onChange={(e) => setServiceType(e.target.value as MailServiceType)}
              className="w-full h-10 rounded-xl border border-slate-200 bg-white px-3 text-sm text-slate-900 capitalize focus:border-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-600/15"
            >
              {MAIL_SERVICE_TYPES.map((type) => (
                <option key={type} value={type}>
                  {type}
                </option>
              ))}
            </select>
          </div>

          <div className="flex flex-1 items-center gap-2">
            <button
              type="submit"
              className="flex-1 h-10 inline-flex items-center justify-center gap-2 px-4 rounded-xl bg-slate-900 hover:bg-slate-800 active:scale-[0.99] text-white text-sm font-semibold whitespace-nowrap transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-slate-900 focus-visible:ring-offset-2"
            >
              <Save className="w-4 h-4" aria-hidden="true" />
              <span>{mode === 'bulk' ? 'Save All' : 'Save'}</span>
            </button>

            <button
              type="button"
              onClick={handleLoadSample}
              className="h-10 inline-flex items-center justify-center gap-1.5 px-3.5 rounded-xl border border-slate-200 bg-white hover:bg-slate-50 text-slate-700 text-xs font-semibold whitespace-nowrap transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-slate-400"
            >
              <ClipboardPaste className="w-3.5 h-3.5 text-slate-600" aria-hidden="true" />
              <span>{mode === 'bulk' ? 'Sample Bulk' : 'Load Sample'}</span>
            </button>
          </div>
        </div>

        {validationError && (
          <div
            role="alert"
            className="text-xs text-rose-700 bg-rose-50 border border-rose-200 rounded-xl px-3.5 py-2.5"
          >
            {validationError}
          </div>
        )}

        {savedFeedback && (
          <div
            role="status"
            className="flex items-center gap-2 text-xs text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-xl px-3.5 py-2.5"
          >
            <Mail className="w-3.5 h-3.5 shrink-0" aria-hidden="true" />
            <span>{savedFeedback}</span>
          </div>
        )}
      </form>
    </section>
  );
};
