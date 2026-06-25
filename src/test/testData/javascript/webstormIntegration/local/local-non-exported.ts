function hiddenUtility(value: string): string {
  return value.trim();
}

export function visibleEntry(value: string): string {
  return hiddenUtility(value);
}
