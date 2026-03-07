let repaintScheduled = false;
let pendingReason = "ui";
let coordinatorInstalled = false;
let lastRepaintAt = 0;
let trailingTimer: number | null = null;

const REPAINT_THROTTLE_MS = 100;

function flushHostRepaint() {
  repaintScheduled = false;
  lastRepaintAt = Date.now();

  try {
    window.__requestHostRepaint?.(pendingReason);
  } catch (_) {
    // Ignore repaint bridge errors; this is a best-effort host nudge for JCEF.
  }
}

export function scheduleJcefHostRepaint(reason = "ui") {
  if (typeof window === "undefined") return;

  pendingReason = reason;
  const now = Date.now();
  const elapsed = now - lastRepaintAt;

  if (!repaintScheduled && elapsed >= REPAINT_THROTTLE_MS) {
    repaintScheduled = true;
    requestAnimationFrame(() => {
      flushHostRepaint();
    });
    return;
  }

  if (trailingTimer !== null) return;

  const delay = Math.max(0, REPAINT_THROTTLE_MS - elapsed);
  trailingTimer = window.setTimeout(() => {
    trailingTimer = null;
    if (repaintScheduled) return;

    repaintScheduled = true;
    requestAnimationFrame(() => {
      flushHostRepaint();
    });
  }, delay);
}

export function installJcefHostRepaintCoordinator() {
  if (typeof window === "undefined" || coordinatorInstalled) return;
  coordinatorInstalled = true;

  const root = document.getElementById("root");
  const body = document.body;

  const schedule = (reason: string) => scheduleJcefHostRepaint(reason);

  const resizeObserver =
    typeof ResizeObserver !== "undefined"
      ? new ResizeObserver(() => schedule("resize-observer"))
      : null;

  if (root) {
    resizeObserver?.observe(root);
  }
  if (body) {
    resizeObserver?.observe(body);
  }

  const transitionHandler = () => schedule("transition");
  const animationHandler = () => schedule("animation");
  const visibilityHandler = () => schedule("visibility");

  document.addEventListener("transitionend", transitionHandler, true);
  document.addEventListener("animationend", animationHandler, true);
  document.addEventListener("visibilitychange", visibilityHandler, true);
  window.addEventListener("pageshow", visibilityHandler, true);

  schedule("coordinator-init");
}
