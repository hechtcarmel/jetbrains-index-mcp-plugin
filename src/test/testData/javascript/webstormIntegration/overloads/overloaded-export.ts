export function getProjectId(
  input: string,
): Promise<string | null>;
export function getProjectId(
  input: {
    workspace: string;
    project: string;
  },
): Promise<string | null>;
export async function getProjectId(
  input: string | {
    workspace: string;
    project: string;
  },
): Promise<string | null> {
  const normalizedProjectKey =
    typeof input === "string"
      ? input
      : `${input.workspace}/${input.project}`;

  return readProjectIdFromConfig(normalizedProjectKey);
}

async function readProjectIdFromConfig(
  projectKey: string,
): Promise<string | null> {
  return projectKey.trim() || null;
}
