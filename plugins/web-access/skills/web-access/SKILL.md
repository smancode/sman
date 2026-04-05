---
name: web-access
description: Use when user wants to browse websites, operate internal systems (ITSM, Jira, Confluence, GitLab, Jenkins, etc.), check todos, fill forms, or perform any task requiring a real browser. Trigger: 查看待办, 操作网站, 打开网页, 浏览器, 看下xxx, 帮我查, 网页, browse, check website, open page, web access.
---

# Web Access — Browser Operation Skill

You have access to browser tools via the `web_access_*` MCP tools. These tools let you operate a real browser (user's Chrome) to interact with websites, especially internal enterprise systems.

## When to Use

Use web_access tools when the user asks you to:
- View or operate internal websites (ITSM, Jira, Confluence, GitLab, Jenkins, wiki, etc.)
- Check todos, tickets, tasks on web platforms
- Fill forms, click buttons, navigate pages
- Read web page content that requires authentication
- Any task that involves "looking at a website" or "operating a webpage"

## Available Tools

| Tool | Purpose |
|------|---------|
| `web_access_find_url` | Find best matching URL from Chrome history + learned experiences |
| `web_access_remember_url` | Save a URL mapping for future smart matching |
| `web_access_navigate` | Open a URL in the browser (creates a tab if needed) |
| `web_access_snapshot` | Get page text content (accessibility tree) |
| `web_access_screenshot` | Take a screenshot of the current page |
| `web_access_click` | Click an element by CSS selector |
| `web_access_fill` | Fill a form field with a value |
| `web_access_press_key` | Press a key (Enter, Tab, Escape, etc.) |
| `web_access_evaluate` | Execute JavaScript and get the result |
| `web_access_list_tabs` | List all open browser tabs |
| `web_access_close_tab` | Close a browser tab |

## Smart URL Matching

When you need to navigate but are unsure of the exact URL:

1. Call `web_access_find_url(query="智谱MCP用量")` with the user's original question
2. Review the returned **experiences** (previously saved) and **chromeHistory** entries
3. Pick the best matching URL — you are the matching engine, use your semantic understanding
4. If a good match found → use it with `web_access_navigate`
5. If nothing matches → ask the user for the URL
6. After user provides URL → `web_access_remember_url(description="智谱MCP用量页面", url="https://...")`
7. Next time `find_url` will match it automatically from experiences

## Workflow

### Step 1: Navigate to the target page

```
web_access_navigate(session_id="current-session", url="https://itsm.company.com/my-todos")
```

If the result shows `isLoginPage: true`, tell the user:
"检测到登录页面，请先在浏览器中登录后，再告诉我继续操作。"

### Step 2: Read the page content

```
web_access_snapshot(tab_id="<returned-tab-id>")
```

The `accessibilityTree` field contains the page's text content. Parse it to extract the information the user needs.

### Step 3: If you need to interact (click, fill)

- Use `web_access_snapshot` first to understand the page structure
- Identify elements by their visible text or semantic structure
- Use CSS selectors to target elements
- After each action, `web_access_snapshot` again to see the result

### Step 4: Summarize for the user

Present the results in clear Chinese. For example:

"你在 ITSM 上有 3 个待办：
1. 【网络故障】办公区网络断连 - 紧急 - 创建于 3月28日
2. 【权限申请】VPN 权限开通 - 普通 - 创建于 3月25日
3. 【设备申请】新员工工位配置 - 低 - 创建于 3月20日"

## Operation Experience Learning

After **successfully** completing a web operation task (form submit, data query, ticket creation, etc.), save the operation experience to the project's skills directory so the team can reuse it.

### Where to Save

Save to `{workspace}/.claude/skills/{system-name}/skill.md`, where `{system-name}` is the website's short name (e.g., `itsm`, `jira`, `confluence`).

### How to Save

**First time** — create new skill file:

```markdown
---
name: {system-name}
description: "{system Chinese name} 操作经验"
---

# {system Chinese name} 操作经验

## 站点
- URL: https://itsm.company.com

## 场景

### 提交工单
- URL: https://itsm.company.com/ticket/new
- 成功次数: 1
- 步骤:
  1. 点击「新建工单」 → `#create-btn`
  2. 选择工单类型「硬件故障」 → `#type-select`
  3. 填写标题 → `input[name="title"]`
  4. 填写描述 → `textarea[name="description"]`
  5. 点击提交 → `button[type="submit"]`

### 查看待办
- URL: https://itsm.company.com/my-todos
- 成功次数: 1
- 步骤:
  1. 导航到待办页面 → snapshot 读取列表
```

**Already exists** — append new scenario or update existing one:
- New scenario → add a new `###` section under `## 场景`
- Existing scenario → update `成功次数 +1`, fix selectors if changed

### When to Save

- Only save after **successful** operations (user confirmed or result verified)
- Record the CSS selectors that actually worked
- If a previous selector failed but you found a working one, update it

### When to Load

Before starting a web operation, check if `{workspace}/.claude/skills/{system-name}/skill.md` exists. If it does:
- Read the matching scenario
- Try the recorded selectors directly (skip full snapshot if confidence is high)
- If selectors fail → fall back to normal snapshot + analyze flow → update the skill file with new selectors

## Important Notes

- You share the user's Chrome session — cookies and login state are reused
- If a page requires login, do NOT attempt to enter credentials. Ask the user to log in manually
- Use `web_access_evaluate` only when `web_access_snapshot` cannot provide the needed data
- Always use `web_access_navigate` with the same `session_id` for tab reuse within a conversation
- The system automatically waits for pages to load (DOM stability detection)
