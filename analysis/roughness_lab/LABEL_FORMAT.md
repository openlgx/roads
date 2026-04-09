# Hand labels for roughness_lab eval

Minimal format for **window-level** gold labels (JSON Lines, UTF-8).

## Required fields (each line one JSON object)

| Field | Type | Meaning |
|-------|------|---------|
| `session_uuid` | string | Must match `session_uuid` in export `session.json`. |
| `window_start_ms` | number | UTC epoch **milliseconds** (inclusive), same time base as export CSVs. |
| `window_end_ms` | number | UTC epoch **milliseconds** (**exclusive**), aligned with lab `window_end_ms`. |
| `label` | string | One of the **canonical** classes below. |

## Canonical label strings

Use exactly these values (eval script normalizes case and strips whitespace):

- `stable_cruise`
- `braking`
- `cornering`
- `vertical_impact`
- `unknown_high_energy`
- `rail_crossing` — reserved for future geo-mask workflow; optional in tiny sets
- `designed_rough` — e.g. speed table, deliberate discontinuity without rail context

## Optional fields

- `reviewer` — string
- `notes` — string

## Example file

See `example_labels.jsonl` in this folder.

## Matching to lab windows

`eval_labels.py` matches a labeled interval to a lab row when:

- `session_uuid` matches, and
- **IoU** (intersection over union) of \([start, end)\) intervals is **≥ 0.5** (configurable).

The lab row’s `pred_primary` is compared to `label` for precision/recall.
