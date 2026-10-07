"""Generates dashboards/bimba-overview.json. Run: python cloud/grafana/build_dashboard.py"""
import json
import os

DS = {"type": "grafana-postgresql-datasource", "uid": "scada-pg"}
panels = []
pid = 0
Y = 0


def target(sql, fmt="table"):
    return {"refId": "A", "datasource": DS, "format": fmt, "rawQuery": True, "editorMode": "code", "rawSql": sql}


def panel(ptype, title, x, y, w, h, sql, fmt="table", defaults=None, options=None, overrides=None, **extra):
    global pid
    pid += 1
    p = {"id": pid, "type": ptype, "title": title, "datasource": DS,
         "gridPos": {"x": x, "y": y, "w": w, "h": h}, "targets": [target(sql, fmt)],
         "fieldConfig": {"defaults": defaults or {}, "overrides": overrides or []}, "options": options or {}}
    p.update(extra)
    panels.append(p)


def row(title, y):
    global pid
    pid += 1
    panels.append({"id": pid, "type": "row", "title": title, "collapsed": False,
                   "gridPos": {"x": 0, "y": y, "w": 24, "h": 1}, "panels": []})


def steps(*pairs):
    return {"mode": "absolute", "steps": [{"color": c, "value": v} for c, v in pairs]}


def latest(machine, tag):
    return f"SELECT value FROM latest_values WHERE machine_id = '{machine}' AND tag = '{tag}'"


def latest_text(machine, tag):
    return f"SELECT value_text AS value FROM latest_values WHERE machine_id = '{machine}' AND tag = '{tag}'"


REDUCE = {"calcs": ["lastNotNull"], "fields": "", "values": False}
STAT = {"reduceOptions": REDUCE, "colorMode": "background", "graphMode": "none", "textMode": "value",
        "justifyMode": "center"}
STATV = {**STAT, "colorMode": "value"}
TS_OPTS = {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
           "tooltip": {"mode": "multi", "sort": "none"}}


def ts_def(unit="none", **custom):
    return {"unit": unit, "custom": {"lineWidth": 2, "fillOpacity": 8, "showPoints": "never", "spanNulls": True,
                                     **custom}}


def boolmap(on_text, on_color, off_text, off_color):
    return [{"type": "value", "options": {"1": {"text": on_text, "color": on_color, "index": 0},
                                          "0": {"text": off_text, "color": off_color, "index": 1}}}]


def textmap(mapping):
    return [{"type": "value", "options": {k: {"color": c, "index": i} for i, (k, c) in enumerate(mapping.items())}}]


def trend(pairs, agg="avg", label="tag"):
    lst = ", ".join(f"('{m}', '{t}')" for m, t in pairs)
    name = "m.tag" if label == "tag" else "m.machine_id || ' ' || m.tag"
    return (f"SELECT $__timeGroupAlias(m.time, $__interval), {name} AS metric, {agg}(m.value) AS value\n"
            "FROM measurements m\n"
            f"WHERE (m.machine_id, m.tag) IN ({lst}) AND $__timeFilter(m.time)\n"
            "GROUP BY 1, 2 ORDER BY 1")


def text_timeline(machine, tag, alias):
    return (f"SELECT time, value_text AS \"{alias}\" FROM measurements\n"
            f"WHERE machine_id = '{machine}' AND tag = '{tag}' AND $__timeFilter(time) ORDER BY time")


def dual_axis(right_name, right_unit, left_name=None, left_unit=None):
    ov = [{"matcher": {"id": "byName", "options": right_name},
           "properties": [{"id": "unit", "value": right_unit}, {"id": "custom.axisPlacement", "value": "right"}]}]
    if left_name:
        ov.append({"matcher": {"id": "byName", "options": left_name},
                   "properties": [{"id": "unit", "value": left_unit}]})
    return ov


STATUS_COLORS = textmap({"online": "green", "offline": "red", "UP": "green", "DOWN": "red",
                         "ACTIVE": "red", "CLEARED": "green", "GOOD": "green", "BAD": "red", "UNCERTAIN": "orange"})
COLOR_CELL = {"id": "custom.cellOptions", "value": {"type": "color-background", "mode": "basic"}}


def colored(col):
    return {"matcher": {"id": "byName", "options": col},
            "properties": [{"id": "mappings", "value": STATUS_COLORS}, COLOR_CELL]}


