"""Clip GeoJSON-like dict features to an authoritative boundary (Shapely)."""

from __future__ import annotations

import json
from typing import Any

from shapely.geometry import LineString, Point, mapping, shape


def _round_coord(x: float, nd: int = 6) -> float:
    return round(float(x), nd)


def round_geometry(g: dict[str, Any], nd: int = 6) -> dict[str, Any]:
    t = g.get("type")
    coords = g.get("coordinates")
    if t == "Point" and isinstance(coords, list) and len(coords) >= 2:
        return {
            "type": "Point",
            "coordinates": [_round_coord(coords[0], nd), _round_coord(coords[1], nd)],
        }
    if t == "LineString" and isinstance(coords, list):
        return {
            "type": "LineString",
            "coordinates": [[_round_coord(p[0], nd), _round_coord(p[1], nd)] for p in coords],
        }
    if t == "MultiLineString" and isinstance(coords, list):
        return {
            "type": "MultiLineString",
            "coordinates": [
                [[_round_coord(p[0], nd), _round_coord(p[1], nd)] for p in line] for line in coords
            ],
        }
    return json.loads(json.dumps(g, sort_keys=True))


def clip_point_to_boundary(
    payload_geom: dict[str, Any],
    boundary,
) -> dict[str, Any] | None:
    if payload_geom.get("type") != "Point":
        return None
    c = payload_geom.get("coordinates")
    if not isinstance(c, list) or len(c) < 2:
        return None
    p = Point(float(c[0]), float(c[1]))
    if not boundary.covers(p) and not boundary.contains(p):
        return None
    return round_geometry(mapping(p))


def clip_line_to_boundary(line: LineString, boundary) -> list[dict[str, Any]]:
    inter = line.intersection(boundary)
    if inter.is_empty:
        return []
    g = mapping(inter)
    t = g.get("type")
    if t == "LineString":
        return [round_geometry(g)]
    if t == "MultiLineString":
        out = []
        for seg in inter.geoms:
            out.append(round_geometry(mapping(seg)))
        return out
    if t == "GeometryCollection":
        out = []
        for sub in inter.geoms:
            if sub.geom_type == "LineString":
                out.append(round_geometry(mapping(sub)))
        return out
    return []


def shapely_boundary_from_geojson(geojson_str: str):
    g = shape(json.loads(geojson_str))
    if not g.is_valid:
        g = g.buffer(0)
    return g
