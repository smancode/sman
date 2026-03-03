/**
 * File-based Storage Tests
 */

import { describe, test, expect, beforeEach, afterEach } from "bun:test"
import * as fs from "fs"
import * as path from "path"
import * as os from "os"
import { JsonlSessionStore, JsonlMessageStore, FileMarkdownStore, JsonKeyValueStore } from "./files"

describe("JsonlSessionStore", () => {
  let tempDir: string
  let store: JsonlSessionStore

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "sman-test-"))
    store = new JsonlSessionStore(tempDir)
  })

  afterEach(() => {
    fs.rmSync(tempDir, { recursive: true, force: true })
  })

  test("should create session", async () => {
    const session = await store.create({ projectId: "test-project" })

    expect(session.id).toBeDefined()
    expect(session.projectId).toBe("test-project")
    expect(session.status).toBe("active")
  })

  test("should persist session across restarts", async () => {
    const session = await store.create({ projectId: "test-project", title: "Test Session" })

    // 创建新实例模拟重启
    const newStore = new JsonlSessionStore(tempDir)
    const loaded = await newStore.get(session.id)

    expect(loaded).toBeDefined()
    expect(loaded?.title).toBe("Test Session")
  })

  test("should update session", async () => {
    const session = await store.create({ projectId: "test" })
    const updated = await store.update(session.id, { title: "Updated Title" })

    expect(updated.title).toBe("Updated Title")
  })

  test("should delete session", async () => {
    const session = await store.create({ projectId: "test" })
    await store.delete(session.id)

    const loaded = await store.get(session.id)
    expect(loaded).toBeUndefined()
  })

  test("should list sessions", async () => {
    await store.create({ projectId: "project-a" })
    await store.create({ projectId: "project-a" })
    await store.create({ projectId: "project-b" })

    const listA = await store.list("project-a")
    expect(listA).toHaveLength(2)

    const listB = await store.list("project-b")
    expect(listB).toHaveLength(1)
  })
})

describe("JsonlMessageStore", () => {
  let tempDir: string
  let store: JsonlMessageStore
  let sessionId: string

  beforeEach(async () => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "sman-test-"))
    const sessionStore = new JsonlSessionStore(tempDir)
    const session = await sessionStore.create({ projectId: "test" })
    sessionId = session.id
    store = new JsonlMessageStore(tempDir)
  })

  afterEach(() => {
    fs.rmSync(tempDir, { recursive: true, force: true })
  })

  test("should append message", async () => {
    const message = await store.append(sessionId, {
      role: "user",
      parts: [{ type: "text", text: "Hello" }],
    })

    expect(message.id).toBeDefined()
    expect(message.role).toBe("user")
    expect(message.sessionId).toBe(sessionId)
  })

  test("should persist messages across restarts", async () => {
    await store.append(sessionId, {
      role: "user",
      parts: [{ type: "text", text: "Hello" }],
    })

    // 创建新实例模拟重启
    const newStore = new JsonlMessageStore(tempDir)
    const history = await newStore.getHistory(sessionId)

    expect(history).toHaveLength(1)
    expect(history[0].parts[0]).toEqual({ type: "text", text: "Hello" })
  })

  test("should get message history", async () => {
    await store.append(sessionId, { role: "user", parts: [{ type: "text", text: "Hi" }] })
    await store.append(sessionId, { role: "assistant", parts: [{ type: "text", text: "Hello!" }] })

    const history = await store.getHistory(sessionId)
    expect(history).toHaveLength(2)
    expect(history[0].role).toBe("user")
    expect(history[1].role).toBe("assistant")
  })

  test("should limit history", async () => {
    for (let i = 0; i < 10; i++) {
      await store.append(sessionId, { role: "user", parts: [{ type: "text", text: `Msg ${i}` }] })
    }

    const history = await store.getHistory(sessionId, { limit: 5 })
    expect(history).toHaveLength(5)
    expect(history[0].parts[0]).toMatchObject({ text: "Msg 5" })
  })

  test("should update message", async () => {
    const msg = await store.append(sessionId, {
      role: "assistant",
      parts: [{ type: "text", text: "Original" }],
    })

    const updated = await store.update(msg.id, {
      parts: [{ type: "text", text: "Updated" }],
    })

    expect(updated.parts[0]).toMatchObject({ text: "Updated" })
  })

  test("should delete message", async () => {
    const msg = await store.append(sessionId, {
      role: "user",
      parts: [{ type: "text", text: "To delete" }],
    })

    await store.delete(msg.id)
    const history = await store.getHistory(sessionId)

    expect(history).toHaveLength(0)
  })
})

describe("FileMarkdownStore", () => {
  let tempDir: string
  let store: FileMarkdownStore

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "sman-test-"))
    store = new FileMarkdownStore(tempDir)
  })

  afterEach(() => {
    fs.rmSync(tempDir, { recursive: true, force: true })
  })

  test("should write and read markdown file", async () => {
    const filePath = path.join(tempDir, "test.md")
    await store.write(filePath, {
      content: "# Hello\n\nThis is test content.",
      metadata: { title: "Test Doc" },
    })

    const doc = await store.read(filePath)

    expect(doc.content).toBe("# Hello\n\nThis is test content.")
    expect(doc.metadata.title).toBe("Test Doc")
  })

  test("should handle frontmatter", async () => {
    const filePath = path.join(tempDir, "frontmatter.md")
    await store.write(filePath, {
      content: "Body content",
      metadata: {
        title: "Title",
        tags: ["tag1", "tag2"],
      },
    })

    const doc = await store.read(filePath)

    expect(doc.metadata.title).toBe("Title")
    expect(doc.metadata.tags).toEqual(["tag1", "tag2"])
  })

  test("should search markdown files", async () => {
    // 创建多个文件
    await store.write(path.join(tempDir, "doc1.md"), {
      content: "This contains keyword: apple",
    })
    await store.write(path.join(tempDir, "doc2.md"), {
      content: "This contains keyword: banana",
    })
    await store.write(path.join(tempDir, "doc3.md"), {
      content: "No fruit here",
    })

    const results = await store.search("apple")

    expect(results).toHaveLength(1)
    expect(results[0].content).toContain("apple")
  })
})

describe("JsonKeyValueStore", () => {
  let tempDir: string
  let store: JsonKeyValueStore
  let filePath: string

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "sman-test-"))
    filePath = path.join(tempDir, "kv.json")
    store = new JsonKeyValueStore(filePath)
  })

  afterEach(() => {
    fs.rmSync(tempDir, { recursive: true, force: true })
  })

  test("should set and get value", async () => {
    await store.set("key1", "value1")
    const value = await store.get("key1")

    expect(value).toBe("value1")
  })

  test("should persist across restarts", async () => {
    await store.set("persistent", "yes")

    const newStore = new JsonKeyValueStore(filePath)
    const value = await newStore.get("persistent")

    expect(value).toBe("yes")
  })

  test("should delete value", async () => {
    await store.set("temp", "value")
    await store.delete("temp")

    const value = await store.get("temp")
    expect(value).toBeUndefined()
  })

  test("should check existence", async () => {
    await store.set("exists", true)

    expect(await store.has("exists")).toBe(true)
    expect(await store.has("not-exists")).toBe(false)
  })

  test("should list keys with pattern", async () => {
    await store.set("user:1", "a")
    await store.set("user:2", "b")
    await store.set("session:1", "c")

    const userKeys = await store.keys("user:*")

    expect(userKeys).toHaveLength(2)
    expect(userKeys).toContain("user:1")
    expect(userKeys).toContain("user:2")
  })
})
