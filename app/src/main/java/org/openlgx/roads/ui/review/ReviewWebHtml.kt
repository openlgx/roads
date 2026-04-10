package org.openlgx.roads.ui.review

/**
 * Single HTML shell for session review, sessions overview map, and all-runs map.
 * Plotly: three stacked subplots (shared time axis, linked vertical hover).
 * Leaflet: basemap toolbar (satellite / streets / dark) + layer toggles.
 */
internal fun tripReviewHtmlDocument(): String =
    """
    <!DOCTYPE html>
    <html>
    <head>
      <meta charset="utf-8"/>
      <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no"/>
      <link rel="stylesheet" href="vendor/leaflet.css"/>
      <script src="vendor/leaflet.js"></script>
      <script src="vendor/plotly-2.35.2.min.js"></script>
      <style>
        * { box-sizing: border-box; }
        html, body { height: 100%; margin: 0; background: #121212; color: #e0e0e0;
          font-family: system-ui, sans-serif; -webkit-text-size-adjust: 100%; }
        #note { padding: 6px 10px; font-size: 11px; color: #9aa0a6; flex-shrink: 0; }
        #toolbar {
          display: flex; flex-wrap: wrap; gap: 6px; padding: 6px 8px;
          background: #1e1e1e; border-bottom: 1px solid #333; flex-shrink: 0; align-items: center;
        }
        #toolbar button, #toolbar label {
          font-size: 11px; padding: 6px 10px; border-radius: 6px; border: 1px solid #444;
          background: #2a2a2a; color: #ddd; cursor: pointer;
        }
        #toolbar button.on { background: #3949ab; border-color: #5c6bc0; color: #fff; }
        #toolbar label { display: inline-flex; align-items: center; gap: 4px; cursor: pointer; }
        /* Use percent heights: 100vh inside an embedded WebView often yields a wrong tile pane size (black map). */
        #root { display: flex; flex-direction: column; height: 100%; min-height: 100%; }
        #map {
          flex: 1 1 auto; min-height: 220px; width: 100%; z-index: 1;
          border-bottom: 1px solid #333;
        }
        #plotHost {
          flex: 1 1 auto; min-height: 180px; width: 100%; display: none;
        }
        #plotHost.show { display: block; }
        .plot-title { font-size: 11px; color: #888; padding: 4px 8px 0; }
      </style>
    </head>
    <body>
      <div id="root">
        <div id="note"></div>
        <div id="toolbar"></div>
        <div id="map"></div>
        <div class="plot-title" id="plotTitle" style="display:none">Synced time (tap or drag on charts)</div>
        <div id="plotHost"></div>
      </div>
      <script>
        var MAP = null;
        var BASE_TILE = null;
        var ACTIVE_BASE = 'satellite';
        var ROUGH_LAYER = null;
        var ROUTE_LAYER = null;
        var ANOM_LAYER = null;
        var CONS_LAYER = null;
        var STATE = { showRoute: true, showRough: true, showAnom: true, showConsensus: true };

        function heatColor(t) {
          t = Math.max(0, Math.min(1, t));
          var h = (1 - t) * 105;
          return 'hsl(' + h + ', 82%, 52%)';
        }

        function roughnessRange(points) {
          var vals = [];
          for (var i = 0; i < points.length; i++) {
            var r = points[i].roughnessNorm;
            if (r != null && isFinite(r)) vals.push(r);
          }
          if (vals.length === 0) return { min: 0, max: 1 };
          var min = Math.min.apply(null, vals), max = Math.max.apply(null, vals);
          if (max - min < 1e-6) max = min + 1e-6;
          return { min: min, max: max };
        }

        function roughnessRangeFromWindows(wins) {
          var vals = [];
          for (var i = 0; i < wins.length; i++) {
            var r = wins[i].roughnessProxy;
            if (r != null && isFinite(r)) vals.push(r);
          }
          if (vals.length === 0) return { min: 0, max: 1 };
          var min = Math.min.apply(null, vals), max = Math.max.apply(null, vals);
          if (max - min < 1e-6) max = min + 1e-6;
          return { min: min, max: max };
        }

        function buildToolbar(DATA) {
          var tb = document.getElementById('toolbar');
          tb.innerHTML = '';
          var kind = DATA.reviewKind || 'singleSession';

          function mkToggle(text, key) {
            var b = document.createElement('button');
            b.type = 'button';
            b.textContent = text;
            b.className = STATE[key] ? 'on' : '';
            b.onclick = function() {
              STATE[key] = !STATE[key];
              b.className = STATE[key] ? 'on' : '';
              refreshMapLayers(DATA);
            };
            return b;
          }

          var sat = document.createElement('button');
          sat.type = 'button';
          sat.textContent = 'Satellite';
          sat.className = ACTIVE_BASE === 'satellite' ? 'on' : '';
          var str = document.createElement('button');
          str.type = 'button';
          str.textContent = 'Streets';
          str.className = ACTIVE_BASE === 'streets' ? 'on' : '';
          var drk = document.createElement('button');
          drk.type = 'button';
          drk.textContent = 'Dark';
          drk.className = ACTIVE_BASE === 'dark' ? 'on' : '';

          function setBaseBtn(active) {
            sat.className = active === 'satellite' ? 'on' : '';
            str.className = active === 'streets' ? 'on' : '';
            drk.className = active === 'dark' ? 'on' : '';
            ACTIVE_BASE = active;
          }
          sat.onclick = function() { setBasemap('satellite'); setBaseBtn('satellite'); };
          str.onclick = function() { setBasemap('streets'); setBaseBtn('streets'); };
          drk.onclick = function() { setBasemap('dark'); setBaseBtn('dark'); };

          tb.appendChild(sat); tb.appendChild(str); tb.appendChild(drk);

          if (kind !== 'singleSession') {
            tb.appendChild(mkToggle('Route', 'showRoute'));
            tb.appendChild(mkToggle('Roughness', 'showRough'));
            tb.appendChild(mkToggle('Anomalies', 'showAnom'));
            tb.appendChild(mkToggle('Consensus', 'showConsensus'));
          }
        }

        function setBasemap(which) {
          if (!MAP) return;
          if (BASE_TILE) {
            try { MAP.removeLayer(BASE_TILE); } catch (e) {}
            BASE_TILE = null;
          }
          var opt = { maxZoom: 19, attribution: '' };
          if (which === 'streets') {
            BASE_TILE = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', opt);
          } else if (which === 'dark') {
            BASE_TILE = L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', opt);
          } else {
            BASE_TILE = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', opt);
          }
          BASE_TILE.addTo(MAP);
        }

        function roughPopupHtml(roughLabel, sessionId, predHtml) {
          var openTrip = '';
          if (sessionId != null && window.RoadsAndroid && typeof window.RoadsAndroid.openSession === 'function') {
            openTrip = '<br/><a href="#" class="open-trip-review" data-sid="' + sessionId + '" style="color:#1565c0;font-size:12px;font-weight:600">Open trip review →</a>';
          }
          var tripLine = sessionId != null ? '<br/>Trip #' + sessionId : '';
          return '<div style="color:#111;font-size:12px">' + roughLabel + predHtml + tripLine + openTrip + '</div>';
        }

        function refreshMapLayers(DATA) {
          if (!MAP) return;
          if (ROUTE_LAYER) { MAP.removeLayer(ROUTE_LAYER); ROUTE_LAYER = null; }
          if (ROUGH_LAYER) { MAP.removeLayer(ROUGH_LAYER); ROUGH_LAYER = null; }
          if (ANOM_LAYER) { MAP.removeLayer(ANOM_LAYER); ANOM_LAYER = null; }
          if (CONS_LAYER) { MAP.removeLayer(CONS_LAYER); CONS_LAYER = null; }

          var rp = DATA.routePoints || [];
          var range = roughnessRange(rp);

          if (STATE.showRoute && rp.length > 1) {
            var latlngs = rp.map(function(p) { return [p.lat, p.lon]; });
            ROUTE_LAYER = L.layerGroup();
            var poly = L.polyline(latlngs, { color: '#ffffff', weight: 4, opacity: 0.65 });
            poly.addTo(ROUTE_LAYER);
            ROUTE_LAYER.addTo(MAP);
          }

          if (STATE.showRough) {
            ROUGH_LAYER = L.layerGroup();
            var wins = DATA.windows || [];
            var drewFromWindows = false;
            if (wins.length > 0) {
              var wr = roughnessRangeFromWindows(wins);
              for (var wi = 0; wi < wins.length; wi++) {
                var ww = wins[wi];
                if (ww.midLat == null || ww.midLon == null || ww.roughnessProxy == null || !isFinite(ww.roughnessProxy)) continue;
                var tw = (ww.roughnessProxy - wr.min) / (wr.max - wr.min);
                var cw = heatColor(tw);
                var wmk = L.circleMarker([ww.midLat, ww.midLon], { radius: 6, fillColor: cw, color: '#111', weight: 1, opacity: 0.9, fillOpacity: 0.85 });
                var predHtml = ww.pred != null ? '<br/>' + String(ww.pred) : '';
                wmk.bindPopup(roughPopupHtml('Roughness: ' + ww.roughnessProxy.toFixed(3), ww.sessionId, predHtml));
                wmk.addTo(ROUGH_LAYER);
              }
              drewFromWindows = ROUGH_LAYER.getLayers().length > 0;
            }
            if (!drewFromWindows) {
              range = roughnessRange(rp);
              for (var i = 0; i < rp.length; i++) {
                var p = rp[i];
                if (p.roughnessNorm == null || !isFinite(p.roughnessNorm)) continue;
                var t = (p.roughnessNorm - range.min) / (range.max - range.min);
                var c = heatColor(t);
                var m = L.circleMarker([p.lat, p.lon], { radius: 5, fillColor: c, color: '#111', weight: 1, opacity: 0.9, fillOpacity: 0.85 });
                m.bindPopup(roughPopupHtml('Roughness: ' + p.roughnessNorm.toFixed(3), p.sessionId, ''));
                m.addTo(ROUGH_LAYER);
              }
            }
            if (ROUGH_LAYER.getLayers().length) ROUGH_LAYER.addTo(MAP);
          }

          var winR = roughnessRangeFromWindows(DATA.windows || []);
          if (winR.max - winR.min > 1e-9) {
            range = { min: Math.min(range.min, winR.min), max: Math.max(range.max, winR.max) };
          }

          if (STATE.showAnom && DATA.anomalies && DATA.anomalies.length) {
            ANOM_LAYER = L.layerGroup();
            for (var j = 0; j < DATA.anomalies.length; j++) {
              var a = DATA.anomalies[j];
              if (a.lat == null || a.lon == null) continue;
              var mk = L.circleMarker([a.lat, a.lon], { radius: 7, fillColor: '#ff5252', color: '#fff', weight: 1, fillOpacity: 0.9 });
              mk.bindPopup('Anomaly');
              mk.addTo(ANOM_LAYER);
            }
            ANOM_LAYER.addTo(MAP);
          }

          if (STATE.showConsensus && DATA.consensusBins && DATA.consensusBins.length) {
            CONS_LAYER = L.layerGroup();
            for (var k = 0; k < DATA.consensusBins.length; k++) {
              var b = DATA.consensusBins[k];
              if (b.lat == null || b.lon == null) continue;
              var pr = b.proxy != null ? b.proxy : 0;
              var cr = heatColor(Math.min(1, pr / Math.max(1e-6, range.max)));
              var cm = L.circleMarker([b.lat, b.lon], { radius: 8, fillColor: cr, color: '#222', weight: 1, fillOpacity: 0.5 });
              cm.addTo(CONS_LAYER);
            }
            if (CONS_LAYER.getLayers().length) CONS_LAYER.addTo(MAP);
          }

          if (rp.length > 1) {
            try { MAP.fitBounds(L.latLngBounds(rp.map(function(p) { return [p.lat, p.lon]; })), { padding: [14, 14] }); } catch (e) {}
          } else if (rp.length === 1) {
            try { MAP.setView([rp[0].lat, rp[0].lon], 14); } catch (e) {}
          }
          try { MAP.invalidateSize(true); } catch (e) {}
        }

        function initMap(DATA) {
          document.getElementById('map').innerHTML = '';
          var rp = DATA.routePoints || [];
          if (rp.length === 0) {
            document.getElementById('map').innerHTML = '<p style="padding:12px">No route points yet</p>';
            return;
          }
          MAP = L.map('map', { zoomControl: true });
          MAP.on('popupopen', function(e) {
            var container = e.popup.getElement();
            if (!container) return;
            var link = container.querySelector('.open-trip-review');
            if (!link) return;
            L.DomEvent.once(link, 'click', function(ev) {
              L.DomEvent.stopPropagation(ev);
              L.DomEvent.preventDefault(ev);
              var sid = link.getAttribute('data-sid');
              if (sid && window.RoadsAndroid && typeof window.RoadsAndroid.openSession === 'function') {
                window.RoadsAndroid.openSession(sid);
              }
            });
          });
          setBasemap(ACTIVE_BASE);
          refreshMapLayers(DATA);
          function fixMapLayout() {
            if (!MAP) return;
            try { MAP.invalidateSize(true); } catch (e) {}
            var pts = DATA.routePoints || [];
            if (pts.length > 1) {
              try { MAP.fitBounds(L.latLngBounds(pts.map(function(p) { return [p.lat, p.lon]; })), { padding: [14, 14] }); } catch (e) {}
            } else if (pts.length === 1) {
              try { MAP.setView([pts[0].lat, pts[0].lon], 14); } catch (e) {}
            }
          }
          fixMapLayout();
          setTimeout(fixMapLayout, 80);
          setTimeout(fixMapLayout, 400);
        }

        function buildCharts(DATA) {
          var host = document.getElementById('plotHost');
          var title = document.getElementById('plotTitle');
          var kind = DATA.reviewKind || 'singleSession';
          if (kind !== 'singleSession') {
            host.className = '';
            title.style.display = 'none';
            host.innerHTML = '';
            if (window.__plotlyGd) { try { Plotly.purge(host); } catch (e) {} window.__plotlyGd = null; }
            return;
          }

          var rp = DATA.routePoints || [];
          var cs = DATA.chartSeries || {};
          var accT = cs.accT || [], accMag = cs.accMag || [];
          var gyrT = cs.gyrT || [], gyrMag = cs.gyrMag || [];
          var x1 = rp.map(function(p) { return p.tRelS; });
          var y1 = rp.map(function(p) { return p.speedKmh != null ? p.speedKmh : null; });

          var h = Math.max(260, Math.floor(window.innerHeight * 0.38));
          host.className = 'show';
          title.style.display = 'block';
          host.innerHTML = '';

          var t1 = {
            type: 'scatter', mode: 'lines', x: x1, y: y1, name: 'Speed',
            line: { color: '#4fc3f7', width: 2 },
            xaxis: 'x', yaxis: 'y', hovertemplate: 't=%{x:.1f}s<br>km/h=%{y:.1f}<extra></extra>'
          };
          var t2 = {
            type: 'scatter', mode: 'lines', x: accT, y: accMag, name: '|accel|',
            line: { color: '#ffb74d', width: 1.2 },
            xaxis: 'x2', yaxis: 'y2', hovertemplate: 't=%{x:.1f}s<br>m/s2=%{y:.3f}<extra></extra>'
          };
          var t3 = {
            type: 'scatter', mode: 'lines', x: gyrT, y: gyrMag, name: '|gyro|',
            line: { color: '#81c784', width: 1.2 },
            xaxis: 'x3', yaxis: 'y3', hovertemplate: 't=%{x:.1f}s<br>rad/s=%{y:.3f}<extra></extra>'
          };

          var layout = {
            height: h,
            paper_bgcolor: '#121212', plot_bgcolor: '#1e1e1e',
            font: { color: '#ccc', size: 10 },
            margin: { l: 50, r: 14, t: 8, b: 22 },
            hovermode: 'x',
            spikedistance: 1000,
            showlegend: false,
            xaxis: {
              domain: [0, 1], anchor: 'y', showticklabels: false, title: '',
              showspikes: true, spikecolor: '#90a4ae', spikethickness: 1, spikesnap: 'cursor',
              spikemode: 'across', zeroline: false
            },
            yaxis: {
              domain: [0.70, 1], title: { text: 'km/h', font: { size: 11 } },
              gridcolor: '#333', zeroline: false
            },
            xaxis2: {
              domain: [0, 1], anchor: 'y2', matches: 'x', showticklabels: false,
              showspikes: true, spikecolor: '#90a4ae', spikethickness: 1, spikesnap: 'cursor',
              spikemode: 'across', zeroline: false
            },
            yaxis2: {
              domain: [0.38, 0.68], title: { text: 'm/s2', font: { size: 11 } },
              gridcolor: '#333', zeroline: false
            },
            xaxis3: {
              domain: [0, 1], anchor: 'y3', matches: 'x', title: { text: 's from start', font: { size: 11 } },
              showspikes: true, spikecolor: '#90a4ae', spikethickness: 1, spikesnap: 'cursor',
              spikemode: 'across', zeroline: false
            },
            yaxis3: {
              domain: [0, 0.33], title: { text: 'rad/s', font: { size: 11 } },
              gridcolor: '#333', zeroline: false
            }
          };

          var config = { responsive: true, displayModeBar: false, scrollZoom: false };
          Plotly.newPlot(host, [t1, t2, t3], layout, config).then(function(gd) {
            window.__plotlyGd = gd;
          });
        }

        function decodeB64Utf8(dataB64) {
          var bin = atob(dataB64);
          var bytes = new Uint8Array(bin.length);
          for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
          return new TextDecoder('utf-8').decode(bytes);
        }

        function init(dataB64) {
          try {
            var DATA = JSON.parse(decodeB64Utf8(dataB64));
            var ver = DATA.reviewPayloadVersion;
            if (ver !== 1 && ver !== 2) {
              document.getElementById('note').textContent = 'Unsupported review payload version';
              return;
            }
            document.getElementById('note').textContent = DATA.experimentalDisclaimer || '';
            ACTIVE_BASE = 'satellite';
            var consDef = DATA.initialConsensusVisible;
            if (consDef === undefined || consDef === null) consDef = true;
            STATE = {
              showRoute: true, showRough: true, showAnom: true,
              showConsensus: !!consDef
            };
            buildToolbar(DATA);
            initMap(DATA);
            buildCharts(DATA);
          } catch (e) {
            document.getElementById('note').textContent = 'Review render error: ' + e;
          }
        }
      </script>
    </body>
    </html>
    """.trimIndent()
