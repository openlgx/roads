/** Storage object key builders — two-digit month `mm`. */

export function rawSessionZipKey(params: {
  councilSlug: string;
  projectSlug: string;
  deviceId: string;
  startedAt: Date;
  sessionUuid: string;
}): string {
  const y = params.startedAt.getUTCFullYear();
  const m = String(params.startedAt.getUTCMonth() + 1).padStart(2, "0");
  return `raw/${params.councilSlug}/${params.projectSlug}/${params.deviceId}/${y}/${m}/${params.sessionUuid}.zip`;
}

export function filteredSessionZipKey(params: {
  councilSlug: string;
  projectSlug: string;
  deviceId: string;
  startedAt: Date;
  sessionUuid: string;
}): string {
  const y = params.startedAt.getUTCFullYear();
  const m = String(params.startedAt.getUTCMonth() + 1).padStart(2, "0");
  return `filtered/${params.councilSlug}/${params.projectSlug}/${params.deviceId}/${y}/${m}/${params.sessionUuid}.zip`;
}

export function publishedRoughnessGeoJsonKey(councilSlug: string): string {
  return `published/${councilSlug}/roughness/latest.geojson`;
}

export function publishedAnomaliesGeoJsonKey(councilSlug: string): string {
  return `published/${councilSlug}/anomalies/latest.geojson`;
}

export function publishedConsensusGeoJsonKey(councilSlug: string): string {
  return `published/${councilSlug}/consensus/latest.geojson`;
}

export function publishedManifestKey(councilSlug: string): string {
  return `published/${councilSlug}/manifest.json`;
}
