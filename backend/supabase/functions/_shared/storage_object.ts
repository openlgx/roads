/**
 * Verify an object exists in Supabase Storage and matches expected byte size using a ranged GET
 * (downloads at most one byte; uses Content-Range total when present).
 */
export async function verifyStorageObjectSize(
  supabaseUrl: string,
  serviceKey: string,
  bucket: string,
  objectKey: string,
  expectedSize: number,
): Promise<void> {
  const base = supabaseUrl.replace(/\/$/, "");
  const enc = objectKey.split("/").map((p) => encodeURIComponent(p)).join("/");
  const url = `${base}/storage/v1/object/${bucket}/${enc}`;
  const res = await fetch(url, {
    method: "GET",
    headers: {
      Authorization: `Bearer ${serviceKey}`,
      apikey: serviceKey,
      Range: "bytes=0-0",
    },
  });
  if (res.status !== 200 && res.status !== 206) {
    throw new Error(`storage object missing or unreadable (HTTP ${res.status})`);
  }
  const cr = res.headers.get("content-range");
  if (cr) {
    const m = cr.trim().match(/\/(\d+)\s*$/);
    if (m) {
      const total = parseInt(m[1], 10);
      if (total !== expectedSize) {
        throw new Error(
          `byte size mismatch: expected ${expectedSize} from job, storage reports ${total}`,
        );
      }
      return;
    }
  }
  const cl = res.headers.get("content-length");
  if (cl != null && parseInt(cl, 10) === expectedSize) {
    return;
  }
  throw new Error("could not verify storage object size (no usable Content-Range/Length)");
}
