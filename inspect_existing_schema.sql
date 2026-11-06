-- =====================================================================
-- Petsa de Sweldo - READ-ONLY schema inspection
-- Run this in MySQL Workbench against the MySQL server you already use.
-- It only SELECTs. It never changes anything.
-- Copy the results (or export them) so the schema script can be
-- reconciled with what you already have.
-- =====================================================================
SELECT schema_name AS database_name FROM information_schema.schemata
 WHERE schema_name NOT IN ('information_schema','mysql','performance_schema','sys')
 ORDER BY schema_name;

-- Replace YOUR_DATABASE below with the database that holds your existing tables
SET @db = 'YOUR_DATABASE';

SELECT table_name, table_rows, engine
  FROM information_schema.tables WHERE table_schema = @db ORDER BY table_name;

SELECT table_name, column_name, column_type, is_nullable, column_key, column_default, extra
  FROM information_schema.columns WHERE table_schema = @db
 ORDER BY table_name, ordinal_position;

SELECT table_name, constraint_name, column_name, referenced_table_name, referenced_column_name
  FROM information_schema.key_column_usage
 WHERE table_schema = @db AND referenced_table_name IS NOT NULL;

SELECT VERSION() AS mysql_version;
