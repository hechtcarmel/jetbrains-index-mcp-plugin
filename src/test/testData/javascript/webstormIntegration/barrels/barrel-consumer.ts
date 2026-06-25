import { loadPluginConfig } from "./named-barrel";
import { loadPluginConfig as loadPluginConfigFromStar } from "./export-star-barrel";

export function loadProductionConfigThroughNamedBarrel(): string {
  return loadPluginConfig("production");
}

export function loadFromNamedBarrel(): string {
  return loadPluginConfig("named");
}

export function loadFromExportStarBarrel(): string {
  return loadPluginConfigFromStar("star");
}
