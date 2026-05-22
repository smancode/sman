import Database from 'better-sqlite3';

export interface IMMessage {
  id: string;
  roomId: string;
  sender: string;
  content: string;
  mentionedAgents: string[];
  quoteId?: string;
  type: 'text' | 'agent_output' | 'system';
  status?: 'running' | 'completed' | 'failed';
  attachments?: any[];
  sessionId?: string;
  timestamp: number;
}

export interface IMRoom {
  id: string;
  name: string;
  type: 'group' | 'dm' | 'workspace';
  members: string[];
  lastMessage?: string;
  lastMessageTime?: number;
}

interface IMMessageRow {
  id: string;
  room_id: string;
  sender: string;
  content: string;
  mentioned_agents: string | null;
  quote_id: string | null;
  type: string;
  status: string | null;
  attachments: string | null;
  session_id: string | null;
  timestamp: number;
}

interface IMRoomRow {
  id: string;
  name: string;
  type: string;
  members: string;
  last_message: string | null;
  last_message_time: number | null;
}

function parseMessageRow(row: IMMessageRow): IMMessage {
  return {
    id: row.id,
    roomId: row.room_id,
    sender: row.sender,
    content: row.content,
    mentionedAgents: row.mentioned_agents ? JSON.parse(row.mentioned_agents) : [],
    quoteId: row.quote_id ?? undefined,
    type: row.type as IMMessage['type'],
    status: (row.status as IMMessage['status']) ?? undefined,
    attachments: row.attachments ? JSON.parse(row.attachments) : undefined,
    sessionId: row.session_id ?? undefined,
    timestamp: row.timestamp,
  };
}

function parseRoomRow(row: IMRoomRow): IMRoom {
  return {
    id: row.id,
    name: row.name,
    type: row.type as IMRoom['type'],
    members: JSON.parse(row.members),
    lastMessage: row.last_message ?? undefined,
    lastMessageTime: row.last_message_time ?? undefined,
  };
}

export class IMStore {
  constructor(private db: Database.Database) {}

  insertMessage(msg: IMMessage): void {
    this.db.prepare(`
      INSERT OR IGNORE INTO im_messages
        (id, room_id, sender, content, mentioned_agents, quote_id, type, status, attachments, session_id, timestamp)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      msg.id,
      msg.roomId,
      msg.sender,
      msg.content,
      JSON.stringify(msg.mentionedAgents),
      msg.quoteId ?? null,
      msg.type,
      msg.status ?? null,
      msg.attachments ? JSON.stringify(msg.attachments) : null,
      msg.sessionId ?? null,
      msg.timestamp,
    );
  }

  getMessage(id: string): IMMessage | undefined {
    const row = this.db.prepare(
      'SELECT id, room_id, sender, content, mentioned_agents, quote_id, type, status, attachments, session_id, timestamp FROM im_messages WHERE id = ?',
    ).get(id) as IMMessageRow | undefined;
    return row ? parseMessageRow(row) : undefined;
  }

  getMessagesByRoom(roomId: string, options: { before?: number; limit: number }): IMMessage[] {
    let sql = 'SELECT id, room_id, sender, content, mentioned_agents, quote_id, type, status, attachments, session_id, timestamp FROM im_messages WHERE room_id = ?';
    const params: (string | number)[] = [roomId];

    if (options.before !== undefined) {
      sql += ' AND timestamp < ?';
      params.push(options.before);
    }

    sql += ' ORDER BY timestamp ASC LIMIT ?';
    params.push(options.limit);

    const rows = this.db.prepare(sql).all(...params) as IMMessageRow[];
    return rows.map(parseMessageRow);
  }

  getMessagesBefore(roomId: string, beforeTimestamp: number, limit: number): IMMessage[] {
    const rows = this.db.prepare(
      'SELECT id, room_id, sender, content, mentioned_agents, quote_id, type, status, attachments, session_id, timestamp FROM im_messages WHERE room_id = ? AND timestamp < ? ORDER BY timestamp DESC LIMIT ?',
    ).all(roomId, beforeTimestamp, limit) as IMMessageRow[];
    // Return in ASC order (reversed from DESC query)
    return rows.reverse().map(parseMessageRow);
  }

  updateMessageStatus(id: string, status: string): void {
    this.db.prepare(
      "UPDATE im_messages SET status = ?, updated_at = datetime('now','localtime') WHERE id = ?",
    ).run(status, id);
  }

  updateMessageContent(id: string, content: string): void {
    this.db.prepare(
      "UPDATE im_messages SET content = ?, updated_at = datetime('now','localtime') WHERE id = ?",
    ).run(content, id);
  }

  createRoom(room: IMRoom): void {
    this.db.prepare(`
      INSERT OR IGNORE INTO im_rooms (id, name, type, members, last_message, last_message_time)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(
      room.id,
      room.name,
      room.type,
      JSON.stringify(room.members),
      room.lastMessage ?? null,
      room.lastMessageTime ?? null,
    );
  }

  getRoom(id: string): IMRoom | undefined {
    const row = this.db.prepare(
      'SELECT id, name, type, members, last_message, last_message_time FROM im_rooms WHERE id = ?',
    ).get(id) as IMRoomRow | undefined;
    return row ? parseRoomRow(row) : undefined;
  }

  listRooms(): IMRoom[] {
    const rows = this.db.prepare(
      'SELECT id, name, type, members, last_message, last_message_time FROM im_rooms ORDER BY last_message_time DESC',
    ).all() as IMRoomRow[];
    return rows.map(parseRoomRow);
  }

  updateRoomLastMessage(roomId: string, preview: string, time: number): void {
    this.db.prepare(
      'UPDATE im_rooms SET last_message = ?, last_message_time = ? WHERE id = ?',
    ).run(preview, time, roomId);
  }

  updateRoomMembers(roomId: string, members: string[]): void {
    this.db.prepare(
      'UPDATE im_rooms SET members = ? WHERE id = ?',
    ).run(JSON.stringify(members), roomId);
  }
}
