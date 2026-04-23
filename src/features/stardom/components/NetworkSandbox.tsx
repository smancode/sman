// src/features/stardom/components/NetworkSandbox.tsx
// 协作星图 — Collaboration Atlas 核心
// 能力簇→星云发光 / 协作路径→星路流动 / 贡献沉积→节点亮度
// 纯 SVG + CSS 动画

import { useStardomStore } from '@/stores/stardom';
import { useChatStore } from '@/stores/chat';
import { useMemo } from 'react';
import { getReputationLevel } from './ReputationUtils';

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
  brightness: number; // 0-1, based on reputation/contribution
}

interface GraphLink {
  source: string;
  target: string;
  color: string;
  animated: boolean;
  intensity: number; // 0-1
}

interface StarNebula {
  x: number;
  y: number;
  radius: number;
  color: string;
  opacity: number;
}

function layoutNodes(
  width: number,
  height: number,
  selfId: string | undefined,
  tasks: Array<{ taskId: string; direction: string; status: string; helperName?: string; requesterName?: string; question: string }>,
  agents: Array<{ agentId: string; name: string; status: string; reputation: number }>,
): { nodes: GraphNode[]; links: GraphLink[]; nebulae: StarNebula[] } {
  const nodes: GraphNode[] = [];
  const links: GraphLink[] = [];
  const nebulae: StarNebula[] = [];
  const cx = width / 2;
  const cy = height / 2;

  // Self node at center — brightest
  nodes.push({
    id: 'self',
    label: '本节点',
    type: 'self',
    x: cx,
    y: cy,
    color: 'var(--bz-cyan)',
    glow: 'var(--bz-cyan-glow)',
    radius: 22,
    brightness: 1,
  });

  // Central nebula glow
  nebulae.push({ x: cx, y: cy, radius: 60, color: 'var(--bz-cyan)', opacity: 0.06 });

  // Task nodes in inner ring — form task nebulae
  const activeTasks = tasks.filter(t => ['searching', 'offered', 'matched', 'chatting'].includes(t.status));
  const taskCount = activeTasks.length;
  const taskRadius = Math.min(width, height) * 0.22;

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
      label: task.question.slice(0, 14),
      type: 'task',
      x: tx,
      y: ty,
      color: taskColor,
      glow: taskGlow,
      status: task.status,
      radius: isActive ? 12 : 9,
      brightness: isActive ? 0.8 : 0.4,
    });

    links.push({
      source: 'self',
      target: task.taskId,
      color: taskColor,
      animated: isActive,
      intensity: isActive ? 0.8 : 0.3,
    });

    // Active tasks create small nebulae
    if (isActive) {
      nebulae.push({ x: tx, y: ty, radius: 25, color: taskColor, opacity: 0.04 });
    }
  });

  // Agent nodes in outer ring — brightness based on reputation
  const otherAgents = agents.filter(a => a.agentId !== selfId).slice(0, 10);
  const agentRadius = Math.min(width, height) * 0.4;

  otherAgents.forEach((agent, i) => {
    const angle = (2 * Math.PI * i) / Math.max(otherAgents.length, 1) - Math.PI / 2;
    const ax = cx + Math.cos(angle) * agentRadius;
    const ay = cy + Math.sin(angle) * agentRadius;

    const agentColor = agent.status === 'idle' ? 'var(--bz-green)' : agent.status === 'busy' ? 'var(--bz-amber)' : 'var(--bz-text-dim)';
    const repLevel = getReputationLevel(agent.reputation);
    const brightness = Math.min(1, agent.reputation / 100);

    nodes.push({
      id: agent.agentId,
      label: agent.name,
      type: 'agent',
      x: ax,
      y: ay,
      color: agentColor,
      glow: agentColor,
      status: agent.status,
      radius: 6 + brightness * 4,
      brightness,
    });

    // High reputation agents glow brighter
    if (agent.reputation >= 50) {
      nebulae.push({ x: ax, y: ay, radius: 15 + brightness * 10, color: repLevel.color, opacity: 0.03 + brightness * 0.03 });
    }

    links.push({
      source: 'self',
      target: agent.agentId,
      color: 'var(--bz-border)',
      animated: false,
      intensity: 0.15 + brightness * 0.2,
    });
  });

  return { nodes, links, nebulae };
}