# ============================================================ integration health
row("Integration health (edge -> cloud)", Y); Y += 1
panel("stat", "WAN bridge PLANT01", 0, Y, 4, 4,
      "SELECT state AS value FROM edge_links WHERE plant_id = 'PLANT01'",
      defaults={"mappings": textmap({"UP": "green", "DOWN": "red"}), "thresholds": steps(("#808080", None))},
      options={**STAT, "reduceOptions": {**REDUCE, "fields": "/.*/"}})
panel("stat", "Messages / s (cloud ingest)", 4, Y, 4, 4,
      "SELECT count(*) / 30.0 AS value FROM delivery_log WHERE received_at > now() - interval '30 seconds'",
      defaults={"decimals": 1, "thresholds": steps(("blue", None))}, options={**STATV})
panel("stat", "End-to-end latency (p95, 1 min)", 8, Y, 4, 4,
      "SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM received_at - event_time)) AS value\n"
      "FROM delivery_log WHERE received_at > now() - interval '1 minute'",
      defaults={"unit": "s", "decimals": 2, "thresholds": steps(("green", None), ("orange", 5), ("red", 30))},
      options={**STATV})
panel("stat", "Missing messages (all sessions)", 12, Y, 4, 4,
      "SELECT COALESCE(sum(missing), 0) AS value FROM delivery_gaps",
      defaults={"thresholds": steps(("green", None), ("red", 1))}, options={**STAT})
panel("stat", "Active alarms", 16, Y, 4, 4, "SELECT count(*) AS value FROM active_alarms",
      defaults={"thresholds": steps(("green", None), ("red", 1))}, options={**STAT})
panel("stat", "OT sources online", 20, Y, 4, 4,
      "SELECT count(*) FILTER (WHERE status = 'online') || ' / ' || count(*) AS value FROM sources",
      defaults={"thresholds": steps(("green", None))},
      options={**STATV, "reduceOptions": {**REDUCE, "fields": "/.*/"}})
Y += 4
panel("timeseries", "Cloud ingest rate & delivery latency", 0, Y, 12, 7,
      "SELECT $__timeGroupAlias(received_at, '5s'), count(*) / 5.0 AS \"msgs/s\",\n"
      "       avg(extract(epoch FROM received_at - event_time)) AS \"latency (s)\"\n"
      "FROM delivery_log WHERE $__timeFilter(received_at) GROUP BY 1 ORDER BY 1",
      fmt="time_series", defaults=ts_def(), options=TS_OPTS, overrides=dual_axis("latency (s)", "s"))
panel("table", "OT source communication status (reported by edge)", 12, Y, 12, 7,
      "SELECT name AS \"Source\", protocol AS \"Protocol\", machine_id AS \"Machine\", status AS \"Status\",\n"
      "       endpoint AS \"Endpoint\", last_ok AS \"Last good read\", last_error AS \"Last error\"\n"
      "FROM sources ORDER BY protocol, name",
      overrides=[colored("Status")], options={"showHeader": True, "cellHeight": "sm"})
Y += 7

# ============================================================ pump station
row("Pump station  -  SCADA/PLC via Modbus TCP", Y); Y += 1
panel("gauge", "Tank T1 level", 0, Y, 4, 6, latest("TANK-T1", "tank_level"),
      defaults={"unit": "percent", "min": 0, "max": 100, "decimals": 1,
                "thresholds": steps(("red", None), ("orange", 20), ("green", 25), ("orange", 85), ("red", 90))},
      options={"reduceOptions": REDUCE, "showThresholdMarkers": True})
panel("stat", "Pump P1", 4, Y, 3, 3, latest("PUMP-P1", "running"),
      defaults={"mappings": boolmap("RUNNING", "green", "STOPPED", "#808080"), "thresholds": steps(("#808080", None))},
      options=STAT)
panel("stat", "P1 trip", 4, Y + 3, 3, 3, latest("PUMP-P1", "tripped"),
      defaults={"mappings": boolmap("TRIPPED", "red", "HEALTHY", "green"), "thresholds": steps(("green", None))},
      options=STAT)
panel("timeseries", "Tank level", 7, Y, 6, 6, trend([("TANK-T1", "tank_level")]), fmt="time_series",
      defaults={**ts_def("percent", thresholdsStyle={"mode": "dashed"}), "min": 0, "max": 100,
                "thresholds": steps(("transparent", None), ("red", 90))}, options=TS_OPTS, interval="2s")
