-- Parameter descriptor for Boomi's Database V2 connector (operation "BIMBA PostgreSQL bimba_ingest").
-- A Standard Get checks the input document's keys against the columns of its object type,
-- so the operation points at this zero-row view whose only column is the $payload parameter.
-- It holds no data and is never written to.
CREATE OR REPLACE VIEW bimba_ingest_param AS
SELECT CAST(NULL AS text) AS payload
WHERE false;
