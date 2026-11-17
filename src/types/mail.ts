export const MAIL_SERVICE_TYPES = [
  'facebook',
  'all',
  'instagram',
  'twitter',
  'apple',
  'tiktok',
  'amazon',
  'lazada',
  'kakaotalk',
  'google',
  'shopee',
  'telegram',
  'wechat',
] as const;

export type MailServiceType = (typeof MAIL_SERVICE_TYPES)[number];

export interface MailboxAccount {
  id: string;
  email: string;
  password?: string;
  refresh_token: string;
  client_id: string;
  serviceType: MailServiceType;
  createdAt: number;
  lastCode?: string;
  lastContent?: string;
  lastDate?: string;
  lastFetchedAt?: number;
  lastCodeReceivedAt?: number;
  isDemo?: boolean;
}

export interface MailReadResponse {
  email: string;
  password?: string;
  status: boolean;
  code?: string;
  content?: string;
  date?: string;
  message?: string;
  error?: string;
}

export interface FloatingPosition {
  x: number;
  y: number;
}
