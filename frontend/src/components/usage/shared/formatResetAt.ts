const MAX_QUOTA_RESET_FUTURE_MS = 100 * 24 * 60 * 60 * 1000;
const MAX_QUOTA_RESET_FUTURE_SECONDS = MAX_QUOTA_RESET_FUTURE_MS / 1000;

function parseResetAtTime(value: string | number | null | undefined): number | null {
  if (value === null || value === undefined) return null;

  const time = new Date(value).getTime();
  return Number.isNaN(time) ? null : time;
}

export function hasDisplayableQuotaReset(value: string | number | null | undefined, now = Date.now()): boolean {
  const time = parseResetAtTime(value);
  if (time === null) return false;

  const delta = time - now;
  return delta >= 0 && delta <= MAX_QUOTA_RESET_FUTURE_MS;
}

export function hasDisplayableQuotaResetAfterSeconds(seconds: number): boolean {
  return seconds >= 0 && seconds <= MAX_QUOTA_RESET_FUTURE_SECONDS;
}

export function formatResetAt(value: string | number | null | undefined): string | null {
  const time = parseResetAtTime(value);
  if (time === null) return null;

  const date = new Date(time);

  try {
    const parts = new Intl.DateTimeFormat(undefined, {
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
      hour12: false,
    }).formatToParts(date);

    const month = parts.find((part) => part.type === 'month')?.value;
    const day = parts.find((part) => part.type === 'day')?.value;
    const hour = parts.find((part) => part.type === 'hour')?.value;
    const minute = parts.find((part) => part.type === 'minute')?.value;

    if (!month || !day || !hour || !minute) return null;

    return `${month} ${day}, ${Number(hour)}:${minute}`;
  } catch {
    return null;
  }
}
