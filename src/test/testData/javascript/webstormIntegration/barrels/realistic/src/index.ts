import { loadPluginConfig } from "../config";

export function bootstrapPluginConfig(): string {
  return loadPluginConfig(" Production ");
}
