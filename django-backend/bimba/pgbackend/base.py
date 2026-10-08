"""PostgreSQL backend that pins every statement to the BIMBA schema, also behind a transaction-mode pooler.

The shared database is reached through a pooler (port 6433) in transaction mode: each transaction may run on another
server connection, so a session-level ``SET search_path`` does not stay with Django's connection (and leaks onto
connections other clients get). A statement can then read or create tables in the wrong schema, e.g. ``public``.

Every statement is therefore sent as ``SET LOCAL search_path TO "<schema>"; <statement>`` in ONE round trip. PostgreSQL
runs a multi-statement query as one (implicit) transaction on one server connection, so the schema applies to exactly
that statement and is gone afterwards; inside an explicit transaction it applies until the transaction ends.

The schema is settings.DB_SCHEMA; ``pinned(schema)`` switches it for a block (migrate_gateway_schema uses it).
Server-side cursors are disabled (a DECLARE cannot carry a second statement, and poolers do not keep them either).
"""
import contextlib

import psycopg2.extensions
from django.conf import settings
from django.db.backends.postgresql import base

_schema = None


def schema():
    return _schema or settings.DB_SCHEMA


@contextlib.contextmanager
def pinned(name):
    """Runs the block's statements in schema `name` (single-threaded management commands)."""
    global _schema
    before = _schema
    _schema = name
    try:
        yield
    finally:
        _schema = before


def _prefix():
    return 'SET LOCAL search_path TO "%s"; ' % schema().replace('"', '""')


class PinnedCursor(psycopg2.extensions.cursor):
    def execute(self, query, vars=None):
        if isinstance(query, str):
            # without parameters psycopg2 sends the text as it is; with parameters "%" must stay a placeholder marker
            query = _prefix() + query if vars is None else _prefix().replace("%", "%%") + query
        return super().execute(query, vars)

    def executemany(self, query, vars_list):
        # psycopg2 sends the (prefixed) text once per parameter set, each as one round trip
        if isinstance(query, str):
            query = _prefix().replace("%", "%%") + query
        return super().executemany(query, vars_list)


class DatabaseWrapper(base.DatabaseWrapper):
    def get_new_connection(self, conn_params):
        conn = super().get_new_connection(conn_params)
        conn.cursor_factory = PinnedCursor
        return conn
