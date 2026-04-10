import postgres from "https://deno.land/x/postgresjs@v3.4.5/mod.js";

/** Single connection pool for Edge (small). Prefer DATABASE_URL_POOLED on Neon. */
export function createSql() {
  const url = Deno.env.get("DATABASE_URL_POOLED")?.trim() ||
    Deno.env.get("DATABASE_URL")?.trim();
  if (!url) throw new Error("DATABASE_URL_POOLED or DATABASE_URL required");
  return postgres(url, {
    max: 3,
    idle_timeout: 20,
    connect_timeout: 10,
  });
}
