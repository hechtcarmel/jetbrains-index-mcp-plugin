export const THOTH_STATUS = {
  Draft: "draft",
  Published: "published",
} as const;

export type ThothStatus = (typeof THOTH_STATUS)[keyof typeof THOTH_STATUS];

export const DEFAULT_THOTH_STATUS: ThothStatus = THOTH_STATUS.Draft;

export function formatThothStatus(status: ThothStatus): string {
  return status.toUpperCase();
}
