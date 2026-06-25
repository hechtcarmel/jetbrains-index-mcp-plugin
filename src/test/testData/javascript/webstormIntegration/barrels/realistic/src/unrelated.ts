import { loadPluginConfig } from "../unrelated-config";

export function bootstrapUnrelatedPluginConfig(): string {
  return loadPluginConfig("Unrelated");
}
