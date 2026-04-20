export function getMenuItems(menu: HTMLDivElement | null): HTMLButtonElement[] {
  if (!menu) {
    return [];
  }
  return Array.from(menu.querySelectorAll<HTMLButtonElement>('[role="menuitem"]'));
}

export function focusMenuItem(menu: HTMLDivElement | null, index: number) {
  const items = getMenuItems(menu);
  if (items.length === 0) {
    return;
  }
  items[((index % items.length) + items.length) % items.length]?.focus();
}

export function moveMenuFocus(menu: HTMLDivElement | null, delta: number) {
  const items = getMenuItems(menu);
  if (items.length === 0) {
    return;
  }
  const activeIndex = items.findIndex((item) => item === document.activeElement);
  const nextIndex = activeIndex === -1 ? (delta > 0 ? 0 : items.length - 1) : activeIndex + delta;
  items[((nextIndex % items.length) + items.length) % items.length]?.focus();
}
