import { loadPluginConfig } from "./unrelated-barrel";

export function loadFromUnrelatedBarrel(): string {
  return loadPluginConfig("unrelated");
}
