#!/bin/sh
# TEST ONLY: historian-like tables in the SQL test databases (containers sql-pg, sql-maria, sql-mssql, see sql_check.py).
#   sh sql_seed.sh     (from a shell with docker; test passwords only, never real ones)
# Each database: a "one row per tag" table with an older and a newer row per tag, a "one column per tag" table, and a
# login that can only read (bimba_reader / TestOnly_read1).
set -e

docker exec -i sql-pg psql -q -U postgres <<'SQL'
DROP TABLE IF EXISTS readings, line_data;
CREATE TABLE readings (tag_name text, value double precision, ts timestamptz, quality int);
INSERT INTO readings VALUES
  ('Line1.Speed', 10.0, now() - interval '1 hour', 192), ('Line1.Speed', 12.5, now() - interval '5 seconds', 192),
  ('Line1.Temp', 71.25, now() - interval '5 seconds', 192), ('Line1.Pressure', 3.2, now() - interval '5 seconds', 0),
  ('Line1.Level', 55.0, now() - interval '2 days', 192);
CREATE TABLE line_data (time timestamp, speed real, running boolean, batch text);
INSERT INTO line_data VALUES (now() - interval '1 minute', 1.0, false, 'B-0001'), (now(), 42.5, true, 'B-0002');
DROP ROLE IF EXISTS bimba_reader;
CREATE ROLE bimba_reader LOGIN PASSWORD 'TestOnly_read1';
GRANT SELECT ON readings, line_data TO bimba_reader;
SQL

docker exec -i sql-maria mariadb -uroot -pTestOnly_my1 scada <<'SQL'
DROP TABLE IF EXISTS readings, line_data;
CREATE TABLE readings (tag_name varchar(100), value double, ts datetime(3), quality varchar(20));
INSERT INTO readings VALUES
  ('Line1.Speed', 10.0, UTC_TIMESTAMP(3) - INTERVAL 1 HOUR, 'GOOD'), ('Line1.Speed', 12.5, UTC_TIMESTAMP(3), 'GOOD'),
  ('Line1.Temp', 71.25, UTC_TIMESTAMP(3), 'Good'), ('Line1.Pressure', 3.2, UTC_TIMESTAMP(3), 'BAD');
CREATE TABLE line_data (time datetime(3), speed float, running tinyint(1), batch varchar(20));
INSERT INTO line_data VALUES (UTC_TIMESTAMP(3), 42.5, 1, 'B-0002');
DROP USER IF EXISTS 'bimba_reader'@'%';
CREATE USER 'bimba_reader'@'%' IDENTIFIED BY 'TestOnly_read1';
GRANT SELECT ON scada.* TO 'bimba_reader'@'%';
SQL

docker exec -i sql-mssql /opt/mssql-tools18/bin/sqlcmd -C -b -S localhost -U sa -P 'TestOnly_ms1!' <<'SQL'
IF DB_ID('Runtime') IS NULL CREATE DATABASE Runtime;
GO
USE Runtime;
IF OBJECT_ID('dbo.Live') IS NOT NULL DROP TABLE dbo.Live;
IF OBJECT_ID('dbo.LineData') IS NOT NULL DROP TABLE dbo.LineData;
CREATE TABLE dbo.Live (TagName nvarchar(256), Value float, vValue sql_variant, DateTime datetime2(3), OPCQuality int);
INSERT INTO dbo.Live VALUES
  (N'Line1.Speed', 12.5, CAST(12.5 AS float), SYSUTCDATETIME(), 192),
  (N'Line1.Temp', 71.25, CAST(71.25 AS float), SYSUTCDATETIME(), 192),
  (N'Line1.Pressure', 3.2, CAST(3.2 AS float), SYSUTCDATETIME(), 64);
INSERT INTO dbo.Live VALUES (N'Line1.State', NULL, CAST(N'RUNNING' AS nvarchar(20)), SYSUTCDATETIME(), 192);
CREATE TABLE dbo.LineData ([time] datetimeoffset(3), Speed real, Running bit, Batch nvarchar(20));
INSERT INTO dbo.LineData VALUES (SYSDATETIMEOFFSET(), 42.5, 1, N'B-0002');
IF SUSER_ID('bimba_reader') IS NULL CREATE LOGIN bimba_reader WITH PASSWORD = 'TestOnly_read1', CHECK_POLICY = OFF;
IF USER_ID('bimba_reader') IS NULL CREATE USER bimba_reader FOR LOGIN bimba_reader;
ALTER ROLE db_datareader ADD MEMBER bimba_reader;
GO
SQL
echo seeded
