-- =====================================================================
-- Petsa de Sweldo - upgrade a VERSION 1 database to VERSION 2   (safe to re-run)
--
-- Run this ONLY if you already ran the version-1 schema.sql. For a brand-new install run schema.sql.
--
-- What it changes (all additive - no data is deleted, no column is dropped):
--   employees  + employment_type, shift_start, shift_end, workdays, break_minutes
--              (existing employees get the default schedule 08:00-17:00, Mon-Fri, 60 min break)
--   users      role now also allows 'FINANCE'            (existing values are unchanged)
--   payroll    + deductions, approval_status, status_updated_at, approved_by/at, released_by/at
--              late_deduction gets DEFAULT 0
--              rows of periods that were already 'Processed' in v1 become 'Released' so employees keep seeing them
--   audit_log  new table (the v1 table activity_log is left untouched and is no longer used by the app)
-- Every step checks information_schema first, so running the script twice does nothing the second time.
-- =====================================================================
USE petsa_de_sweldo;

-- ---- employees: new columns -------------------------------------------------
SET @s = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'employees' AND column_name = 'employment_type') = 0,
  'ALTER TABLE employees ADD COLUMN employment_type ENUM(''Full-Time'',''Part-Time'',''Agency-Hired'') NOT NULL DEFAULT ''Full-Time'' AFTER status', 'SELECT ''employees.employment_type already present''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'employees' AND column_name = 'shift_start') = 0,
  'ALTER TABLE employees ADD COLUMN shift_start TIME NOT NULL DEFAULT ''08:00:00'' AFTER employment_type', 'SELECT ''employees.shift_start already present''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'employees' AND column_name = 'shift_end') = 0,
  'ALTER TABLE employees ADD COLUMN shift_end TIME NOT NULL DEFAULT ''17:00:00'' AFTER shift_start', 'SELECT ''employees.shift_end already present''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'employees' AND column_name = 'workdays') = 0,
  'ALTER TABLE employees ADD COLUMN workdays VARCHAR(27) NOT NULL DEFAULT ''Mon,Tue,Wed,Thu,Fri'' AFTER shift_end', 'SELECT ''employees.workdays already present''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'employees' AND column_name = 'break_minutes') = 0,
  'ALTER TABLE employees ADD COLUMN break_minutes SMALLINT UNSIGNED NOT NULL DEFAULT 60 AFTER workdays', 'SELECT ''employees.break_minutes already present''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- users.role: allow FINANCE (widening an ENUM keeps all existing values) ---
SET @s = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'role' AND column_type LIKE '%FINANCE%') = 0,
  'ALTER TABLE users MODIFY role ENUM(''ADMIN'',''FINANCE'',''EMPLOYEE'') NOT NULL', 'SELECT ''users.role already allows FINANCE''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- payroll ------------------------------------------------------------------
-- remember whether approval_status is new, so v1 "Processed" periods are converted exactly once
SET @fresh = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'payroll' AND column_name = 'approval_status') = 0;

SET @s = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'payroll' AND column_name = 'deductions') = 0,
  'ALTER TABLE payroll ADD COLUMN deductions DECIMAL(12,2) NOT NULL DEFAULT 0 AFTER gross', 'SELECT ''payroll.deductions already present''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = IF(@fresh,
  'ALTER TABLE payroll ADD COLUMN approval_status ENUM(''Pending'',''Approved'',''Released'') NOT NULL DEFAULT ''Pending'', ADD COLUMN status_updated_at DATETIME NULL, ADD COLUMN approved_by VARCHAR(30) NULL, ADD COLUMN approved_at DATETIME NULL, ADD COLUMN released_by VARCHAR(30) NULL, ADD COLUMN released_at DATETIME NULL, ADD KEY idx_payroll_status (approval_status)',
  'SELECT ''payroll approval columns already present''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = IF(@fresh,
  'UPDATE payroll r JOIN payroll_periods p ON p.id = r.period_id SET r.approval_status = ''Released'', r.status_updated_at = p.processed_at, r.released_at = p.processed_at WHERE p.status = ''Processed''',
  'SELECT ''v1 processed periods already converted''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'payroll' AND column_name = 'late_deduction' AND column_default IS NULL) = 1,
  'ALTER TABLE payroll MODIFY late_deduction DECIMAL(12,2) NOT NULL DEFAULT 0', 'SELECT ''payroll.late_deduction default already set''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---- audit_log ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  occurred_at  DATETIME     NOT NULL,
  category     ENUM('SYSTEM','PAYROLL') NOT NULL,
  actor        VARCHAR(30)  NOT NULL,
  action       VARCHAR(40)  NOT NULL,
  target       VARCHAR(40)  NOT NULL,
  details      VARCHAR(255) NOT NULL,
  result       ENUM('Success','Attention') NOT NULL DEFAULT 'Success',
  PRIMARY KEY (id),
  KEY idx_audit_category (category, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SELECT 'Upgrade to version 2 complete' AS result;
