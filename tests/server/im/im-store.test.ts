import { describe, it, expect, beforeEach } from 'vitest';
import Database from 'better-sqlite3';
import { IMStore, type IMMessage, type IMRoom } from '../../../server/im/im-store.js';

function createTestDB(): Database.Database {
  const db = new Database(':memory:');
  db.exec(`
    CREATE TABLE IF NOT EXISTS im_messages (
      id TEXT PRIMARY KEY,
      room_id TEXT NOT NULL,
      sender TEXT NOT NULL,
      content TEXT NOT NULL,
      mentioned_agents TEXT,
      quote_id TEXT,
      type TEXT NOT NULL DEFAULT 'text',
      status TEXT DEFAULT NULL,
      attachments TEXT,
      session_id TEXT,
      timestamp INTEGER NOT NULL,
      created_at DATETIME DEFAULT (datetime('now', 'localtime')),
      updated_at DATETIME DEFAULT (datetime('now', 'localtime'))
    );
    CREATE INDEX IF NOT EXISTS idx_im_messages_room_ts ON im_messages(room_id, timestamp);
    CREATE INDEX IF NOT EXISTS idx_im_messages_sender ON im_messages(sender);

    CREATE TABLE IF NOT EXISTS im_rooms (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      type TEXT NOT NULL DEFAULT 'group',
      members TEXT NOT NULL,
      last_message TEXT,
      last_message_time INTEGER,
      created_at DATETIME DEFAULT (datetime('now', 'localtime'))
    );
  `);
  return db;
}

