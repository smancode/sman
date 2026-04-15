// src/features/bazaar/components/NetworkSandbox.tsx
// 中央网络沙盘 — 分身网络拓扑可视化
// 纯 SVG + CSS 动画，不依赖外部库
// 中心: 用户Agent → 辐射: 任务节点 + 协作者节点

import { useBazaarStore } from '@/stores/bazaar';
import { useChatStore } from '@/stores/chat';
import { useMemo } from 'react';

interface GraphNode {
  id: string;
  label: string;
  type: 'self' | 'task' | 'agent';
  x: number;
  y: number;
  color: string;
  glow: string;
  status?: string;
  radius: number;
}

interface GraphLink {
  source: string;
  target: string;
  color: string;
  animated: boolean;
}

function layoutNodes(
  width: number,
  height: number,
  selfId: string | undefined,
  tasks: Array<{ taskId: string; direction: string; status: string; helperName?: string; requesterName?: string; question: string }>,
  agents: Array<{ agentId: string; name: string; status: string; reputation: number }>,
): { nodes: GraphNode[]; links: GraphLink[] } {
  const nodes: GraphNode[] = [];
  const links: GraphLink[] = [];
  const cx = width / 2;
  const cy = height / 2;

  // Self node at center
  nodes.push({
    id: 'self',
    label: '我的分身',
    type: 'self',
    x: cx,
    y: cy,
    color: 'var(--bz-cyan)',
    glow: 'var(--bz-cyan-glow)',
    radius: 20,
  });

  // Task nodes in inner ring
  const activeTasks = tasks.filter(t => ['searching', 'offered', 'matched', 'chatting'].includes(t.status));
  const taskCount = activeTasks.length;
  const taskRadius = Math.min(width, height) * 0.25;

  activeTasks.forEach((task, i) => {
    const angle = (2 * Math.PI * i) / Math.max(taskCount, 1) - Math.PI / 2;
    const tx = cx + Math.cos(angle) * taskRadius;
    const ty = cy + Math.sin(angle) * taskRadius;
    const isActive = task.status === 'chatting' || task.status === 'matched';

    const taskColor = task.direction === 'outgoing'
      ? (isActive ? 'var(--bz-green)' : 'var(--bz-amber)')
      : isActive ? 'var(--bz-blue)' : 'var(--bz-purple)';
    const taskGlow = task.direction === 'outgoing'
      ? (isActive ? 'var(--bz-green-glow)' : 'var(--bz-amber-glow)')
      : isActive ? 'var(--bz-blue)' : 'var(--bz-purple)';

    nodes.push({
      id: task.taskId,
      label: task.question.slice(0, 12),
      type: 'task',
      x: tx,
      y: ty,
      color: taskColor,
      glow: taskGlow,
      status: task.status,
      radius: 10,
    });

    links.push({
      source: 'self',
      target: task.taskId,
      color: taskColor,
      animated: isActive,
    });
  });

  // Agent nodes in outer ring
  const otherAgents = agents.filter(a => a.agentId !== selfId).slice(0, 8);
  const agentRadius = Math.min(width, height) * 0.42;

  otherAgents.forEach((agent, i) => {
    const angle = (2 * Math.PI * i) / Math.max(otherAgents.length, 1) - Math.PI / 2;
    const ax = cx + Math.cos(angle) * agentRadius;
    const ay = cy + Math.sin(angle) * agentRadius;

    const agentColor = agent.status === 'idle' ? 'var(--bz-green)' : agent.status === 'busy' ? 'var(--bz-amber)' : 'var(--bz-text-dim)';

    nodes.push({
      id: agent.agentId,
      label: agent.name,
      type: 'agent',
      x: ax,
      y: ay,
      color: agentColor,
      glow: agentColor,
      status: agent.status,
      radius: 8,
    });

    // Link to center
    links.push({
      source: 'self',
      target: agent.agentId,
      color: 'var(--bz-border)',
      animated: false,
    });
  });

  return { nodes, links };
}