panel("timeseries", "Flows (m³/h)", 13, Y, 5, 6, trend([("PUMP-P1", "inlet_flow"), ("TANK-T1", "outlet_flow")]),
      fmt="time_series", defaults=ts_def(), options=TS_OPTS, interval="2s")
panel("timeseries", "Motor temp & current", 18, Y, 6, 6,
      trend([("PUMP-P1", "motor_temp"), ("PUMP-P1", "motor_current")]), fmt="time_series",
      defaults=ts_def(), options=TS_OPTS, interval="2s",
      overrides=dual_axis("motor_current", "amp", "motor_temp", "celsius"))
Y += 6

# ============================================================ substation
row("Substation meter  -  OPC UA", Y); Y += 1
panel("stat", "Bus voltage", 0, Y, 4, 3, latest("SUBSTN-M1", "bus_voltage"),
      defaults={"unit": "volt", "decimals": 1, "thresholds": steps(("red", None), ("green", 380), ("red", 420))},
      options=STATV)
panel("stat", "Active power", 0, Y + 3, 4, 3, latest("SUBSTN-M1", "active_power"),
      defaults={"unit": "kwatt", "decimals": 1, "thresholds": steps(("yellow", None))}, options=STATV)
panel("stat", "Main breaker", 4, Y, 3, 3, latest("SUBSTN-M1", "breaker_closed"),
      defaults={"mappings": boolmap("CLOSED", "green", "OPEN", "red"), "thresholds": steps(("green", None))},
      options=STAT)
panel("stat", "Frequency", 4, Y + 3, 3, 3, latest("SUBSTN-M1", "frequency"),
      defaults={"unit": "hertz", "decimals": 2, "thresholds": steps(("red", None), ("green", 49.8), ("red", 50.2))},
      options=STATV)
panel("timeseries", "Bus voltage", 7, Y, 8, 6, trend([("SUBSTN-M1", "bus_voltage")]), fmt="time_series",
      defaults={**ts_def("volt", thresholdsStyle={"mode": "dashed"}), "thresholds": steps(("transparent", None), ("red", 380))},
      options=TS_OPTS, interval="2s")
panel("timeseries", "Power & feeder current", 15, Y, 9, 6,
      trend([("SUBSTN-M1", "active_power"), ("SUBSTN-M1", "feeder_current")]), fmt="time_series",
      defaults=ts_def(), options=TS_OPTS, interval="2s",
      overrides=dual_axis("feeder_current", "amp", "active_power", "kwatt"))
Y += 6

# ============================================================ MQTT devices
row("Packaging line  -  MQTT raw JSON (FILLER-01)   |   Press  -  MQTT Sparkplug B (PRESS-01)", Y); Y += 1
state_map = textmap({"RUNNING": "green", "IDLE": "#808080", "BLOCKED": "orange", "FAULT": "red"})
panel("stat", "Filler state", 0, Y, 4, 3, latest_text("FILLER-01", "machine_state"),
      defaults={"mappings": state_map, "thresholds": steps(("#808080", None))},
      options={**STAT, "reduceOptions": {**REDUCE, "fields": "/.*/"}})
panel("stat", "Filler speed", 0, Y + 3, 4, 3, latest("FILLER-01", "speed"),
      defaults={"unit": "none", "decimals": 0, "displayName": "bottles/min",
                "thresholds": steps(("red", None), ("green", 200))},
      options={**STATV, "textMode": "value_and_name"})
panel("timeseries", "Filler speed & reject count", 4, Y, 8, 6,
      trend([("FILLER-01", "speed"), ("FILLER-01", "reject_count")], agg="max"), fmt="time_series",
      defaults=ts_def(), options=TS_OPTS, interval="2s", overrides=dual_axis("reject_count", "none"))
panel("stat", "Press state", 12, Y, 4, 3, latest_text("PRESS-01", "status_state"),
      defaults={"mappings": state_map, "thresholds": steps(("#808080", None))},
      options={**STAT, "reduceOptions": {**REDUCE, "fields": "/.*/"}})
panel("stat", "Press cycles", 12, Y + 3, 4, 3, latest("PRESS-01", "cycle_count"),
      defaults={"decimals": 0, "thresholds": steps(("blue", None))}, options=STATV)