function makeMessage(overrides: Partial<IMMessage> = {}): IMMessage {
  return {
    id: `msg-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    roomId: 'room-1',
    sender: 'user',
    content: 'hello',
    mentionedAgents: [],
    type: 'text',
    timestamp: Date.now(),
    ...overrides,
  };
}

describe('IMStore', () => {
  let db: Database.Database;
  let store: IMStore;

  beforeEach(() => {
    db = createTestDB();
    store = new IMStore(db);
  });

  // === Message CRUD ===

  describe('insertMessage + getMessage', () => {
    it('should insert and retrieve a message', () => {
      const msg = makeMessage({ id: 'msg-1' });
      store.insertMessage(msg);
      const fetched = store.getMessage('msg-1');
      expect(fetched).toBeDefined();
      expect(fetched!.id).toBe('msg-1');
      expect(fetched!.roomId).toBe(msg.roomId);
      expect(fetched!.sender).toBe(msg.sender);
      expect(fetched!.content).toBe(msg.content);
      expect(fetched!.type).toBe('text');
      expect(fetched!.timestamp).toBe(msg.timestamp);
    });

    it('should parse JSON fields correctly', () => {
      const msg = makeMessage({
        id: 'msg-json',
        mentionedAgents: ['agent-1', 'agent-2'],
        attachments: [{ type: 'file', name: 'test.txt' }],
      });
      store.insertMessage(msg);
      const fetched = store.getMessage('msg-json');
      expect(fetched!.mentionedAgents).toEqual(['agent-1', 'agent-2']);
      expect(fetched!.attachments).toEqual([{ type: 'file', name: 'test.txt' }]);
    });

    it('should return undefined for non-existent message', () => {
      expect(store.getMessage('non-existent')).toBeUndefined();
    });
  });

  describe('INSERT OR IGNORE dedup', () => {
    it('should not throw on duplicate id insert', () => {
      const msg = makeMessage({ id: 'msg-dup' });
      store.insertMessage(msg);
      expect(() => store.insertMessage(msg)).not.toThrow();
    });

    it('should still have only 1 row after duplicate insert', () => {
      const msg = makeMessage({ id: 'msg-dup' });
      store.insertMessage(msg);
      store.insertMessage(msg);
      const fetched = store.getMessage('msg-dup');
      expect(fetched).toBeDefined();
      // Verify only one row exists
      const count = (db.prepare('SELECT COUNT(*) as c FROM im_messages WHERE id = ?').get('msg-dup') as { c: number }).c;
      expect(count).toBe(1);
    });
  });

  describe('getMessagesByRoom', () => {
    it('should return messages ordered by timestamp ASC', () => {
      store.insertMessage(makeMessage({ id: 'm1', roomId: 'room-1', timestamp: 100 }));
      store.insertMessage(makeMessage({ id: 'm3', roomId: 'room-1', timestamp: 300 }));
      store.insertMessage(makeMessage({ id: 'm2', roomId: 'room-1', timestamp: 200 }));

      const messages = store.getMessagesByRoom('room-1', { limit: 10 });
      expect(messages).toHaveLength(3);
      expect(messages[0].id).toBe('m1');
      expect(messages[1].id).toBe('m2');
      expect(messages[2].id).toBe('m3');
    });

    it('should filter by room_id only', () => {
      store.insertMessage(makeMessage({ id: 'r1-m1', roomId: 'room-1', timestamp: 100 }));
      store.insertMessage(makeMessage({ id: 'r2-m1', roomId: 'room-2', timestamp: 150 }));
      store.insertMessage(makeMessage({ id: 'r1-m2', roomId: 'room-1', timestamp: 200 }));

      const messages = store.getMessagesByRoom('room-1', { limit: 10 });
      expect(messages).toHaveLength(2);
      expect(messages.every(m => m.roomId === 'room-1')).toBe(true);
    });

    it('should respect before cursor', () => {
      store.insertMessage(makeMessage({ id: 'm1', roomId: 'room-1', timestamp: 100 }));
      store.insertMessage(makeMessage({ id: 'm2', roomId: 'room-1', timestamp: 200 }));
      store.insertMessage(makeMessage({ id: 'm3', roomId: 'room-1', timestamp: 300 }));

      const messages = store.getMessagesByRoom('room-1', { before: 250, limit: 10 });
      expect(messages).toHaveLength(2);
      expect(messages[0].id).toBe('m1');
      expect(messages[1].id).toBe('m2');
    });

    it('should respect limit', () => {
      for (let i = 0; i < 5; i++) {
        store.insertMessage(makeMessage({ id: `m${i}`, roomId: 'room-1', timestamp: 100 + i }));
      }
      const messages = store.getMessagesByRoom('room-1', { limit: 3 });
      expect(messages).toHaveLength(3);
    });
  });

  describe('getMessagesBefore', () => {
    it('should return messages before cursor in ASC order', () => {
      store.insertMessage(makeMessage({ id: 'm1', roomId: 'room-1', timestamp: 100 }));
      store.insertMessage(makeMessage({ id: 'm2', roomId: 'room-1', timestamp: 200 }));
      store.insertMessage(makeMessage({ id: 'm3', roomId: 'room-1', timestamp: 300 }));
      store.insertMessage(makeMessage({ id: 'm4', roomId: 'room-1', timestamp: 400 }));

      const messages = store.getMessagesBefore('room-1', 350, 2);
      expect(messages).toHaveLength(2);
      // ASC order even though query is DESC
      expect(messages[0].id).toBe('m2');
      expect(messages[1].id).toBe('m3');
    });

    it('should return empty when no messages before cursor', () => {
      store.insertMessage(makeMessage({ id: 'm1', roomId: 'room-1', timestamp: 100 }));
      const messages = store.getMessagesBefore('room-1', 50, 10);
      expect(messages).toHaveLength(0);
    });
  });

  describe('updateMessageStatus', () => {
    it('should update status field', () => {
      store.insertMessage(makeMessage({ id: 'msg-status', status: 'running' }));
      store.updateMessageStatus('msg-status', 'completed');
      const fetched = store.getMessage('msg-status');
      expect(fetched!.status).toBe('completed');
    });

    it('should update status to failed', () => {
      store.insertMessage(makeMessage({ id: 'msg-fail', status: 'running' }));
      store.updateMessageStatus('msg-fail', 'failed');
      const fetched = store.getMessage('msg-fail');
      expect(fetched!.status).toBe('failed');
    });
  });

  // === Room CRUD ===

  describe('createRoom + getRoom', () => {
    it('should create and retrieve a room', () => {
      const room: IMRoom = {
        id: 'room-1',
        name: 'Test Room',
        type: 'group',
        members: ['user-1', 'agent-1'],
      };
      store.createRoom(room);
      const fetched = store.getRoom('room-1');
      expect(fetched).toBeDefined();
      expect(fetched!.id).toBe('room-1');
      expect(fetched!.name).toBe('Test Room');
      expect(fetched!.type).toBe('group');
      expect(fetched!.members).toEqual(['user-1', 'agent-1']);
    });

    it('should return undefined for non-existent room', () => {
      expect(store.getRoom('non-existent')).toBeUndefined();
    });

    it('should not throw on duplicate id insert', () => {
      const room: IMRoom = {
        id: 'room-dup',
        name: 'Dup Room',
        type: 'group',
        members: ['user-1'],
      };
      store.createRoom(room);
      expect(() => store.createRoom(room)).not.toThrow();
    });
  });

  describe('listRooms', () => {
    it('should return rooms ordered by last_message_time DESC', () => {
      store.createRoom({ id: 'r1', name: 'Room 1', type: 'group', members: ['u1'], lastMessageTime: 100 });
      store.createRoom({ id: 'r2', name: 'Room 2', type: 'group', members: ['u2'], lastMessageTime: 300 });
      store.createRoom({ id: 'r3', name: 'Room 3', type: 'group', members: ['u3'], lastMessageTime: 200 });

      const rooms = store.listRooms();
      expect(rooms).toHaveLength(3);
      expect(rooms[0].id).toBe('r2');
      expect(rooms[1].id).toBe('r3');
      expect(rooms[2].id).toBe('r1');
    });

    it('should return empty array when no rooms', () => {
      expect(store.listRooms()).toHaveLength(0);
    });
  });

  describe('updateRoomLastMessage', () => {
    it('should update last message preview and time', () => {
      store.createRoom({ id: 'room-1', name: 'Room 1', type: 'group', members: ['u1'] });
      store.updateRoomLastMessage('room-1', 'Hello world', 1234567890);

      const room = store.getRoom('room-1');
      expect(room!.lastMessage).toBe('Hello world');
      expect(room!.lastMessageTime).toBe(1234567890);
    });

    it('should overwrite previous last message', () => {
      store.createRoom({ id: 'room-1', name: 'Room 1', type: 'group', members: ['u1'] });
      store.updateRoomLastMessage('room-1', 'First', 100);
      store.updateRoomLastMessage('room-1', 'Second', 200);

      const room = store.getRoom('room-1');
      expect(room!.lastMessage).toBe('Second');
      expect(room!.lastMessageTime).toBe(200);
    });
  });
});
