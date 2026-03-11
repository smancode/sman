export type AcpxInvokeRequest = {
  agent: "claudecode";
  taskId: string;
  prompt: string;
};

export type AcpxInvokeResponse = {
  output: string;
};

export interface AcpxClient {
  invoke(request: AcpxInvokeRequest): Promise<AcpxInvokeResponse>;
}
