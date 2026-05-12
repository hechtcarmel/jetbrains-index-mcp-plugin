export type PluginName = "alpha" | "beta";

export interface AliasSourceConfig {
  plugin: PluginName;
  enabled: boolean;
}
