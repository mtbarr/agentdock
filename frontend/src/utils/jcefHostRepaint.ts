const FIRST_REPAINT_DELAY_MS = 500;
const SECOND_REPAINT_DELAY_MS = 3000;

let firstRepaintTimer: number | null = null;
let secondRepaintTimer: number | null = null;
let coordinatorInstalled = false;

function clearScheduledRepaints() {
  if (firstRepaintTimer !== null) {
    window.clearTimeout(firstRepaintTimer);
    firstRepaintTimer = null;
  }

  if (secondRepaintTimer !== null) {
    window.clearTimeout(secondRepaintTimer);
    secondRepaintTimer = null;
  }
}

function triggerRepaint(reason: string) {
  try {
    window.__requestHostRepaint?.(reason);
  } catch (_) {}
}

function scheduleClickRepaints() {
  clearScheduledRepaints();

  firstRepaintTimer = window.setTimeout(() => {
    firstRepaintTimer = null;
    triggerRepaint(`click:${FIRST_REPAINT_DELAY_MS}ms`);
  }, FIRST_REPAINT_DELAY_MS);

  secondRepaintTimer = window.setTimeout(() => {
    secondRepaintTimer = null;
    triggerRepaint(`click:${SECOND_REPAINT_DELAY_MS}ms`);
  }, SECOND_REPAINT_DELAY_MS);
}

export function installJcefHostRepaintCoordinator() {
  if (typeof window === "undefined" || coordinatorInstalled) return;
  coordinatorInstalled = true;

  document.addEventListener("click", scheduleClickRepaints, { passive: true, capture: true });
}
