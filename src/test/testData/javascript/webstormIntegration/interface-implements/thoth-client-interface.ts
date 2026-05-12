export interface ThothClient {
  fetch(prompt: string): Promise<string>;
}

export class HttpThothClient implements ThothClient {
  fetch(prompt: string): Promise<string> {
    return Promise.resolve(`http:${prompt}`);
  }
}

export class MemoryThothClient implements ThothClient {
  fetch(prompt: string): Promise<string> {
    return Promise.resolve(`memory:${prompt}`);
  }
}