panel("timeseries", "Press hydraulic pressure & oil temp", 16, Y, 8, 6,
      trend([("PRESS-01", "hydraulic_pressure"), ("PRESS-01", "hydraulic_oiltemp")]), fmt="time_series",
      defaults=ts_def(), options=TS_OPTS, interval="2s",
      overrides=dual_axis("hydraulic_oiltemp", "celsius", "hydraulic_pressure", "pressurebar"))
Y += 6
panel("state-timeline", "Machine states", 0, Y, 24, 5,
      "SELECT time, machine_id || ' ' || tag AS metric, value_text AS value FROM measurements\n"
      "WHERE (machine_id, tag) IN (('FILLER-01','machine_state'), ('PRESS-01','status_state'))\n"
      "  AND $__timeFilter(time) ORDER BY time",
      defaults={"mappings": state_map, "thresholds": steps(("#808080", None)), "custom": {"fillOpacity": 80, "lineWidth": 0}},
      options={"showValue": "auto", "mergeValues": True, "rowHeight": 0.8,
               "legend": {"showLegend": False, "displayMode": "list", "placement": "bottom"}},
      transformations=[{"id": "partitionByValues", "options": {"fields": ["metric"], "keepFields": False,
                                                                 "naming": {"asLabels": False}}}])
Y += 5

# ============================================================ legacy SCADA
row("Boiler house  -  legacy SCADA (XML over REST + SQL operator journal)", Y); Y += 1
panel("stat", "Steam pressure", 0, Y, 4, 3, latest("BOILER-01", "steam_pressure"),
      defaults={"unit": "pressurebar", "decimals": 2, "thresholds": steps(("green", None), ("red", 11.8))},
      options=STATV)
panel("stat", "Burner", 0, Y + 3, 4, 3, latest("BOILER-01", "burner_on"),
      defaults={"mappings": boolmap("ON", "orange", "OFF", "#808080"), "thresholds": steps(("#808080", None))},
      options=STAT)
panel("timeseries", "Steam pressure vs setpoint", 4, Y, 8, 6,
      trend([("BOILER-01", "steam_pressure"), ("BOILER-01", "steam_setpoint")]), fmt="time_series",
      defaults=ts_def("pressurebar"), options=TS_OPTS, interval="5s")
panel("table", "Operator journal (-> HRM shift adherence)", 12, Y, 12, 6,
      "SELECT time AS \"Time\", shift AS \"Shift\", operator_id AS \"Operator\", operator_name AS \"Name\",\n"
      "       action AS \"Action\", object AS \"Object\", details::text AS \"Details\"\n"
      "FROM operator_logs WHERE $__timeFilter(time) ORDER BY time DESC LIMIT 200",
      options={"showHeader": True, "cellHeight": "sm"})
Y += 6

# ============================================================ alarms & enterprise
row("Alarms  |  Boomi deliveries to enterprise systems", Y); Y += 1
panel("table", "Alarm & event journal", 0, Y, 14, 9,
      "SELECT time AS \"Time\", machine_id AS \"Machine\", tag AS \"Tag\", kind AS \"Type\", state AS \"State\",\n"
      "       value AS \"Value\", limit_value AS \"Limit\", message AS \"Message\"\n"
      "FROM alarms WHERE $__timeFilter(time) ORDER BY time DESC LIMIT 200",
      overrides=[colored("State")], options={"showHeader": True, "cellHeight": "sm"})
panel("table", "Enterprise inbox (what Boomi delivered to ERP / HRM / data lake)", 14, Y, 10, 9,
      "SELECT received_at AS \"Received\", upper(target) AS \"Target\", endpoint AS \"Endpoint\",\n"
      "       left(payload::text, 200) AS \"Payload\"\n"
      "FROM enterprise_inbox WHERE $__timeFilter(received_at) ORDER BY id DESC LIMIT 100",
      options={"showHeader": True, "cellHeight": "sm"})
Y += 9

dashboard = {
    "uid": "bimba-overview", "title": "BIMBA IT/OT Overview", "tags": ["bimba", "scada", "poc"],
    "timezone": "browser", "refresh": "5s", "schemaVersion": 39, "version": 1,
    "time": {"from": "now-15m", "to": "now"}, "editable": True, "graphTooltip": 1,
    "panels": panels, "templating": {"list": []}, "annotations": {"list": []},
}
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dashboards", "bimba-overview.json")
with open(out, "w", encoding="utf-8") as fh:
    json.dump(dashboard, fh, indent=2, ensure_ascii=False)
print("wrote", out, len(panels), "panels")