export function NetworkSandbox() {
  const { tasks, onlineAgents, connection } = useStardomStore();
  const sending = useChatStore((s) => s.sending);

  const width = 800;
  const height = 400;
  const cx = width / 2;
  const cy = height / 2;

  const { nodes, links, nebulae } = useMemo(
    () => layoutNodes(width, height, connection.agentId, tasks, onlineAgents),
    [tasks, onlineAgents, connection.agentId],
  );

  return (
    <div className="w-full h-full flex items-center justify-center relative overflow-hidden" style={{ background: 'var(--bz-bg)' }}>
      {/* Star field background — tiny dots */}
      <svg className="absolute inset-0 w-full h-full opacity-20" xmlns="http://www.w3.org/2000/svg">
        {Array.from({ length: 60 }, (_, i) => (
          <circle
            key={`star-${i}`}
            cx={Math.random() * 100 + '%'}
            cy={Math.random() * 100 + '%'}
            r={Math.random() * 0.8 + 0.3}
            fill="var(--bz-text-dim)"
            opacity={Math.random() * 0.5 + 0.1}
          />
        ))}
      </svg>

      {/* Grid background */}
      <div className="absolute inset-0 opacity-[0.03]" style={{
        backgroundImage: `
          linear-gradient(var(--bz-cyan) 1px, transparent 1px),
          linear-gradient(90deg, var(--bz-cyan) 1px, transparent 1px)
        `,
        backgroundSize: '50px 50px',
      }} />

      <svg width="100%" height="100%" viewBox={`0 0 ${width} ${height}`} className="relative">
        <defs>
          <filter id="glow" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="3" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
          <filter id="glow-strong" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="8" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
          <filter id="nebula" x="-100%" y="-100%" width="300%" height="300%">
            <feGaussianBlur stdDeviation="15" />
          </filter>
          <style>{`
            .link-animated {
              stroke-dasharray: 6 4;
              animation: bz-link-pulse 1.2s linear infinite;
            }
          `}</style>
        </defs>

        {/* Nebulae — capability clusters glow */}
        {nebulae.map((neb, i) => (
          <circle
            key={`neb-${i}`}
            cx={neb.x} cy={neb.y}
            r={neb.radius}
            fill={neb.color}
            opacity={neb.opacity}
            filter="url(#nebula)"
          />
        ))}

        {/* Links — star routes */}
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
              strokeWidth={link.animated ? 1.5 : 0.5}
              opacity={link.intensity}
              className={link.animated ? 'link-animated' : ''}
              filter={link.animated ? 'url(#glow)' : undefined}
            />
          );
        })}

        {/* Nodes */}
        {nodes.map((node) => (
          <g key={node.id}>
            {/* Outer glow ring — brightness */}
            <circle
              cx={node.x} cy={node.y}
              r={node.radius + 6}
              fill="none"
              stroke={node.color}
              strokeWidth={0.5}
              opacity={node.brightness * 0.3}
              style={{ animation: node.type === 'self' ? 'bz-node-breathe 4s ease-in-out infinite' : undefined }}
            />
            {/* Nebula body */}
            <circle
              cx={node.x} cy={node.y}
              r={node.radius + 2}
              fill={node.color}
              opacity={node.brightness * 0.08}
              filter="url(#glow-strong)"
            />
            {/* Core */}
            <circle
              cx={node.x} cy={node.y}
              r={node.radius}
              fill={node.color}
              opacity={node.brightness * 0.15}
            />
            <circle
              cx={node.x} cy={node.y}
              r={node.radius * 0.5}
              fill={node.color}
              opacity={0.6 + node.brightness * 0.4}
              filter="url(#glow)"
              style={{
                animation: node.type === 'self' && sending ? 'bz-pulse-yellow 1.5s ease-in-out infinite' : undefined,
              }}
            />
            {/* Label */}
            <text
              x={node.x}
              y={node.y + node.radius + 13}
              textAnchor="middle"
              fill={node.type === 'self' ? 'var(--bz-text)' : 'var(--bz-text-dim)'}
              fontSize={node.type === 'self' ? 10 : 8}
              fontFamily="ui-monospace, monospace"
              opacity={0.5 + node.brightness * 0.5}
            >
              {node.label}
            </text>
            {/* Status tag */}
            {node.type === 'task' && node.status && (
              <text
                x={node.x}
                y={node.y - node.radius - 5}
                textAnchor="middle"
                fill={node.color}
                fontSize={7}
                fontFamily="ui-monospace, monospace"
                opacity={0.7}
              >
                {node.status === 'chatting' ? 'ACTIVE' : node.status === 'matched' ? 'LINKED' : node.status.toUpperCase()}
              </text>
            )}
          </g>
        ))}

        {/* Center label */}
        {nodes.find(n => n.id === 'self') && (
          <text
            x={cx} y={cy + 3}
            textAnchor="middle"
            fill={sending ? 'var(--bz-amber)' : 'var(--bz-cyan-glow)'}
            fontSize={8}
            fontWeight="bold"
            fontFamily="ui-monospace, monospace"
            filter="url(#glow)"
          >
            {sending ? 'PROCESSING' : 'ONLINE'}
          </text>
        )}
      </svg>

      {/* Empty state */}
      {tasks.length === 0 && onlineAgents.length <= 1 && (
        <div className="absolute inset-0 flex items-center justify-center">
          <div className="text-center space-y-1">
            <p className="text-sm" style={{ color: 'var(--bz-text-dim)' }}>星图待绘制</p>
            <p className="text-[10px]" style={{ color: 'var(--bz-text-dim)', opacity: 0.5 }}>连接协作服务器后，协作星路将在此显现</p>
          </div>
        </div>
      )}
    </div>
  );
}
