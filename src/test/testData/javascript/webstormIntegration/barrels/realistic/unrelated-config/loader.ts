export function loadAgentPrompt(pluginId: string): string {
  return `prompt:${pluginId}`;
}

export function loadPluginConfig(pluginId: string): string {
  return pluginId;
}
