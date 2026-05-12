export function loadAgentPrompt(pluginId: string): string {
  return `prompt:${normalizePluginId(pluginId)}`;
}

export function loadPluginConfig(pluginId: string): string {
  return normalizePluginId(pluginId);
}

function normalizePluginId(pluginId: string): string {
  return pluginId.trim().toLowerCase();
}
