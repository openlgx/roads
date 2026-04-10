export function requireEnv(name: string): string {
  const v = Deno.env.get(name);
  if (!v?.trim()) throw new Error(`Missing env: ${name}`);
  return v.trim();
}

/**
 * Supabase CLI rejects custom secret names starting with `SUPABASE_`.
 * Edge runtimes still inject `SUPABASE_SERVICE_ROLE_KEY` / `SUPABASE_URL`.
 */
export function requireSupabaseServiceKey(): string {
  const v = Deno.env.get("SUPABASE_SECRET_KEY")?.trim() ||
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim();
  if (!v) {
    throw new Error(
      "Missing SUPABASE_SECRET_KEY or SUPABASE_SERVICE_ROLE_KEY",
    );
  }
  return v;
}

export function requireSupabaseProjectUrl(): string {
  const v = Deno.env.get("SUPABASE_PROJECT_URL")?.trim() ||
    Deno.env.get("SUPABASE_URL")?.trim();
  if (!v) {
    throw new Error("Missing SUPABASE_PROJECT_URL or SUPABASE_URL");
  }
  return v;
}

/** Bucket names must be set as RAW_BUCKET / PUBLISHED_BUCKET when using CLI `secrets set`. */
export function requireRawBucket(): string {
  const v = Deno.env.get("SUPABASE_RAW_BUCKET")?.trim() ||
    Deno.env.get("RAW_BUCKET")?.trim();
  if (!v) throw new Error("Missing SUPABASE_RAW_BUCKET or RAW_BUCKET");
  return v;
}

export function requirePublishedBucket(): string {
  const v = Deno.env.get("SUPABASE_PUBLISHED_BUCKET")?.trim() ||
    Deno.env.get("PUBLISHED_BUCKET")?.trim();
  if (!v) {
    throw new Error(
      "Missing SUPABASE_PUBLISHED_BUCKET or PUBLISHED_BUCKET",
    );
  }
  return v;
}
