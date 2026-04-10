import { jsonResponse } from "./json.ts";

export type ApiErrorBody = {
  error: { code: string; message: string; details?: unknown };
};

export function errorResponse(
  code: string,
  message: string,
  status: number,
  details?: unknown,
): Response {
  const body: ApiErrorBody = { error: { code, message, details } };
  return jsonResponse(body, status);
}