export function NetworkSandbox() {
  const { tasks, onlineAgents, connection } = useBazaarStore();
  const sending = useChatStore((s) => s.sending);

  const width = 800;
  const height = 400;

  const { nodes, links } = useMemo(
    () => layoutNodes(width, height, connection.agentId, tasks, onlineAgents),
    [tasks, onlineAgents, connection.agentId],
  );

  const selfNode = nodes.find(n => n.id === 'self');

  return (
    <div className="w-full h-full flex items-center justify-center relative overflow-hidden" style={{ background: 'var(--bz-bg)' }}>
      {/* Grid background */}
      <div className="absolute inset-0 opacity-10" style={{
        backgroundImage: `
          linear-gradient(var(--bz-border) 1px, transparent 1px),
          linear-gradient(90deg, var(--bz-border) 1px, transparent 1px)
        `,
        backgroundSize: '40px 40px',
      }} />

      <svg width="100%" height="100%" viewBox={`0 0 ${width} ${height}`} className="relative">
        <defs>
          {/* Glow filter */}
          <filter id="glow" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="3" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
          <filter id="glow-strong" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="6" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>

          {/* Animated dash for active links */}
          <style>{`
            .link-animated {
              stroke-dasharray: 5 5;
              animation: bz-link-pulse 1s linear infinite;
            }
          `}</style>
        </defs>

        {/* Links */}
        {links.map((link, i) => {
          const source = nodes.find(n => n.id === link.source);
          const target = nodes.find(n => n.id === link.target);
          if (!source || !target) return null;
          return (
            <line
              key={`link-${i}`}
              x1={source.x} y1={source.y}
              x2={target.x} y2={target.y}
              stroke={link.color}
              strokeWidth={link.animated ? 2 : 1}
              opacity={link.animated ? 0.8 : 0.3}
              className={link.animated ? 'link-animated' : ''}
              filter={link.animated ? 'url(#glow)' : undefined}
            />
          );
        })}

        {/* Nodes */}
        {nodes.map((node) => (
          <g key={node.id}>
            {/* Outer glow */}
            <circle
              cx={node.x} cy={node.y}
              r={node.radius + 4}
              fill="none"
              stroke={node.color}
              strokeWidth={1}
              opacity={0.3}
              filter="url(#glow)"
              style={{
                animation: node.type === 'self' ? 'bz-node-breathe 3s ease-in-out infinite' : undefined,
              }}
            />
            {/* Node body */}
            <circle
              cx={node.x} cy={node.y}
              r={node.radius}
              fill={node.color}
              opacity={0.15}
              filter="url(#glow)"
            />
            <circle
              cx={node.x} cy={node.y}
              r={node.radius * 0.6}
              fill={node.color}
              opacity={0.8}
              filter={node.type === 'self' ? 'url(#glow-strong)' : 'url(#glow)'}
              style={{
                animation: node.type === 'self' && sending ? 'bz-pulse-yellow 1.5s ease-in-out infinite' : undefined,
              }}
            />
            {/* Label */}
            <text
              x={node.x}
              y={node.y + node.radius + 14}
              textAnchor="middle"
              fill={node.type === 'self' ? 'var(--bz-text)' : 'var(--bz-text-dim)'}
              fontSize={node.type === 'self' ? 11 : 9}
              fontFamily="ui-monospace, monospace"
            >
              {node.label}
            </text>
            {/* Status badge for tasks */}
            {node.type === 'task' && node.status && (
              <text
                x={node.x}
                y={node.y - node.radius - 6}
                textAnchor="middle"
                fill={node.color}
                fontSize={8}
                fontFamily="ui-monospace, monospace"
              >
                {node.status}
              </text>
            )}
          </g>
        ))}

        {/* Center label */}
        {selfNode && (
          <text
            x={selfNode.x}
            y={selfNode.y + 4}
            textAnchor="middle"
            fill="var(--bz-cyan-glow)"
            fontSize={9}
            fontWeight="bold"
            fontFamily="ui-monospace, monospace"
            filter="url(#glow)"
          >
            {sending ? 'RUNNING' : 'ONLINE'}
          </text>
        )}
      </svg>

      {/* Empty state */}
      {tasks.length === 0 && onlineAgents.length <= 1 && (
        <div className="absolute inset-0 flex items-center justify-center">
          <p className="text-sm" style={{ color: 'var(--bz-text-dim)' }}>连接集市后，网络拓扑将在此显示</p>
        </div>
      )}
    </div>
  );
}
