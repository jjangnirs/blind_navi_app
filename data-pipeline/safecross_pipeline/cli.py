import argparse
import json
import os
import sys
import time

from sqlalchemy import create_engine, text

from safecross_pipeline.ingest import ingest_file
from safecross_pipeline.spatial.review_cli import (
    export_review_queue,
    import_review_results,
)
from safecross_pipeline.spatial.spatial_join import (
    CrossingRecord,
    SignalRecord,
    persist_links_to_db,
    run_spatial_join,
)


def get_db_engine():
    db_url = os.getenv(
        "DATABASE_URL",
        "postgresql+psycopg://safecross:safecross_local_dev_only@localhost:5432/safecross",
    )
    return create_engine(db_url, pool_pre_ping=True)


def main(args=None):
    parser = argparse.ArgumentParser(
        description="Safe Cross KR Data Pipeline & Spatial Join CLI"
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    # 1. Ingest
    ingest_parser = subparsers.add_parser("ingest", help="Ingest public data CSV file")
    ingest_parser.add_argument(
        "--source",
        choices=["crosswalk", "traffic-light"],
        required=True,
        help="Source type (crosswalk 또는 traffic-light)",
    )
    ingest_parser.add_argument("--file", required=True, help="Path to CSV file")
    ingest_parser.add_argument(
        "--retrieved-at",
        required=True,
        help="Retrieval timestamp in ISO format (e.g. 2026-09-08T12:00:00Z)",
    )
    ingest_parser.add_argument(
        "--persist", action="store_true", help="Persist parsed records to database"
    )
    ingest_parser.add_argument(
        "--report-out", default=None, help="Optional path to save JSON report file"
    )
    ingest_parser.add_argument(
        "--report-dir", default=None, help="Optional directory to save JSON report"
    )

    # 2. Spatial Join
    sj_parser = subparsers.add_parser(
        "spatial-join", help="Execute spatial join between crossings and signals"
    )
    sj_parser.add_argument(
        "--radius",
        type=float,
        default=30.0,
        help="Max search radius in meters (default 30.0)",
    )
    sj_parser.add_argument(
        "--persist", action="store_true", help="Persist candidate links to database"
    )
    sj_parser.add_argument(
        "--osm-file",
        default=None,
        help="Optional path to GeoJSON file containing OSM crosswalk/intersection geometries",
    )

    # 3. Review
    review_parser = subparsers.add_parser(
        "review", help="Operator review export and import"
    )
    review_subparsers = review_parser.add_subparsers(
        dest="review_command", required=True
    )

    # Review Export
    export_parser = review_subparsers.add_parser(
        "export", help="Export review queue to CSV/GeoJSON"
    )
    export_parser.add_argument(
        "--format", choices=["csv", "geojson"], default="csv", help="Export format"
    )
    export_parser.add_argument("--out", required=True, help="Output file path")
    export_parser.add_argument(
        "--status",
        default="REVIEW_REQUIRED",
        help="Filter by association_status (default: REVIEW_REQUIRED, 'ALL' for no filter)",
    )

    # Review Import
    import_parser = review_subparsers.add_parser(
        "import", help="Import operator review results"
    )
    import_parser.add_argument(
        "--file", required=True, help="Path to reviewed file (CSV or GeoJSON)"
    )
    import_parser.add_argument(
        "--operator-id", required=True, help="Operator identifier"
    )
    import_parser.add_argument(
        "--role", choices=["admin", "operator"], required=True, help="Operator role"
    )

    parsed_args = parser.parse_args(args)

    if parsed_args.command == "ingest":
        try:
            engine = get_db_engine() if parsed_args.persist else None
            report = ingest_file(
                source_type=parsed_args.source,
                file_path=parsed_args.file,
                retrieved_at=parsed_args.retrieved_at,
                db_engine=engine,
            )
            report_json = report.to_json()
            print(report_json)

            if parsed_args.report_out:
                with open(parsed_args.report_out, "w", encoding="utf-8") as rf:
                    rf.write(report_json)
                print(f"Report saved to {parsed_args.report_out}")

            if parsed_args.report_dir:
                os.makedirs(parsed_args.report_dir, exist_ok=True)
                report_path = os.path.join(
                    parsed_args.report_dir,
                    f"ingestion_report_{parsed_args.source}_{int(time.time())}.json",
                )
                with open(report_path, "w", encoding="utf-8") as rf:
                    rf.write(report_json)
                print(f"Report saved to {report_path}")

        except (ValueError, FileNotFoundError, RuntimeError, OSError) as e:
            print(f"Error during ingestion: {e}", file=sys.stderr)
            sys.exit(1)

    elif parsed_args.command == "spatial-join":
        try:
            engine = get_db_engine()
            # Fetch crossings & signals from DB
            with engine.connect() as conn:
                crossing_rows = (
                    conn.execute(
                        text(
                            "SELECT id, source_record_key, ST_Y(geom::geometry) AS lat, ST_X(geom::geometry) AS lon, road_name FROM crossing WHERE published = TRUE AND valid_to IS NULL"
                        )
                    )
                    .mappings()
                    .all()
                )
                signal_rows = (
                    conn.execute(
                        text(
                            "SELECT id, source_record_key, ST_Y(geom::geometry) AS lat, ST_X(geom::geometry) AS lon, signal_type FROM signal_device WHERE published = TRUE AND valid_to IS NULL"
                        )
                    )
                    .mappings()
                    .all()
                )

            crossings = [
                CrossingRecord(
                    id=str(r["id"]),
                    source_record_key=r["source_record_key"],
                    lat=float(r["lat"]),
                    lon=float(r["lon"]),
                    road_name=r["road_name"],
                )
                for r in crossing_rows
            ]
            signals = [
                SignalRecord(
                    id=str(r["id"]),
                    source_record_key=r["source_record_key"],
                    lat=float(r["lat"]),
                    lon=float(r["lon"]),
                    road_name=None,
                    signal_type=r["signal_type"],
                )
                for r in signal_rows
            ]

            osm_features = None
            if parsed_args.osm_file:
                with open(parsed_args.osm_file, "r", encoding="utf-8") as f:
                    osm_data = json.load(f)
                    osm_features = osm_data.get("features", [])

            summary = run_spatial_join(
                crossings,
                signals,
                max_radius_m=parsed_args.radius,
                osm_features=osm_features,
            )
            print(json.dumps(summary.to_dict(), ensure_ascii=False, indent=2))

            if parsed_args.persist and summary.links:
                persisted = persist_links_to_db(summary.links, engine)
                print(f"Persisted {persisted} candidate links to database.")

        except (
            ValueError,
            FileNotFoundError,
            PermissionError,
            OSError,
            RuntimeError,
        ) as e:
            print(f"Error during spatial join: {e}", file=sys.stderr)
            sys.exit(1)

    elif parsed_args.command == "review":
        engine = get_db_engine()
        if parsed_args.review_command == "export":
            try:
                status_filter = (
                    None if parsed_args.status.upper() == "ALL" else parsed_args.status
                )
                count = export_review_queue(
                    db_engine=engine,
                    out_path=parsed_args.out,
                    format=parsed_args.format,
                    status_filter=status_filter,
                )
                print(f"Exported {count} review items to {parsed_args.out}")
            except (
                ValueError,
                FileNotFoundError,
                PermissionError,
                OSError,
                RuntimeError,
            ) as e:
                print(f"Error during review export: {e}", file=sys.stderr)
                sys.exit(1)

        elif parsed_args.review_command == "import":
            try:
                result = import_review_results(
                    db_engine=engine,
                    file_path=parsed_args.file,
                    operator_id=parsed_args.operator_id,
                    role=parsed_args.role,
                )
                print(
                    f"Successfully applied {result['applied_count']} review decisions by {result['operator_id']}."
                )
            except (
                ValueError,
                FileNotFoundError,
                PermissionError,
                OSError,
                RuntimeError,
            ) as e:
                print(f"Error during review import: {e}", file=sys.stderr)
                sys.exit(1)


if __name__ == "__main__":
    main()
