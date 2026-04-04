/**
 * WeChat Personal Bot (iLink Bot API) Types
 * Extracted from @tencent-weixin/openclaw-weixin for Sman integration.
 */

// ── Enums / Constants ──

export const MessageItemType = {
  NONE: 0,
  TEXT: 1,
  IMAGE: 2,
  VOICE: 3,
  FILE: 4,
  VIDEO: 5,
} as const;

export const MessageType = {
  NONE: 0,
  USER: 1,
  BOT: 2,
} as const;

export const MessageState = {
  NEW: 0,
  GENERATING: 1,
  FINISH: 2,
} as const;

export const SESSION_EXPIRED_ERRCODE = -14;

// ── Message Types ──

export interface TextItem {
  text?: string;
}

export interface ImageItem {
  url?: string;
  width?: number;
  height?: number;
  thumb_url?: string;
  thumb_width?: number;
  thumb_height?: number;
  md5?: string;
  data_size?: number;
}

export interface VoiceItem {
  url?: string;
  duration_ms?: number;
  md5?: string;
  data_size?: number;
  recognition?: string; // voice-to-text transcription
}

export interface FileItem {
  url?: string;
  file_name?: string;
  md5?: string;
  data_size?: number;
}

export interface VideoItem {
  url?: string;
  width?: number;
  height?: number;
  duration_ms?: number;
  md5?: string;
  data_size?: number;
  thumb_url?: string;
  thumb_width?: number;
  thumb_height?: number;
}

export interface MessageItem {
  type?: number;
  create_time_ms?: number;
  update_time_ms?: number;
  is_completed?: boolean;
  msg_id?: string;
  text_item?: TextItem;
  image_item?: ImageItem;
  voice_item?: VoiceItem;
  file_item?: FileItem;
  video_item?: VideoItem;
}

export interface WeixinMessage {
  seq?: number;
  message_id?: number;
  from_user_id?: string;
  to_user_id?: string;
  client_id?: string;
  create_time_ms?: number;
  update_time_ms?: number;
  delete_time_ms?: number;
  session_id?: string;
  group_id?: string;
  message_type?: number;
  message_state?: number;
  item_list?: MessageItem[];
  context_token?: string;
}

// ── API Request / Response ──

export interface BaseInfo {
  channel_version?: string;
}

export interface GetUpdatesReq {
  get_updates_buf?: string;
}

export interface GetUpdatesResp {
  ret?: number;
  errcode?: number;
  errmsg?: string;
  msgs?: WeixinMessage[];
  get_updates_buf?: string;
  longpolling_timeout_ms?: number;
}

export interface SendMessageReq {
  msg?: WeixinMessage;
}

// ── QR Login ──

export interface QRCodeResponse {
  qrcode?: string;
  qrcode_img_content?: string;
}

export type QRCodeStatus =
  | 'wait'
  | 'scaned'
  | 'scaned_but_redirect'
  | 'confirmed'
  | 'expired';

export interface QRCodeStatusResponse {
  status?: QRCodeStatus;
  bot_token?: string;
  ilink_bot_id?: string;
  baseurl?: string;
  ilink_user_id?: string;
  redirect_host?: string;
}

// ── Account Persistence ──

export interface WeixinAccountData {
  token?: string;
  baseUrl?: string;
  userId?: string;
  savedAt?: string;
}

// ── Connection Types ──

export type WeixinConnectionStatus = 'idle' | 'connecting' | 'connected' | 'disconnected';

export interface WeixinQrStartResult {
  qrcodeUrl: string;
  sessionKey: string;
}

export interface WeixinQrWaitResult {
  connected: boolean;
  qrStatus?: QRCodeStatus;
  botToken?: string;
  accountId?: string;
  baseUrl?: string;
  userId?: string;
  message: string;
}
