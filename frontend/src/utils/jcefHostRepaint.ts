const PERIODIC_INTERVAL_MS = 3000;
const INACTIVITY_STOP_MS = 10_000;

let periodicTimer: number | null = null;
let lastActivityAt = 0;
let coordinatorInstalled = false;

function triggerRepaint() {
  try {
    window.__requestHostRepaint?.();
  } catch (_) {}
}

function schedulePeriodicRepaint() {
  periodicTimer = window.setTimeout(() => {
    periodicTimer = null;
    if (Date.now() - lastActivityAt > INACTIVITY_STOP_MS) return;
    triggerRepaint();
    schedulePeriodicRepaint();
  }, PERIODIC_INTERVAL_MS);
}

function recordActivity() {
  const wasInactive = Date.now() - lastActivityAt > INACTIVITY_STOP_MS;
  lastActivityAt = Date.now();
  if (wasInactive && periodicTimer === null) {
    schedulePeriodicRepaint();
  }
}

export function installJcefHostRepaintCoordinator() {
  if (typeof window === "undefined" || coordinatorInstalled) return;
  coordinatorInstalled = true;

  document.addEventListener("mousemove", recordActivity, { passive: true, capture: true });
  document.addEventListener("keydown", recordActivity, { passive: true, capture: true });
  new MutationObserver(recordActivity).observe(document.body, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ["class", "style"],
  });

  lastActivityAt = Date.now();
  schedulePeriodicRepaint();
}
