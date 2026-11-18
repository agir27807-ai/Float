import React, { useEffect, useRef, useState } from 'react';
import { Check, ChevronDown, ChevronUp, Copy, Lock, Move } from 'lucide-react';
import type { FloatingPosition, MailboxAccount } from '../types/mail';
import { clampFloatingPosition } from '../utils/mailParser';

interface FloatingReaderBarProps {
  visible: boolean;
  activeMailbox: MailboxAccount | null;
  autoReading: boolean;
  error: string | null;
  copiedValue: string | null;
  position: FloatingPosition;
  onPositionChange: (pos: FloatingPosition) => void;
  onCopyOtp: (code: string) => void;
  onCopyEmail: (email: string) => void;
}

export const FloatingReaderBar: React.FC<FloatingReaderBarProps> = ({
  visible,
  activeMailbox,
  autoReading,
  error,
  copiedValue,
  position,
  onPositionChange,
  onCopyOtp,
  onCopyEmail,
}) => {
  const barRef = useRef<HTMLDivElement | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [minimized, setMinimized] = useState(false);
  const [isDragging, setIsDragging] = useState(false);
  const [viewportHeight, setViewportHeight] = useState(() =>
    typeof window !== 'undefined' ? window.innerHeight : 900
  );

  const suppressClickRef = useRef(false);
  const dragStateRef = useRef<{
    active: boolean;
    moved: boolean;
    pointerId: number | null;
    startX: number;
    startY: number;
    originX: number;
    originY: number;
  }>({
    active: false,
    moved: false,
    pointerId: null,
    startX: 0,
    startY: 0,
    originX: 0,
    originY: 0,
  });

  useEffect(() => {
    const syncBounds = () => {
      setViewportHeight(window.innerHeight);
      if (!barRef.current) return;
      const rect = barRef.current.getBoundingClientRect();
      const clamped = clampFloatingPosition(
        position,
        { width: rect.width || (minimized ? 46 : 340), height: rect.height || 46 },
        { width: window.innerWidth, height: window.innerHeight },
        8
      );
      if (clamped.x !== position.x || clamped.y !== position.y) {
        onPositionChange(clamped);
      }
    };
    syncBounds();
    window.addEventListener('resize', syncBounds);
    let observer: ResizeObserver | null = null;
    if (typeof ResizeObserver !== 'undefined' && barRef.current) {
      observer = new ResizeObserver(syncBounds);
      observer.observe(barRef.current);
    }
    return () => {
      window.removeEventListener('resize', syncBounds);
      observer?.disconnect();
    };
  }, [position, expanded, minimized, visible, activeMailbox?.email, activeMailbox?.lastCode, onPositionChange]);

  const handleDragStart = (e: React.PointerEvent<HTMLElement>) => {
    if (e.button !== 0 && e.pointerType === 'mouse') return;
    suppressClickRef.current = false;
    e.currentTarget.setPointerCapture(e.pointerId);
    dragStateRef.current = {
      active: true,
      moved: false,
      pointerId: e.pointerId,
      startX: e.clientX,
      startY: e.clientY,
      originX: position.x,
      originY: position.y,
    };
    setIsDragging(true);
  };

  const handleDragMove = (e: React.PointerEvent<HTMLElement>) => {
    const state = dragStateRef.current;
    if (!state.active || state.pointerId !== e.pointerId) return;

    const dx = e.clientX - state.startX;
    const dy = e.clientY - state.startY;
    if (!state.moved && (Math.abs(dx) > 5 || Math.abs(dy) > 5)) {
      state.moved = true;
    }
    if (!state.moved) return;

    const rect = barRef.current?.getBoundingClientRect();
    const clamped = clampFloatingPosition(
      { x: state.originX + dx, y: state.originY + dy },
      { width: rect?.width || (minimized ? 46 : 320), height: rect?.height || 46 },
      { width: window.innerWidth, height: window.innerHeight },
      8
    );
    onPositionChange(clamped);
  };

  const handleDragEnd = (e: React.PointerEvent<HTMLElement>) => {
    const state = dragStateRef.current;
    if (!state.active || state.pointerId !== e.pointerId) return;
    if (e.currentTarget.hasPointerCapture(e.pointerId)) {
      e.currentTarget.releasePointerCapture(e.pointerId);
    }
    if (state.moved) {
      suppressClickRef.current = true;
      window.setTimeout(() => {
        suppressClickRef.current = false;
      }, 60);
    }
    dragStateRef.current.active = false;
    dragStateRef.current.moved = false;
    dragStateRef.current.pointerId = null;
    setIsDragging(false);
  };

  const handleToggleCircle = () => {
    if (suppressClickRef.current) {
      suppressClickRef.current = false;
      return;
    }
    setMinimized((prev) => {
      const next = !prev;
      if (next) setExpanded(false);
      return next;
    });
  };

  if (!visible) return null;

  const otpCode = activeMailbox?.lastCode || '';
  const hasOtp = Boolean(otpCode && otpCode !== '----');
  const isOtpCopied = hasOtp && copiedValue === `otp:${otpCode}`;
  const isEmailCopied = Boolean(activeMailbox?.email && copiedValue === `email:${activeMailbox.email}`);
  const displayError = error || (!hasOtp && !activeMailbox?.lastContent ? activeMailbox?.lastError || null : null);
  const openDrawerAbove = position.y > viewportHeight * 0.55;

  return (
    <div
      ref={barRef}
      data-testid="floating-reader-bar"
      data-minimized={minimized ? 'true' : 'false'}
      style={{
        transform: `translate3d(${Math.round(position.x)}px, ${Math.round(position.y)}px, 0)`,
      }}
      className={`fixed top-0 left-0 z-50 flex ${
        openDrawerAbove ? 'flex-col-reverse' : 'flex-col'
      } gap-1.5 max-w-[calc(100vw-16px)] select-none ${
        isDragging ? 'transition-none' : 'transition-shadow duration-150'
      }`}
    >
      {/* Compact Movable Floating Bar OR Minimized Circle */}
      <div
        className={`flex items-center bg-[#1d1b19]/95 backdrop-blur-xl text-white border border-white/15 shadow-[0_14px_38px_rgba(0,0,0,0.38)] ${
          minimized
            ? 'w-12 h-12 rounded-full justify-center p-0'
            : 'gap-1.5 sm:gap-2 px-2.5 py-1.5 rounded-2xl'
        }`}
      >
        {/* 4-Way Move Handle (Drag to move, Click/Tap to toggle Circle mode) */}
        <button
          type="button"
          aria-label={minimized ? 'Expand floating reader bar' : 'Minimize floating reader to circle'}
          aria-expanded={!minimized}
          title={minimized ? 'Click to expand bar · Drag to move' : 'Click to collapse to circle · Drag to move'}
          onPointerDown={handleDragStart}
          onPointerMove={handleDragMove}
          onPointerUp={handleDragEnd}
          onPointerCancel={handleDragEnd}
          onClick={handleToggleCircle}
          style={{ touchAction: 'none' }}
          className={`flex items-center justify-center text-neutral-300 hover:text-white hover:bg-white/10 cursor-grab active:cursor-grabbing shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400 ${
            minimized ? 'w-12 h-12 rounded-full' : 'w-8 h-8 rounded-xl'
          }`}
        >
          <Move className={minimized ? 'w-5 h-5' : 'w-4 h-4'} aria-hidden="true" />
        </button>

        {!minimized && (
          <>
            {/* Latest-Code Email */}
            <button
              type="button"
              disabled={!activeMailbox}
              onClick={() => activeMailbox && onCopyEmail(activeMailbox.email)}
              title={activeMailbox ? `${activeMailbox.email} (click to copy)` : 'No mailbox saved'}
              aria-label={activeMailbox ? `Copy email ${activeMailbox.email}` : 'No active mailbox'}
              className="text-left text-xs sm:text-[13px] font-medium text-neutral-100 hover:text-white max-w-[155px] sm:max-w-[230px] truncate px-1.5 py-1 rounded-md hover:bg-white/10 disabled:opacity-60 disabled:cursor-default whitespace-nowrap focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-400"
            >
              {isEmailCopied
                ? 'Email copied'
                : activeMailbox
                  ? activeMailbox.email
                  : 'No mail saved'}
            </button>

            {/* Lock + OTP Click-to-Copy */}
            <button
              type="button"
              onClick={() => hasOtp && onCopyOtp(otpCode)}
              disabled={!hasOtp}
              aria-label={hasOtp ? `Copy OTP code ${otpCode}` : 'Waiting for OTP code'}
              title={hasOtp ? 'Click to copy OTP code' : 'Auto-reading every 3s for latest code'}
              className={`flex items-center gap-1.5 px-2.5 h-8 rounded-lg font-mono text-xs sm:text-[13px] font-semibold tabular-nums whitespace-nowrap shrink-0 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 ${
                hasOtp
                  ? 'bg-emerald-500/15 hover:bg-emerald-500/25 text-emerald-400 cursor-pointer border border-emerald-400/25'
                  : 'bg-white/5 text-emerald-400/80 cursor-default'
              }`}
            >
              <Lock className="w-3.5 h-3.5 text-amber-400 shrink-0" aria-hidden="true" />
              <span className="text-emerald-400/90">OTP:</span>
              <span data-testid="floating-otp-value" className="text-emerald-300">
                {isOtpCopied ? 'Copied!' : hasOtp ? otpCode : '----'}
              </span>
              {hasOtp && !isOtpCopied && (
                <Copy className="w-3 h-3 text-emerald-300/75 hidden sm:inline shrink-0" aria-hidden="true" />
              )}
              {isOtpCopied && <Check className="w-3.5 h-3.5 text-emerald-300 shrink-0" aria-hidden="true" />}
            </button>

            {/* Expand Latest Mail Preview Toggle */}
            <button
              type="button"
              onClick={() => setExpanded((prev) => !prev)}
              aria-expanded={expanded}
              aria-label={expanded ? 'Collapse latest mail preview' : 'Expand latest mail preview'}
              title="Toggle latest mail preview"
              className="flex items-center justify-center w-8 h-8 rounded-lg text-neutral-400 hover:text-white hover:bg-white/10 shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/60"
            >
              {expanded ? (
                <ChevronUp className="w-4 h-4" aria-hidden="true" />
              ) : (
                <ChevronDown className="w-4 h-4" aria-hidden="true" />
              )}
            </button>
          </>
        )}
      </div>

      {/* Expandable Latest Mail Preview Drawer attached to floating bar */}
      {!minimized && expanded && (
        <div
          data-testid="floating-mail-drawer"
          className="p-3 rounded-2xl bg-[#1d1b19]/95 backdrop-blur-xl text-white border border-white/15 shadow-[0_14px_38px_rgba(0,0,0,0.4)] w-full max-w-[420px]"
        >
          {displayError ? (
            <div className="text-xs text-rose-300 bg-rose-500/15 border border-rose-500/30 rounded-xl px-3 py-2">
              {displayError}
            </div>
          ) : activeMailbox?.lastContent ? (
            <div className="space-y-2">
              <div className="flex items-center justify-between gap-2 text-[11px] text-neutral-400">
                <span className="capitalize font-medium text-neutral-200">
                  {activeMailbox.serviceType} latest mail
                </span>
                <span className="font-mono tabular-nums">{activeMailbox.lastDate || 'Just now'}</span>
              </div>
              <p className="text-xs sm:text-[13px] leading-relaxed text-neutral-100 break-words">
                {activeMailbox.lastContent}
              </p>
              {hasOtp && (
                <button
                  type="button"
                  onClick={() => onCopyOtp(otpCode)}
                  className="w-full flex items-center justify-between px-3 py-2 rounded-xl bg-emerald-500/15 hover:bg-emerald-500/25 border border-emerald-400/25 text-emerald-300 text-xs font-mono font-semibold transition-colors"
                >
                  <span>Code: {otpCode}</span>
                  <span className="font-sans text-[11px] font-medium">
                    {isOtpCopied ? 'Copied to clipboard' : 'Click to copy'}
                  </span>
                </button>
              )}
            </div>
          ) : (
            <p className="text-xs text-neutral-400">
              {activeMailbox
                ? autoReading
                  ? 'Auto-reading saved mailboxes every 3s for incoming verification codes.'
                  : 'Auto-read is paused or finished. Resume auto-read or click Read All below.'
                : 'Save one or more Hotmail OAuth2 accounts in the Mail Box below.'}
            </p>
          )}
        </div>
      )}
    </div>
  );
};
