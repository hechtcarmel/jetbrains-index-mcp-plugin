import type { PluginName as ImportedPluginName } from "./alias-source";

export type ImportedPluginNameAlias = ImportedPluginName;

export const importedPluginName: ImportedPluginName = "alpha";

export function echoImportedPluginName(name: ImportedPluginName): ImportedPluginName {
  return name;
}
