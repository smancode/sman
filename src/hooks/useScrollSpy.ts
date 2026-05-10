import { useEffect, useRef, useState, useCallback } from 'react';

interface UseScrollSpyOptions {
  containerRef: React.RefObject<HTMLDivElement | null>;
  items: Array<{ id: string }>;
  idPrefix?: string;
  threshold?: number;
}

export function useScrollSpy({
  containerRef,
  items,
  idPrefix = '',
  threshold = 0.2,
}: UseScrollSpyOptions) {
  const [activeId, setActiveId] = useState<string | null>(null);
  const isScrollingRef = useRef(false);

  useEffect(() => {
    const container = containerRef.current;
    if (!container || items.length === 0) return;

    let ready = false;
    const timer = setTimeout(() => { ready = true; }, 200);
    const observers: IntersectionObserver[] = [];

    items.forEach(({ id }) => {
      const el = document.getElementById(`${idPrefix}${id}`);
      if (!el) return;

      const observer = new IntersectionObserver(
        (entries) => {
          entries.forEach((entry) => {
            if (ready && entry.isIntersecting && !isScrollingRef.current) {
              setActiveId(id);
            }
          });
        },
        { root: container, threshold },
      );
      observer.observe(el);
      observers.push(observer);
    });

    return () => {
      clearTimeout(timer);
      observers.forEach((o) => o.disconnect());
    };
  }, [items, idPrefix, threshold, containerRef]);

  const scrollTo = useCallback((id: string) => {
    const el = document.getElementById(`${idPrefix}${id}`);
    if (el) {
      isScrollingRef.current = true;
      setActiveId(id);
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      setTimeout(() => { isScrollingRef.current = false; }, 800);
    }
  }, [idPrefix]);

  return { activeId, setActiveId, scrollTo };
}
