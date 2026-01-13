#!/usr/bin/env python3
"""
WebSocket 测试客户端
用于测试 SmanAgent 后端的 WebSocket 功能
"""

import asyncio
import websockets
import json
import uuid

async def test_websocket():
    uri = "ws://localhost:8080/ws/agent"

    # 生成测试用的 sessionId 和 projectKey
    session_id = f"test_{uuid.uuid4().hex[:8]}"
    project_key = "test-project"

    print(f"🔗 连接到 WebSocket: {uri}")
    print(f"📝 Session ID: {session_id}")
    print(f"📦 Project Key: {project_key}")
    print()

    try:
        async with websockets.connect(uri) as websocket:
            print("✅ WebSocket 连接成功!")

            # 等待连接确认消息
            response = await websocket.recv()
            data = json.loads(response)
            print(f"📩 收到: {data}")
            print()

            # 测试 1: 发送 ping
            print("=" * 50)
            print("📌 测试 1: 发送 ping")
            ping_msg = {"type": "ping"}
            await websocket.send(json.dumps(ping_msg))
            response = await websocket.recv()
            data = json.loads(response)
            print(f"📩 收到: {data}")
            print()

            # 测试 2: 发送 analyze 请求（创建新会话）
            print("=" * 50)
            print("📌 测试 2: 发送 analyze 请求（创建新会话）")
            analyze_msg = {
                "type": "analyze",
                "sessionId": session_id,
                "projectKey": project_key,
                "input": "你好，请简单介绍一下你自己。"
            }
            await websocket.send(json.dumps(analyze_msg))
            print(f"📤 发送: {analyze_msg['input']}")
            print()

            # 接收响应流
            print("📥 接收响应流:")
            message_count = 0
            while True:
                try:
                    response = await asyncio.wait_for(websocket.recv(), timeout=5.0)
                    data = json.loads(response)
                    msg_type = data.get("type")

                    if msg_type == "part":
                        part = data.get("part", {})
                        part_type = part.get("type", "UNKNOWN")
                        print(f"  [{part_type}] {format_part(part)}")
                        message_count += 1
                    elif msg_type == "complete":
                        print(f"✅ 完成! sessionId: {data.get('sessionId')}")
                        break
                    elif msg_type == "error":
                        print(f"❌ 错误: {data.get('message')}")
                        break
                    else:
                        print(f"📩 收到: {data}")

                except asyncio.TimeoutError:
                    print("⏱️  超时，停止接收")
                    break

            print(f"\n📊 共收到 {message_count} 条消息")

    except websockets.exceptions.WebSocketException as e:
        print(f"❌ WebSocket 错误: {e}")
    except Exception as e:
        print(f"❌ 错误: {e}")
        import traceback
        traceback.print_exc()

def format_part(part):
    """格式化 Part 用于显示"""
    part_type = part.get("type", "")

    if part_type == "TEXT":
        return part.get("text", "")[:50] + ("..." if len(part.get("text", "")) > 50 else "")
    elif part_type == "TOOL":
        tool_name = part.get("toolName", "")
        state = part.get("state", "")
        return f"工具: {tool_name} ({state})"
    elif part_type == "REASONING":
        return part.get("text", "")[:50] + "..."
    else:
        return str(part)

if __name__ == "__main__":
    print("🚀 SmanAgent WebSocket 测试客户端")
    print("=" * 50)
    asyncio.run(test_websocket())
    print()
    print("✅ 测试完成")
